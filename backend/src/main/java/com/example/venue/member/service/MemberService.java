package com.example.venue.member.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberAccountSummary;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.dto.MemberOverviewResponse;
import com.example.venue.member.dto.SaveMemberLevelRequest;
import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.entity.MemberAccountLog;
import com.example.venue.member.entity.MemberLevel;
import com.example.venue.member.entity.MemberProfileTag;
import com.example.venue.member.mapper.MemberAccountLogMapper;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.member.mapper.MemberLevelMapper;
import com.example.venue.member.mapper.MemberProfileTagMapper;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.security.SecurityContext;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class MemberService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final BigDecimal PRICE_SENSITIVE_DISCOUNT_THRESHOLD = new BigDecimal("200.00");
    private static final int DORMANT_DAYS_THRESHOLD = 60;
    private static final int OVERVIEW_USER_LIMIT = 10;
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";
    private static final String DEFAULT_RULE_VERSION = "B2_MEMBER_V1";
    private static final String MEMBER_POINTS_CHANGED = "MEMBER_POINTS_CHANGED";
    private static final String MEMBER_REFUND_REVERSED = "MEMBER_REFUND_REVERSED";

    private final MemberLevelMapper memberLevelMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final MemberAccountLogMapper memberAccountLogMapper;
    private final MemberProfileTagMapper memberProfileTagMapper;
    private final SysUserMapper userMapper;
    private final BusinessEventService businessEventService;
    private final ObjectMapper objectMapper;
    private final EntityExistenceService entityExistenceService;
    private final MemberPointsService memberPointsService;

    public MemberService(MemberLevelMapper memberLevelMapper, MemberAccountMapper memberAccountMapper,
                         MemberAccountLogMapper memberAccountLogMapper, MemberProfileTagMapper memberProfileTagMapper,
                         SysUserMapper userMapper, BusinessEventService businessEventService,
                         ObjectMapper objectMapper, EntityExistenceService entityExistenceService,
                         MemberPointsService memberPointsService) {
        this.memberLevelMapper = memberLevelMapper;
        this.memberAccountMapper = memberAccountMapper;
        this.memberAccountLogMapper = memberAccountLogMapper;
        this.memberProfileTagMapper = memberProfileTagMapper;
        this.userMapper = userMapper;
        this.businessEventService = businessEventService;
        this.objectMapper = objectMapper;
        this.entityExistenceService = entityExistenceService;
        this.memberPointsService = memberPointsService;
    }

    public List<MemberLevelResponse> levels() {
        return memberLevelMapper.selectList(new QueryWrapper<MemberLevel>()
                .eq("deleted", 0)
                .orderByAsc("growth_threshold", "sort_order", "id"))
            .stream()
            .map(this::toLevelResponse)
            .toList();
    }

    @Transactional
    public MemberLevelResponse saveLevel(SaveMemberLevelRequest request) {
        String levelCode = normalizeLevelCode(request == null ? null : request.levelCode);
        validateLevelRequest(request, levelCode);
        if (memberLevelMapper.findByCode(levelCode) != null) {
            throw BusinessException.conflict("会员等级编码已存在");
        }
        MemberLevel level = new MemberLevel();
        copyLevel(request, level, levelCode);
        level.createTime = LocalDateTime.now();
        level.updateTime = level.createTime;
        level.deleted = 0;
        memberLevelMapper.insert(level);
        return toLevelResponse(level);
    }

    @Transactional
    public MemberLevelResponse updateLevel(Long id, SaveMemberLevelRequest request) {
        MemberLevel level = requireLevel(id);
        String levelCode = normalizeLevelCode(request == null ? null : request.levelCode);
        validateLevelRequest(request, levelCode);
        MemberLevel sameCode = memberLevelMapper.findByCode(levelCode);
        if (sameCode != null && !Objects.equals(sameCode.id, id)) {
            throw BusinessException.conflict("会员等级编码已存在");
        }
        copyLevel(request, level, levelCode, level.status, level.ruleVersion);
        level.updateTime = LocalDateTime.now();
        memberLevelMapper.updateById(level);
        return toLevelResponse(level);
    }

    @Transactional
    public void updateLevelStatus(Long id, String status) {
        MemberLevel level = requireLevel(id);
        level.status = normalizeLevelStatus(status);
        level.updateTime = LocalDateTime.now();
        memberLevelMapper.updateById(level);
    }

    public PageResult<MemberAccountSummary> accounts(PageQuery query, String keyword) {
        QueryWrapper<MemberAccount> wrapper = new QueryWrapper<MemberAccount>().eq("deleted", 0);
        List<Long> keywordUserIds = keywordUserIds(keyword);
        if (keyword != null && !keyword.isBlank()) {
            if (keywordUserIds.isEmpty()) {
                return PageResult.of(List.of(), 0, query.getPageNum(), query.getPageSize());
            }
            wrapper.in("user_id", keywordUserIds);
        }
        Page<MemberAccount> page = memberAccountMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            wrapper.orderByDesc("last_active_time").orderByDesc("id")
        );
        return PageResult.of(page.getRecords().stream().map(this::toAccountSummary).toList(),
            page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public MemberAccountResponse account(Long userId) {
        // AUD-P2-13: 管理员 GET 会员账户前校验 SysUser 存在且启用，避免对不存在的用户建立幻影 member_account 污染会员总览
        entityExistenceService.requireActiveUser(userId);
        MemberAccount account = ensureAccount(userId);
        return toAccountResponse(account);
    }

    public MemberAccountResponse accountSnapshotOrDefault(Long userId) {
        if (userId == null) {
            throw BusinessException.badRequest("用户ID不能为空");
        }
        MemberAccount account = memberAccountMapper.findByUserId(userId);
        if (account != null) {
            return toAccountResponse(account);
        }
        List<MemberLevel> activeLevels = activeLevelsOrThrow();
        MemberLevel defaultLevel = selectDefaultLevel(activeLevels);
        return toDefaultAccountResponse(newDefaultAccountSnapshot(userId, defaultLevel), activeLevels, defaultLevel);
    }

    public MemberAccountResponse currentAccount() {
        Long userId = SecurityContext.currentUserId();
        if (userId == null) {
            throw BusinessException.forbidden("未登录");
        }
        return account(userId);
    }

    public MemberOverviewResponse overview() {
        MemberOverviewResponse response = new MemberOverviewResponse();
        response.totalMembers = memberAccountMapper.countOverviewMembers();
        response.activeMembers = memberAccountMapper.countOverviewActiveMembers();
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (Map<String, Object> row : memberAccountMapper.selectOverviewLevelDistribution()) {
            Object levelCode = row.get("levelCode");
            Object memberCount = row.get("memberCount");
            if (memberCount instanceof Number count) {
                distribution.put(levelCode == null ? null : levelCode.toString(), count.longValue());
            }
        }
        response.levelDistribution = distribution;
        response.coreUsers = memberAccountMapper.selectOverviewCoreMembers(OVERVIEW_USER_LIMIT).stream()
            .map(this::toAccountSummary)
            .toList();
        response.dormantUsers = memberAccountMapper
            .selectOverviewDormantMembers(LocalDateTime.now().minusDays(DORMANT_DAYS_THRESHOLD), OVERVIEW_USER_LIMIT)
            .stream()
            .map(this::toAccountSummary)
            .toList();
        return response;
    }

    @Transactional
    public void recordPaidOrder(BizOrder order, Reservation reservation) {
        if (order == null || order.id == null) {
            throw BusinessException.badRequest("订单不能为空");
        }
        Long userId = order.userId != null ? order.userId : reservation == null ? null : reservation.userId;
        if (userId == null) {
            throw BusinessException.badRequest("会员账户用户不能为空");
        }
        BigDecimal paidAmount = amountOrDefault(order.paidAmount, amountOrDefault(order.payableAmount, order.totalAmount));
        int growthDelta = pointsFromAmount(paidAmount);
        BigDecimal consumptionDelta = amountOrZero(paidAmount);
        MemberAccountLog existingFact = memberAccountLogMapper.findByEventAndOrder(MEMBER_POINTS_CHANGED, order.id);
        if (existingFact != null) {
            memberPointsService.requireCompatibleOrderFact(existingFact, userId,
                "ORDER_PAID_POINTS:" + order.id, MEMBER_POINTS_CHANGED, "R4_POINTS_V1",
                growthDelta, growthDelta, consumptionDelta, order.id, "PAYMENT", "PAID_ORDER");
            return;
        }
        MemberAccount account = ensureAccountForSettlement(userId);
        existingFact = memberAccountLogMapper.findByEventAndOrder(MEMBER_POINTS_CHANGED, order.id);
        if (existingFact != null) {
            memberPointsService.requireCompatibleOrderFact(existingFact, userId,
                "ORDER_PAID_POINTS:" + order.id, MEMBER_POINTS_CHANGED, "R4_POINTS_V1",
                growthDelta, growthDelta, consumptionDelta, order.id, "PAYMENT", "PAID_ORDER");
            return;
        }
        String beforeLevelCode = account.levelCode;
        int nextGrowthValue = intOrZero(account.growthValue) + growthDelta;
        MemberLevel promotedLevel = memberLevelMapper.findActiveLevelForGrowth(nextGrowthValue);
        String afterLevelCode = promotedLevel == null ? account.levelCode : promotedLevel.levelCode;
        account.growthValue = nextGrowthValue;
        account.totalConsumptionAmount = amountOrZero(account.totalConsumptionAmount).add(consumptionDelta).setScale(2, RoundingMode.HALF_UP);
        account.totalReservations = intOrZero(account.totalReservations) + 1;
        account.totalMemberDiscountAmount = amountOrZero(account.totalMemberDiscountAmount)
            .add(amountOrZero(order.memberDiscountAmount))
            .setScale(2, RoundingMode.HALF_UP);
        account.lastActiveTime = LocalDateTime.now();
        boolean levelChanged = promotedLevel != null && !Objects.equals(promotedLevel.levelCode, account.levelCode);
        if (promotedLevel != null) {
            applyLevelSnapshot(account, promotedLevel);
        }
        account.profileTagsJson = toJson(profileTagsFor(account));
        account.updateTime = LocalDateTime.now();
        MemberAccountLog pointsFact = memberPointsService.appendLocked(
            account, "ORDER_PAID_POINTS:" + order.id, MEMBER_POINTS_CHANGED, "R4_POINTS_V1",
            growthDelta, growthDelta, consumptionDelta, order.id, "PAYMENT", "PAID_ORDER",
            "支付成功累计会员成长值和积分", reservation == null ? order.reservationId : reservation.id,
            beforeLevelCode, afterLevelCode,
            toJson(Map.of(
                "orderNo", order.orderNo == null ? "" : order.orderNo,
                "memberDiscountAmount", amountOrZero(order.memberDiscountAmount))));
        if (!Objects.equals(pointsFact.businessKey, "ORDER_PAID_POINTS:" + order.id)) {
            return;
        }
        refreshProfileTags(account);
        recordPointsEvent(account, order, reservation, beforeLevelCode, growthDelta, consumptionDelta);
        if (levelChanged) {
            recordLevelEvent(account, order, reservation, beforeLevelCode);
        }
    }

    @Transactional
    public void reverseRefundedOrder(BizOrder order, Reservation reservation) {
        if (order == null || order.id == null) {
            throw BusinessException.badRequest("订单不能为空");
        }
        if (memberAccountLogMapper.findByEventAndOrder(MEMBER_REFUND_REVERSED, order.id) != null) {
            return;
        }
        if (memberAccountLogMapper.findByBusinessKey("ORDER_REFUND_POINTS_EXCEPTION:" + order.id) != null) {
            return;
        }
        Long userId = order.userId != null ? order.userId : reservation == null ? null : reservation.userId;
        if (userId == null) {
            throw BusinessException.badRequest("会员账户用户不能为空");
        }
        MemberAccount account = ensureAccountForSettlement(userId);
        if (memberAccountLogMapper.findByEventAndOrder(MEMBER_REFUND_REVERSED, order.id) != null) {
            return;
        }
        if (memberAccountLogMapper.findByBusinessKey("ORDER_REFUND_POINTS_EXCEPTION:" + order.id) != null) {
            return;
        }

        String beforeLevelCode = account.levelCode;
        MemberAccountLog paidFact;
        MemberPointsService.PaidOrderFactException paidFactException = null;
        int growthDelta;
        int pointsDelta;
        BigDecimal consumptionDelta;
        try {
            paidFact = memberPointsService.requirePaidOrderFact(order.id, userId, account.id);
            growthDelta = intOrZero(paidFact.growthDelta);
            pointsDelta = intOrZero(paidFact.pointsDelta);
            consumptionDelta = amountOrZero(paidFact.consumptionDelta);
        } catch (MemberPointsService.PaidOrderFactException exception) {
            paidFactException = exception;
            BigDecimal refundedAmount = amountOrDefault(order.paidAmount,
                amountOrDefault(order.payableAmount, order.totalAmount));
            growthDelta = pointsFromAmount(refundedAmount);
            pointsDelta = 0;
            consumptionDelta = amountOrZero(refundedAmount);
        }
        int nextGrowthValue = Math.max(0, intOrZero(account.growthValue) - growthDelta);
        MemberLevel derivedLevel = memberLevelMapper.findActiveLevelForGrowth(nextGrowthValue);
        String afterLevelCode = derivedLevel == null ? account.levelCode : derivedLevel.levelCode;
        account.growthValue = nextGrowthValue;
        account.totalConsumptionAmount = amountOrZero(account.totalConsumptionAmount)
            .subtract(consumptionDelta)
            .max(ZERO_AMOUNT)
            .setScale(2, RoundingMode.HALF_UP);
        account.totalReservations = Math.max(0, intOrZero(account.totalReservations) - 1);
        account.totalMemberDiscountAmount = amountOrZero(account.totalMemberDiscountAmount)
            .subtract(amountOrZero(order.memberDiscountAmount))
            .max(ZERO_AMOUNT)
            .setScale(2, RoundingMode.HALF_UP);
        boolean levelChanged = derivedLevel != null && !Objects.equals(derivedLevel.levelCode, account.levelCode);
        if (derivedLevel != null) {
            applyLevelSnapshot(account, derivedLevel);
        }
        account.profileTagsJson = toJson(profileTagsFor(account));
        account.updateTime = LocalDateTime.now();
        boolean requiresManualReview = paidFactException != null;
        String businessKey = (requiresManualReview ? "ORDER_REFUND_POINTS_EXCEPTION:" : "ORDER_REFUND_POINTS:")
            + order.id;
        String eventType = requiresManualReview ? "MEMBER_REFUND_POINTS_EXCEPTION" : MEMBER_REFUND_REVERSED;
        String reasonCode = requiresManualReview ? paidFactException.reasonCode() : "FULL_REFUND";
        String reasonText = requiresManualReview
            ? paidFactException.getMessage() + "；非积分会员投影已按既有退款规则冲销"
            : "全额退款精确冲销原支付会员成长值、积分与累计消费";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNo", order.orderNo == null ? "" : order.orderNo);
        metadata.put("memberDiscountAmount", amountOrZero(order.memberDiscountAmount));
        metadata.put("reversal", true);
        metadata.put("requiresManualReview", requiresManualReview);
        MemberAccountLog refundFact = memberPointsService.appendLocked(
            account, businessKey, eventType, "R4_POINTS_V1",
            -pointsDelta, -growthDelta, consumptionDelta.negate(), order.id, "REFUND", reasonCode,
            reasonText,
            reservation == null ? order.reservationId : reservation.id,
            beforeLevelCode, afterLevelCode,
            toJson(metadata));
        if (!Objects.equals(refundFact.businessKey, businessKey)) {
            return;
        }
        refreshProfileTags(account);
        recordRefundPointsEvent(account, order, reservation, beforeLevelCode, -growthDelta, -pointsDelta,
            consumptionDelta.negate(), reasonCode, reasonText);
        if (levelChanged) {
            recordRefundLevelEvent(account, order, reservation, beforeLevelCode);
        }
    }

    private MemberAccount ensureAccountForSettlement(Long userId) {
        MemberAccount account = memberAccountMapper.findByUserIdForUpdate(userId);
        if (account != null) {
            return account;
        }
        ensureAccount(userId);
        MemberAccount lockedAccount = memberAccountMapper.findByUserIdForUpdate(userId);
        if (lockedAccount == null) {
            throw BusinessException.conflict("会员账户创建后未找到");
        }
        return lockedAccount;
    }

    private MemberAccount ensureAccount(Long userId) {
        MemberAccount account = memberAccountMapper.findByUserId(userId);
        if (account != null) {
            return account;
        }
        MemberLevel defaultLevel = defaultActiveLevel();
        MemberAccount created = new MemberAccount();
        created.userId = userId;
        applyLevelSnapshot(created, defaultLevel);
        created.growthValue = 0;
        created.pointsBalance = 0;
        created.totalConsumptionAmount = ZERO_AMOUNT;
        created.totalReservations = 0;
        created.totalMemberDiscountAmount = ZERO_AMOUNT;
        created.lastActiveTime = LocalDateTime.now();
        created.profileTagsJson = toJson(List.of("NEW_USER"));
        created.status = ACTIVE;
        created.createTime = LocalDateTime.now();
        created.updateTime = created.createTime;
        created.deleted = 0;
        try {
            memberAccountMapper.insert(created);
        } catch (DuplicateKeyException ex) {
            MemberAccount concurrentAccount = memberAccountMapper.findByUserId(userId);
            if (concurrentAccount != null) {
                return concurrentAccount;
            }
            throw ex;
        }
        refreshProfileTags(created);
        return created;
    }

    private MemberLevel defaultActiveLevel() {
        return selectDefaultLevel(activeLevelsOrThrow());
    }

    private List<MemberLevel> activeLevelsOrThrow() {
        List<MemberLevel> activeLevels = memberLevelMapper.findActiveLevels();
        if (activeLevels == null || activeLevels.isEmpty()) {
            throw BusinessException.badRequest("缺少启用的会员等级");
        }
        return activeLevels;
    }

    private MemberLevel selectDefaultLevel(List<MemberLevel> activeLevels) {
        return activeLevels.stream()
            .min(Comparator.comparing((MemberLevel level) -> intOrZero(level.growthThreshold))
                .thenComparing(level -> intOrZero(level.sortOrder))
                .thenComparing(level -> level.id == null ? Long.MAX_VALUE : level.id))
            .orElseThrow();
    }

    private MemberAccount newDefaultAccountSnapshot(Long userId, MemberLevel defaultLevel) {
        MemberAccount snapshot = new MemberAccount();
        snapshot.userId = userId;
        applyLevelSnapshot(snapshot, defaultLevel);
        snapshot.growthValue = 0;
        snapshot.pointsBalance = 0;
        snapshot.totalConsumptionAmount = ZERO_AMOUNT;
        snapshot.totalReservations = 0;
        snapshot.totalMemberDiscountAmount = ZERO_AMOUNT;
        snapshot.status = ACTIVE;
        snapshot.deleted = 0;
        return snapshot;
    }

    private void validateLevelRequest(SaveMemberLevelRequest request, String levelCode) {
        if (request == null) {
            throw BusinessException.badRequest("会员等级参数不能为空");
        }
        if (levelCode.isBlank()) {
            throw BusinessException.badRequest("等级编码不能为空");
        }
        if (request.levelName == null || request.levelName.isBlank()) {
            throw BusinessException.badRequest("等级名称不能为空");
        }
        if (request.growthThreshold == null || request.growthThreshold < 0) {
            throw BusinessException.badRequest("成长值门槛不能小于 0");
        }
        if (request.discountRate == null
            || request.discountRate.compareTo(BigDecimal.ZERO) <= 0
            || request.discountRate.compareTo(BigDecimal.ONE) > 0) {
            throw BusinessException.badRequest("折扣率必须大于 0 且小于等于 1");
        }
    }

    private void copyLevel(SaveMemberLevelRequest request, MemberLevel level, String levelCode) {
        copyLevel(request, level, levelCode, ACTIVE, DEFAULT_RULE_VERSION);
    }

    private void copyLevel(SaveMemberLevelRequest request, MemberLevel level, String levelCode, String defaultStatus,
                           String defaultRuleVersion) {
        level.levelCode = levelCode;
        level.levelName = request.levelName.trim();
        level.growthThreshold = request.growthThreshold;
        level.discountRate = request.discountRate.setScale(4, RoundingMode.HALF_UP);
        level.benefitText = trimToNull(request.benefitText);
        level.ruleVersion = request.ruleVersion == null || request.ruleVersion.isBlank()
            ? ruleVersionOrDefault(defaultRuleVersion)
            : request.ruleVersion.trim();
        level.status = request.status == null || request.status.isBlank()
            ? normalizeLevelStatus(defaultStatus)
            : normalizeLevelStatus(request.status);
        level.sortOrder = request.growthThreshold;
        if (level.effectiveTime == null) {
            level.effectiveTime = LocalDateTime.now();
        }
    }

    private String ruleVersionOrDefault(String defaultRuleVersion) {
        if (defaultRuleVersion == null || defaultRuleVersion.isBlank()) {
            return DEFAULT_RULE_VERSION;
        }
        return defaultRuleVersion.trim();
    }

    private MemberLevel requireLevel(Long id) {
        MemberLevel level = memberLevelMapper.selectById(id);
        if (level == null || Integer.valueOf(1).equals(level.deleted)) {
            throw BusinessException.notFound("会员等级不存在");
        }
        return level;
    }

    private MemberAccountSummary toAccountSummary(MemberAccount account) {
        MemberAccountSummary response = new MemberAccountSummary();
        fillAccountSummary(response, account);
        return response;
    }

    private MemberAccountResponse toAccountResponse(MemberAccount account) {
        MemberAccountResponse response = new MemberAccountResponse();
        fillAccountSummary(response, account);
        List<MemberLevel> activeLevels = memberLevelMapper.findActiveLevels();
        MemberLevel level = memberLevelMapper.findByCode(account.levelCode);
        if (level == null && activeLevels != null) {
            level = activeLevels.stream()
                .filter(item -> Objects.equals(item.levelCode, account.levelCode))
                .findFirst()
                .orElse(null);
        }
        if (level != null) {
            response.discountRate = level.discountRate;
            response.benefitText = level.benefitText;
        }
        MemberLevel next = activeLevels == null ? null : activeLevels.stream()
            .filter(item -> intOrZero(item.growthThreshold) > intOrZero(account.growthValue))
            .min(Comparator.comparing(item -> intOrZero(item.growthThreshold)))
            .orElse(null);
        if (next != null) {
            response.nextLevel = toLevelResponse(next);
            response.nextLevelGrowthGap = Math.max(0, next.growthThreshold - intOrZero(account.growthValue));
        }
        return response;
    }

    private MemberAccountResponse toDefaultAccountResponse(MemberAccount account, List<MemberLevel> activeLevels,
                                                           MemberLevel defaultLevel) {
        MemberAccountResponse response = new MemberAccountResponse();
        SysUser user = userMapper.selectById(account.userId);
        response.userId = account.userId;
        response.username = user == null ? null : user.username;
        response.nickname = user == null ? null : user.nickname;
        response.memberAccountId = null;
        response.levelCode = account.levelCode;
        response.levelName = account.levelName;
        response.growthValue = intOrZero(account.growthValue);
        response.pointsBalance = intOrZero(account.pointsBalance);
        response.totalConsumptionAmount = amountOrZero(account.totalConsumptionAmount);
        response.totalReservations = intOrZero(account.totalReservations);
        response.totalMemberDiscountAmount = amountOrZero(account.totalMemberDiscountAmount);
        response.lastActiveTime = account.lastActiveTime;
        response.profileTags = List.of();
        response.discountRate = defaultLevel.discountRate;
        response.benefitText = defaultLevel.benefitText;
        MemberLevel next = activeLevels.stream()
            .filter(item -> intOrZero(item.growthThreshold) > intOrZero(account.growthValue))
            .min(Comparator.comparing(item -> intOrZero(item.growthThreshold)))
            .orElse(null);
        if (next != null) {
            response.nextLevel = toLevelResponse(next);
            response.nextLevelGrowthGap = Math.max(0, next.growthThreshold - intOrZero(account.growthValue));
        }
        return response;
    }

    private void fillAccountSummary(MemberAccountSummary response, MemberAccount account) {
        SysUser user = userMapper.selectById(account.userId);
        response.userId = account.userId;
        response.username = user == null ? null : user.username;
        response.nickname = user == null ? null : user.nickname;
        response.memberAccountId = account.id;
        response.levelCode = account.levelCode;
        response.levelName = account.levelName;
        response.growthValue = intOrZero(account.growthValue);
        response.pointsBalance = intOrZero(account.pointsBalance);
        response.totalConsumptionAmount = amountOrZero(account.totalConsumptionAmount);
        response.totalReservations = intOrZero(account.totalReservations);
        response.totalMemberDiscountAmount = amountOrZero(account.totalMemberDiscountAmount);
        response.lastActiveTime = account.lastActiveTime;
        response.profileTags = profileTags(account);
    }

    private MemberLevelResponse toLevelResponse(MemberLevel level) {
        if (level == null) {
            return null;
        }
        MemberLevelResponse response = new MemberLevelResponse();
        response.id = level.id;
        response.levelCode = level.levelCode;
        response.levelName = level.levelName;
        response.growthThreshold = level.growthThreshold;
        response.discountRate = level.discountRate;
        response.benefitText = level.benefitText;
        response.ruleVersion = level.ruleVersion;
        response.status = level.status;
        return response;
    }

    private void applyLevelSnapshot(MemberAccount account, MemberLevel level) {
        account.levelId = level.id;
        account.levelCode = level.levelCode;
        account.levelName = level.levelName;
    }

    private void recordPointsEvent(MemberAccount account, BizOrder order, Reservation reservation, String beforeLevelCode,
                                   int growthDelta, BigDecimal consumptionDelta) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_POINTS_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                account.id,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .relatedEntity("ORDER", order.id)
            .idempotencyKey("member-points:" + order.id)
            .reasonCode("PAID_ORDER")
            .reasonText("支付成功累计会员成长值和积分")
            .metadata("userId", account.userId)
            .metadata("reservationId", reservation == null ? order.reservationId : reservation.id)
            .metadata("beforeLevelCode", beforeLevelCode)
            .metadata("afterLevelCode", account.levelCode)
            .metadata("growthDelta", growthDelta)
            .metadata("pointsDelta", growthDelta)
            .metadata("consumptionDelta", consumptionDelta));
    }

    private void recordLevelEvent(MemberAccount account, BizOrder order, Reservation reservation, String beforeLevelCode) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_LEVEL_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                account.id,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .relatedEntity("ORDER", order.id)
            .idempotencyKey("member-level:" + order.id)
            .reasonCode("GROWTH_PROMOTION")
            .reasonText("成长值达到更高会员等级")
            .fromStatus(beforeLevelCode)
            .toStatus(account.levelCode)
            .metadata("userId", account.userId)
            .metadata("reservationId", reservation == null ? order.reservationId : reservation.id));
    }

    private void recordRefundPointsEvent(MemberAccount account, BizOrder order, Reservation reservation,
                                         String beforeLevelCode, int growthDelta, int pointsDelta,
                                         BigDecimal consumptionDelta, String reasonCode, String reasonText) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_POINTS_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                account.id,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .relatedEntity("ORDER", order.id)
            .idempotencyKey("member-refund:" + order.id)
            .reasonCode(reasonCode)
            .reasonText(reasonText)
            .metadata("userId", account.userId)
            .metadata("reservationId", reservation == null ? order.reservationId : reservation.id)
            .metadata("beforeLevelCode", beforeLevelCode)
            .metadata("afterLevelCode", account.levelCode)
            .metadata("growthDelta", growthDelta)
            .metadata("pointsDelta", pointsDelta)
            .metadata("consumptionDelta", consumptionDelta));
    }

    private void recordRefundLevelEvent(MemberAccount account, BizOrder order, Reservation reservation,
                                        String beforeLevelCode) {
        businessEventService.record(BusinessEventRecordRequest.of(
                BusinessEventType.MEMBER_LEVEL_CHANGED,
                BusinessDomain.MEMBER,
                "MEMBER_ACCOUNT",
                account.id,
                BusinessSourceType.SYSTEM,
                "SUCCESS")
            .relatedEntity("ORDER", order.id)
            .idempotencyKey("member-refund-level:" + order.id)
            .reasonCode("REFUND_LEVEL_RECALCULATION")
            .reasonText("全额退款后按成长值重新计算会员等级")
            .fromStatus(beforeLevelCode)
            .toStatus(account.levelCode)
            .metadata("userId", account.userId)
            .metadata("reservationId", reservation == null ? order.reservationId : reservation.id));
    }

    private void refreshProfileTags(MemberAccount account) {
        memberProfileTagMapper.deactivateTagsByUserId(account.userId);
        for (String tagCode : profileTagsFor(account)) {
            MemberProfileTag tag = memberProfileTagMapper.selectOne(new QueryWrapper<MemberProfileTag>()
                .eq("user_id", account.userId)
                .eq("tag_code", tagCode)
                .last("LIMIT 1"));
            if (tag == null) {
                tag = new MemberProfileTag();
                tag.userId = account.userId;
                tag.tagCode = tagCode;
                tag.tagLabel = tagLabel(tagCode);
                tag.sourceType = "SYSTEM";
                tag.confidence = new BigDecimal("1.00");
                tag.evidenceRef = "member.account:" + (account.id == null ? account.userId : account.id);
                tag.metadataJson = profileTagMetadata(account);
                tag.generatedAt = LocalDateTime.now();
                tag.status = ACTIVE;
                tag.createTime = LocalDateTime.now();
                tag.updateTime = tag.createTime;
                memberProfileTagMapper.insert(tag);
            } else {
                tag.tagLabel = tagLabel(tagCode);
                tag.evidenceRef = "member.account:" + (account.id == null ? account.userId : account.id);
                tag.metadataJson = profileTagMetadata(account);
                tag.status = ACTIVE;
                tag.generatedAt = LocalDateTime.now();
                tag.updateTime = tag.generatedAt;
                memberProfileTagMapper.updateById(tag);
            }
        }
    }

    private List<String> profileTags(MemberAccount account) {
        List<String> tags = parseTags(account.profileTagsJson);
        if (!tags.isEmpty()) {
            return sanitizeProfileTags(account, tags);
        }
        List<MemberProfileTag> activeTags = memberProfileTagMapper.findActiveByUserId(account.userId);
        if (activeTags == null || activeTags.isEmpty()) {
            return profileTagsFor(account);
        }
        return sanitizeProfileTags(account, activeTags.stream().map(tag -> tag.tagCode).toList());
    }

    private List<String> sanitizeProfileTags(MemberAccount account, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>(tags);
        if (isDormantUser(account)) {
            if (!sanitized.contains("DORMANT_USER")) {
                sanitized.add("DORMANT_USER");
            }
        } else {
            sanitized.removeIf("DORMANT_USER"::equals);
        }
        return sanitized;
    }

    private List<String> profileTagsFor(MemberAccount account) {
        List<String> tags = new ArrayList<>();
        if (intOrZero(account.totalReservations) == 0) {
            tags.add("NEW_USER");
        }
        if (intOrZero(account.totalReservations) >= 3
            || (account.lastActiveTime != null && account.lastActiveTime.isAfter(LocalDateTime.now().minusDays(30)))) {
            tags.add("ACTIVE_USER");
        }
        if (isDormantUser(account)) {
            tags.add("DORMANT_USER");
        }
        if (isCoreUser(account)) {
            tags.add("HIGH_VALUE_USER");
        }
        if (amountOrZero(account.totalMemberDiscountAmount).compareTo(PRICE_SENSITIVE_DISCOUNT_THRESHOLD) >= 0) {
            tags.add("PRICE_SENSITIVE_USER");
        }
        if (tags.isEmpty()) {
            tags.add("ACTIVE_USER");
        }
        return tags;
    }

    private boolean isCoreUser(MemberAccount account) {
        return profileTagsFromSnapshot(account).contains("HIGH_VALUE_USER")
            || amountOrZero(account.totalConsumptionAmount).compareTo(new BigDecimal("2000.00")) >= 0
            || intOrZero(account.growthValue) >= 2000;
    }

    private boolean isDormantUser(MemberAccount account) {
        return account.lastActiveTime == null
            || account.lastActiveTime.isBefore(LocalDateTime.now().minusDays(DORMANT_DAYS_THRESHOLD));
    }

    private List<String> profileTagsFromSnapshot(MemberAccount account) {
        return parseTags(account.profileTagsJson);
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw BusinessException.badRequest("会员画像数据无法序列化");
        }
    }

    private String profileTagMetadata(MemberAccount account) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("accountId", account.id);
        snapshot.put("userId", account.userId);
        snapshot.put("levelCode", account.levelCode);
        snapshot.put("levelName", account.levelName);
        snapshot.put("growthValue", intOrZero(account.growthValue));
        snapshot.put("pointsBalance", intOrZero(account.pointsBalance));
        snapshot.put("totalReservations", intOrZero(account.totalReservations));
        snapshot.put("totalConsumptionAmount", amountOrZero(account.totalConsumptionAmount));
        snapshot.put("totalMemberDiscountAmount", amountOrZero(account.totalMemberDiscountAmount));
        snapshot.put("lastActiveTime", account.lastActiveTime == null ? null : account.lastActiveTime.toString());
        return toJson(snapshot);
    }

    private List<Long> keywordUserIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String text = keyword.trim();
        return userMapper.selectList(new QueryWrapper<SysUser>()
                .select("id")
                .eq("deleted", 0)
                .and(wrapper -> wrapper.like("username", text).or().like("nickname", text)))
            .stream()
            .map(user -> user.id)
            .toList();
    }

    private String normalizeLevelCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeLevelStatus(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("DISABLED".equals(normalized)) {
            return INACTIVE;
        }
        if (!ACTIVE.equals(normalized) && !INACTIVE.equals(normalized)) {
            throw BusinessException.badRequest("会员等级状态只能为 ACTIVE 或 INACTIVE");
        }
        return normalized;
    }

    private BigDecimal amountOrDefault(BigDecimal amount, BigDecimal fallback) {
        return amount == null ? amountOrZero(fallback) : amount;
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? ZERO_AMOUNT : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private int pointsFromAmount(BigDecimal amount) {
        return amountOrZero(amount).setScale(0, RoundingMode.FLOOR).intValue();
    }

    private int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String tagLabel(String tagCode) {
        return switch (tagCode) {
            case "NEW_USER" -> "新用户";
            case "ACTIVE_USER" -> "活跃用户";
            case "DORMANT_USER" -> "沉睡用户";
            case "HIGH_VALUE_USER" -> "高价值用户";
            case "PRICE_SENSITIVE_USER" -> "价格敏感用户";
            default -> tagCode;
        };
    }
}
