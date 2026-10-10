package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeReadReceiptEvent;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeReadReceiptEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class NoticeReceiptService {
    private static final DateTimeFormatter RECEIPT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final NoticeMessageMapper messageMapper;
    private final NoticeAudienceSnapshotMapper audienceMapper;
    private final NoticeReadReceiptEventMapper receiptMapper;

    public NoticeReceiptService(NoticeMessageMapper messageMapper, NoticeAudienceSnapshotMapper audienceMapper,
                                NoticeReadReceiptEventMapper receiptMapper) {
        this.messageMapper = messageMapper;
        this.audienceMapper = audienceMapper;
        this.receiptMapper = receiptMapper;
    }

    @Transactional
    public void recordRead(Long messageId, Long userId, String entry) {
        String idempotencyKey = "NOTICE_READ:" + messageId + ":" + userId;
        NoticeMessage message = messageMapper.selectOne(new QueryWrapper<NoticeMessage>()
            .eq("id", messageId)
            .last("for update"));
        if (message == null || !Objects.equals(message.userId, userId)) {
            throw BusinessException.notFound("消息不存在");
        }
        NoticeReadReceiptEvent existing = receiptMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            repairAudienceRead(existing);
            return;
        }

        NoticeAudienceSnapshot audience = lockAudience(message.audienceId, messageId);
        LocalDateTime readTime = LocalDateTime.now();
        NoticeReadReceiptEvent receipt = new NoticeReadReceiptEvent();
        receipt.receiptNo = nextReceiptNo(readTime);
        receipt.batchId = audience == null ? message.batchId : audience.batchId;
        receipt.audienceId = audience == null ? message.audienceId : audience.id;
        // 广播消息（携带 batchId/audienceId 关联公告投递上下文）的 businessId 即 notice.id，
        // 可作为回执的 notice_id；业务通知消息（无 batchId 且无 audienceId）的 businessId 是
        // 业务实体 id（如 reservation.id），不应写入 notice_id 列污染公告 id 空间。
        receipt.noticeId = resolveReceiptNoticeId(audience, message);
        receipt.messageId = messageId;
        receipt.userId = userId;
        receipt.entry = normalizeEntry(entry);
        receipt.readTime = readTime;
        receipt.idempotencyKey = idempotencyKey;
        receipt.metadataJson = "{\"providerEnv\":\"DEV\",\"providerName\":\"IN_APP_MESSAGE\","
            + "\"configVersion\":\"IN_APP_MESSAGE_V1\"}";
        receipt.createTime = readTime;
        receiptMapper.insert(receipt);

        markAudienceRead(audience, readTime);
    }

    @Transactional
    public void recordReads(List<NoticeMessage> messages, Long userId, String entry) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (NoticeMessage message : messages) {
            if (message != null && message.id != null && Objects.equals(message.userId, userId)) {
                recordRead(message.id, userId, entry);
            }
        }
    }

    private void repairAudienceRead(NoticeReadReceiptEvent receipt) {
        if (receipt.audienceId == null) {
            return;
        }
        NoticeAudienceSnapshot audience = lockAudience(receipt.audienceId, receipt.messageId);
        markAudienceRead(audience, receipt.readTime == null ? LocalDateTime.now() : receipt.readTime);
    }

    private Long resolveReceiptNoticeId(NoticeAudienceSnapshot audience, NoticeMessage message) {
        if (audience != null) {
            return audience.noticeId;
        }
        // audience 为 null 时仅广播消息（worker 已为其写入 batchId/audienceId）允许用
        // message.businessId 兜底 notice.id；业务通知消息无投递上下文，notice_id 留空。
        if (message.batchId != null || message.audienceId != null) {
            return message.businessId;
        }
        return null;
    }

    private NoticeAudienceSnapshot lockAudience(Long audienceId, Long messageId) {
        if (audienceId == null && messageId == null) {
            return null;
        }
        QueryWrapper<NoticeAudienceSnapshot> wrapper = new QueryWrapper<NoticeAudienceSnapshot>()
            .eq("deleted", 0);
        if (audienceId != null) {
            wrapper.eq("id", audienceId);
        } else {
            wrapper.eq("message_id", messageId);
        }
        return audienceMapper.selectOne(wrapper.last("for update"));
    }

    private void markAudienceRead(NoticeAudienceSnapshot audience, LocalDateTime readTime) {
        if (audience == null || "READ".equals(audience.readStatus)) {
            return;
        }
        audience.readStatus = "READ";
        audience.readTime = readTime;
        audience.updateTime = readTime;
        audienceMapper.updateById(audience);
    }

    private String normalizeEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "MESSAGE_CENTER";
        }
        return entry.trim().toUpperCase(Locale.ROOT);
    }

    private String nextReceiptNo(LocalDateTime now) {
        return "NR" + now.format(RECEIPT_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
