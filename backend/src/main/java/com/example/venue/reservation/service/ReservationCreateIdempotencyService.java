package com.example.venue.reservation.service;

import com.example.venue.common.BusinessException;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.entity.ReservationCreateCommand;
import com.example.venue.reservation.enums.ReservationSubmissionState;
import com.example.venue.reservation.exception.ReservationSubmissionStateException;
import com.example.venue.reservation.mapper.ReservationCreateCommandMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 预约创建的请求身份与结果核对。
 *
 * <p>沿用社区域已验证的 claim/complete/replay 形状，但表、实体、Mapper 与作用域都归预约域所有，
 * 不复用社区域私有命令表。键作用域包含用户：不同用户无法命中彼此的请求或预约。</p>
 *
 * <p>事务约定：claim 与预约创建处于同一事务。原事务回滚会一并删除 claim 记录，但查询无记录也可能是事务尚未提交，
 * 客户端必须保留原请求键；重放路径必须在任何副作用之前返回。</p>
 */
@Service
public class ReservationCreateIdempotencyService {
    /** 请求键字符集与长度：只接受无语义歧义的标识，避免空白、控制字符与超长键。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    /** 同键并发时败者等待胜者事务结束；胜者回滚则记录消失，允许有限次重试插入。 */
    private static final int CLAIM_ATTEMPTS = 3;
    private static final int MAX_CONTACT_LENGTH = 64;
    private static final int MAX_REMARK_LENGTH = 200;

    private final ReservationCreateCommandMapper mapper;

    public ReservationCreateIdempotencyService(ReservationCreateCommandMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 归一化请求键。返回 {@code null} 表示未提供，调用方保持旧调用语义（不启用幂等）。
     * 提供了但不合法时显式拒绝，避免把空格、超长或控制字符当作不同意图。
     */
    public String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw BusinessException.badRequest("Idempotency-Key 必须是 8 到 128 位的字母、数字、下划线或短横线");
        }
        return key;
    }

    /**
     * 载荷指纹：覆盖所有影响业务结果的输入。座位顺序不影响语义，因此排序后参与指纹；
     * 联系人姓名与电话只以哈希形式参与，不写入记录正文。
     */
    public String fingerprint(CreateReservationRequest request, LocalDateTime startTime, LocalDateTime endTime) {
        List<Long> seatIds = new ArrayList<>();
        if (request.seatIds != null) {
            for (Long seatId : request.seatIds) {
                if (seatId != null) {
                    seatIds.add(seatId);
                }
            }
        }
        seatIds.sort(Long::compareTo);
        return sha256(
            request.spaceId,
            startTime,
            endTime,
            request.participantCount,
            seatIds,
            request.userCouponId,
            truncate(request.contactName, MAX_CONTACT_LENGTH),
            truncate(request.contactPhone, MAX_CONTACT_LENGTH),
            truncate(request.remark, MAX_REMARK_LENGTH));
    }

    /**
     * 抢占请求身份。
     *
     * <p>唯一索引插入串行化同一键：败者等待胜者事务结束后读到真实状态。
     * 若胜者已提交，返回可重放的记录；若胜者仍在处理中，抛出可识别的提交状态冲突，
     * 让前端进入「结果待核对」而不是改换键重投。</p>
     */
    public Claim claim(Long userId, String idempotencyKey, String requestHash) {
        DuplicateKeyException lastDuplicate = null;
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            ReservationCreateCommand command = new ReservationCreateCommand();
            command.userId = userId;
            command.idempotencyKey = idempotencyKey;
            command.requestHash = requestHash;
            command.status = "PROCESSING";
            command.createTime = LocalDateTime.now();
            try {
                mapper.insert(command);
                return new Claim(command, false);
            } catch (DuplicateKeyException duplicate) {
                // 唯一键已存在：读取胜者（只命中唯一索引范围内一行，不产生间隙锁）。
                lastDuplicate = duplicate;
                ReservationCreateCommand winner = mapper.findForUpdate(userId, idempotencyKey);
                if (winner != null) {
                    return replay(winner, requestHash);
                }
                // 胜者事务已回滚，记录不再存在；此时按新请求重试插入，而不是伪报冲突。
            }
        }
        throw lastDuplicate;
    }

    /** 事务内把请求身份标记为已完成，指向真实预约。 */
    public void complete(Claim claim, Long reservationId, String resultStatus) {
        if (claim.replayed()) {
            return;
        }
        if (mapper.complete(claim.command().id, reservationId, resultStatus, LocalDateTime.now()) != 1) {
            throw new ReservationSubmissionStateException(ReservationSubmissionState.SUBMISSION_IN_PROGRESS,
                "这次提交仍在处理中，请先核对提交结果");
        }
        claim.command().status = "COMPLETED";
        claim.command().reservationId = reservationId;
        claim.command().resultStatus = resultStatus;
    }

    /** 只读核对：返回 null 只表示当前读视图无结果，不排除未提交事务。 */
    public ReservationCreateCommand find(Long userId, String idempotencyKey) {
        return mapper.findForRead(userId, idempotencyKey);
    }

    private Claim replay(ReservationCreateCommand existing, String requestHash) {
        if (!Objects.equals(existing.requestHash, requestHash)) {
            throw new ReservationSubmissionStateException(ReservationSubmissionState.SUBMISSION_KEY_REUSED,
                "同一 Idempotency-Key 对应了不同请求，请重新发起提交");
        }
        if (!"COMPLETED".equals(existing.status)) {
            throw new ReservationSubmissionStateException(ReservationSubmissionState.SUBMISSION_IN_PROGRESS,
                "这次提交仍在处理中，请先核对提交结果");
        }
        return new Claim(existing, true);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String sha256(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** {@code replayed=true} 表示命中既有请求，调用方必须直接返回原结果且不产生任何副作用。 */
    public record Claim(ReservationCreateCommand command, boolean replayed) {
    }
}
