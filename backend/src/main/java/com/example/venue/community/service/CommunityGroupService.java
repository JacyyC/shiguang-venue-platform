package com.example.venue.community.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunityGroupStatus;
import com.example.venue.community.enums.CommunityGroupVisibility;
import com.example.venue.community.enums.CommunityJoinPolicy;
import com.example.venue.community.enums.CommunityMemberStatus;
import com.example.venue.community.enums.CommunityMemberRole;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityGroupMapper;
import com.example.venue.community.mapper.CommunityGroupMemberMapper;
import com.example.venue.system.mapper.SysDictDataMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CommunityGroupService {
    static final String GROUP_CATEGORY_DICT_TYPE = "community_group_category";
    /** GroupQuery.category 的保留值：匹配未分类（category_code 为空）的存量小组。 */
    static final String UNCATEGORIZED = "OTHER";

    private final CommunityGroupMapper groupMapper;
    private final CommunityGroupMemberMapper memberMapper;
    private final SysDictDataMapper dictDataMapper;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final CommunityRateLimitService rateLimitService;
    private final CommunityGovernanceEventService eventService;

    public CommunityGroupService(CommunityGroupMapper groupMapper,
                                 CommunityGroupMemberMapper memberMapper,
                                 SysDictDataMapper dictDataMapper,
                                 CommunityAccessService accessService,
                                 CommunityTextPolicy textPolicy,
                                 CommunityRateLimitService rateLimitService,
                                 CommunityGovernanceEventService eventService) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.dictDataMapper = dictDataMapper;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.rateLimitService = rateLimitService;
        this.eventService = eventService;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.GroupRow> search(CommunityRequests.GroupQuery query) {
        CurrentUser user = accessService.requireCurrentUser();
        CommunityRequests.GroupQuery safe = query == null ? new CommunityRequests.GroupQuery() : query;
        int limit = normalizedLimit(safe.limit, 20, 50);
        String namePrefix = safe.namePrefix == null ? null : safe.namePrefix.trim();
        String categoryCode = categoryFilter(safe.category);
        String memberStatus = memberStatusFilter(safe.memberStatus, Boolean.TRUE.equals(safe.mine));
        List<CommunityQueryRows.GroupRow> rows = Boolean.TRUE.equals(safe.mine)
            ? groupMapper.searchMine(user.id(), namePrefix, categoryCode, memberStatus, safe.cursorId, limit + 1)
            : groupMapper.searchVisible(user.id(), namePrefix, categoryCode, safe.cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional(readOnly = true)
    public CommunityQueryRows.GroupRow get(Long groupId) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireVisibleGroup(groupId, user.id());
        CommunityQueryRows.GroupRow detail = groupMapper.findDetail(groupId, user.id());
        if (detail == null) {
            throw BusinessException.notFound("小组不存在");
        }
        return detail;
    }

    @Transactional
    public CommunityGroup create(CommunityRequests.CreateGroup request, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null) {
            throw BusinessException.badRequest("小组参数不能为空");
        }
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        String name = textPolicy.required(request.name, 40, "小组名称");
        String description = textPolicy.optional(request.description, 300, "小组简介");
        String visibility = enumName(CommunityGroupVisibility.class, request.visibility, "小组可见性");
        String joinPolicy = enumName(CommunityJoinPolicy.class, request.joinPolicy, "加入方式");
        String category = requiredCategory(request.category);
        String hash = textPolicy.fingerprint(name, description, visibility, joinPolicy, category);

        CommunityGroup existing = groupMapper.findByOwnerAndIdempotency(user.id(), key);
        if (existing != null) {
            return requireSameRequest(existing.requestHash, hash, existing);
        }

        LocalDateTime now = LocalDateTime.now();
        CommunityGroup group = new CommunityGroup();
        group.groupNo = "CG-" + compactUuid();
        group.name = name;
        group.description = description;
        group.ownerUserId = user.id();
        group.visibility = visibility;
        group.joinPolicy = joinPolicy;
        group.categoryCode = category;
        group.status = CommunityGroupStatus.ACTIVE.name();
        group.idempotencyKey = key;
        group.requestHash = hash;
        group.versionNo = 0L;
        group.createTime = now;
        group.updateTime = now;
        try {
            groupMapper.insert(group);
        } catch (DuplicateKeyException ex) {
            CommunityGroup winner = groupMapper.findByOwnerAndIdempotencyForUpdate(user.id(), key);
            if (winner != null) {
                return requireSameRequest(winner.requestHash, hash, winner);
            }
            throw ex;
        }

        CommunityGroupMember owner = new CommunityGroupMember();
        owner.groupId = group.id;
        owner.userId = user.id();
        owner.memberRole = "OWNER";
        owner.status = CommunityMemberStatus.ACTIVE.name();
        owner.lastIdempotencyKey = textPolicy.scopedIdempotencyKey("CREATE_GROUP", key);
        owner.lastRequestHash = hash;
        owner.joinedAt = now;
        owner.createTime = now;
        owner.updateTime = now;
        memberMapper.insert(owner);
        record("GROUP_CREATED", "GROUP", group.id, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            null, CommunityGroupStatus.ACTIVE.name(),
            "CREATE_GROUP:" + key,
            Map.of("visibility", visibility, "joinPolicy", joinPolicy, "category", category));
        return group;
    }

    @Transactional
    public CommunityGroup update(Long groupId, CommunityRequests.UpdateGroup request,
                                 String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null || request.versionNo == null) {
            throw BusinessException.badRequest("小组更新参数不完整");
        }
        String name = textPolicy.required(request.name, 40, "小组名称");
        String description = textPolicy.optional(request.description, 300, "小组简介");
        String visibility = enumName(CommunityGroupVisibility.class, request.visibility, "小组可见性");
        String joinPolicy = enumName(CommunityJoinPolicy.class, request.joinPolicy, "加入方式");
        String category = requiredCategory(request.category);
        String key = textPolicy.scopedIdempotencyKey("UPDATE_GROUP_" + groupId, rawIdempotencyKey);
        String requestHash = textPolicy.fingerprint(
            groupId, request.versionNo, name, description, visibility, joinPolicy, category);
        CommunityGroup current = groupMapper.selectByIdForUpdate(groupId);
        if (current == null || !CommunityGroupStatus.ACTIVE.matches(current.status)) {
            throw accessService.hiddenResource();
        }
        accessService.requireGroupManagerForUpdate(groupId, user.id());
        CommunityGovernanceEventService.RecordedRequest replay = eventService.findRecordedRequest(
            CommunitySourceType.GROUP_ADMIN, "GROUP_UPDATED", "GROUP", groupId, user.id(), key);
        if (replay != null && replay.recorded()) {
            if (!requestHash.equals(replay.requestHash())) {
                throw BusinessException.conflict("同一 Idempotency-Key 对应了不同小组更新请求");
            }
            if (Objects.equals(current.versionNo, replay.resultVersion())
                && Objects.equals(current.name, name)
                && Objects.equals(current.description, description)
                && Objects.equals(current.visibility, visibility)
                && Objects.equals(current.joinPolicy, joinPolicy)
                && Objects.equals(current.categoryCode, category)) {
                return current;
            }
            throw BusinessException.conflict("该小组更新请求已处理，当前小组已有后续修改");
        }
        if (!Objects.equals(current.versionNo, request.versionNo)) {
            throw BusinessException.conflict("小组已被其他请求修改，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = groupMapper.update(null, new UpdateWrapper<CommunityGroup>()
            .eq("id", groupId)
            .eq("status", CommunityGroupStatus.ACTIVE.name())
            .eq("version_no", request.versionNo)
            .set("name", name)
            .set("description", description)
            .set("visibility", visibility)
            .set("join_policy", joinPolicy)
            .set("category_code", category)
            .set("version_no", request.versionNo + 1)
            .set("update_time", now));
        if (changed == 0) {
            throw BusinessException.conflict("小组已被其他请求修改，请刷新后重试");
        }
        record("GROUP_UPDATED", "GROUP", groupId, user, managerScope(groupId, user.id()), CommunitySourceType.GROUP_ADMIN,
            current.status, current.status, key,
            Map.of("visibility", visibility, "joinPolicy", joinPolicy, "category", category,
                "requestHash", requestHash, "baseVersion", request.versionNo,
                "resultVersion", request.versionNo + 1));
        return groupMapper.selectById(groupId);
    }

    @Transactional
    public CommunityGroupMember join(Long groupId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        CommunityGroup group = accessService.requireVisibleGroupForUpdate(groupId, user.id());
        String requestHash = textPolicy.fingerprint(groupId, user.id(), "JOIN");
        CommunityGroupMember existing = memberMapper.findByGroupAndUserForUpdate(groupId, user.id());
        if (existing != null && key.equals(existing.lastIdempotencyKey)) {
            requireSameRequest(existing.lastRequestHash, requestHash, existing);
            return existing;
        }
        String eventKey = "JOIN_GROUP:" + groupId + ":" + key;
        if (existing != null && eventService.isRecorded(
            CommunitySourceType.USER, "MEMBERSHIP_REQUESTED", "MEMBER", existing.id, user.id(), eventKey)) {
            throw BusinessException.conflict("该 Idempotency-Key 对应的入组请求已处理");
        }
        if (existing != null && CommunityMemberStatus.ACTIVE.matches(existing.status)) {
            return existing;
        }
        rateLimitService.check(user.id(), CommunityRateLimitService.Action.JOIN);
        LocalDateTime now = LocalDateTime.now();
        String targetStatus = CommunityJoinPolicy.DIRECT.name().equals(group.joinPolicy)
            ? CommunityMemberStatus.ACTIVE.name() : CommunityMemberStatus.PENDING.name();
        CommunityGroupMember member = existing == null ? new CommunityGroupMember() : existing;
        member.groupId = groupId;
        member.userId = user.id();
        member.memberRole = "MEMBER";
        member.status = targetStatus;
        member.lastIdempotencyKey = key;
        member.lastRequestHash = requestHash;
        member.reviewedBy = null;
        member.reviewedAt = null;
        member.joinedAt = CommunityMemberStatus.ACTIVE.matches(targetStatus) ? now : null;
        member.leftAt = null;
        member.updateTime = now;
        if (existing == null) {
            member.createTime = now;
            try {
                memberMapper.insert(member);
            } catch (DuplicateKeyException ex) {
                CommunityGroupMember winner = memberMapper.findByGroupAndUserForUpdate(groupId, user.id());
                if (winner != null && key.equals(winner.lastIdempotencyKey)) {
                    return requireSameRequest(winner.lastRequestHash, requestHash, winner);
                }
                throw ex;
            }
        } else {
            memberMapper.updateById(member);
        }
        record("MEMBERSHIP_REQUESTED", "MEMBER", member.id, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            null, targetStatus,
            eventKey, Map.of("groupId", groupId));
        return member;
    }

    @Transactional
    public CommunityGroupMember decideMembership(Long groupId, Long memberId,
                                                   CommunityRequests.MembershipDecision request,
                                                   String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireActiveGroupForUpdate(groupId);
        accessService.requireGroupManagerForUpdate(groupId, user.id());
        String key = textPolicy.scopedIdempotencyKey("MEMBERSHIP_DECISION", rawIdempotencyKey);
        String decision = requiredCode(request == null ? null : request.decision,
            List.of("APPROVE", "REJECT"), "成员审核结果");
        String reason = textPolicy.optional(request.reason, 255, "审核原因");
        String hash = textPolicy.fingerprint(groupId, memberId, decision, reason);
        CommunityGroupMember target = memberMapper.selectById(memberId);
        if (target == null || !groupId.equals(target.groupId)) {
            throw accessService.hiddenResource();
        }
        target = memberMapper.findByGroupAndUserForUpdate(groupId, target.userId);
        if (key.equals(target.lastIdempotencyKey)) {
            return requireSameRequest(target.lastRequestHash, hash, target);
        }
        if (!CommunityMemberStatus.PENDING.matches(target.status)) {
            throw BusinessException.conflict("该入组申请已处理");
        }
        accessService.requireNoBlock(user.id(), target.userId);
        LocalDateTime now = LocalDateTime.now();
        String fromStatus = target.status;
        target.status = "APPROVE".equals(decision)
            ? CommunityMemberStatus.ACTIVE.name() : CommunityMemberStatus.REJECTED.name();
        target.lastIdempotencyKey = key;
        target.lastRequestHash = hash;
        target.reviewedBy = user.id();
        target.reviewedAt = now;
        target.joinedAt = CommunityMemberStatus.ACTIVE.matches(target.status) ? now : null;
        target.leftAt = null;
        target.updateTime = now;
        memberMapper.updateById(target);
        record("MEMBERSHIP_DECIDED", "MEMBER", target.id, user, managerScope(groupId, user.id()),
            CommunitySourceType.GROUP_ADMIN, fromStatus, target.status, key,
            Map.of("groupId", groupId, "decision", decision, "reasonProvided", reason != null));
        return target;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.MemberRow> members(Long groupId, String status,
                                                            Long cursorId, int requestedLimit) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireVisibleGroup(groupId, user.id());
        accessService.requireActiveMember(groupId, user.id());
        String normalizedStatus = status == null || status.isBlank()
            ? CommunityMemberStatus.ACTIVE.name() : status.trim().toUpperCase(Locale.ROOT);
        if (!CommunityMemberStatus.ACTIVE.matches(normalizedStatus)) {
            accessService.requireGroupManager(groupId, user.id());
        }
        if (!List.of(CommunityMemberStatus.PENDING.name(), CommunityMemberStatus.ACTIVE.name(),
            CommunityMemberStatus.REJECTED.name(), CommunityMemberStatus.LEFT.name(),
            CommunityMemberStatus.REMOVED.name()).contains(normalizedStatus)) {
            throw BusinessException.badRequest("成员状态不合法");
        }
        int limit = normalizedLimit(requestedLimit, 20, 50);
        List<CommunityQueryRows.MemberRow> rows = memberMapper.listByGroup(
            user.id(), groupId, normalizedStatus, cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityGroupMember changeRole(Long groupId, Long memberId,
                                           CommunityRequests.ChangeMemberRole request,
                                           String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireActiveGroupForUpdate(groupId);
        CommunityGroupMember actor = accessService.requireGroupManagerForUpdate(groupId, user.id());
        if (!"OWNER".equals(actor.memberRole)) {
            throw BusinessException.forbidden("仅组主可调整组管理员");
        }
        String key = textPolicy.scopedIdempotencyKey("CHANGE_MEMBER_ROLE", rawIdempotencyKey);
        String role = enumName(CommunityMemberRole.class, request == null ? null : request.role, "成员角色");
        if ("OWNER".equals(role)) {
            throw BusinessException.badRequest("不能通过该接口转移组主身份");
        }
        String hash = textPolicy.fingerprint(groupId, memberId, role);
        CommunityGroupMember target = memberMapper.selectById(memberId);
        if (target == null || !groupId.equals(target.groupId)) {
            throw accessService.hiddenResource();
        }
        target = memberMapper.findByGroupAndUserForUpdate(groupId, target.userId);
        if (target == null || !CommunityMemberStatus.ACTIVE.matches(target.status) || "OWNER".equals(target.memberRole)) {
            throw accessService.hiddenResource();
        }
        if (key.equals(target.lastIdempotencyKey)) {
            requireSameRequest(target.lastRequestHash, hash, target);
            if (!role.equals(target.memberRole)) {
                throw BusinessException.conflict("成员角色状态与幂等请求不一致");
            }
            return target;
        }
        if (eventService.isRecorded(CommunitySourceType.GROUP_ADMIN, "MEMBER_ROLE_CHANGED", "MEMBER",
            target.id, user.id(), key)) {
            throw BusinessException.conflict("该 Idempotency-Key 对应的角色变更已处理");
        }
        if (role.equals(target.memberRole)) {
            throw BusinessException.conflict("成员已是该角色");
        }
        String fromRole = target.memberRole;
        target.memberRole = role;
        target.lastIdempotencyKey = key;
        target.lastRequestHash = hash;
        target.updateTime = LocalDateTime.now();
        memberMapper.updateById(target);
        record("MEMBER_ROLE_CHANGED", "MEMBER", target.id, user, CommunityActorScope.GROUP,
            CommunitySourceType.GROUP_ADMIN,
            fromRole, role, key,
            Map.of("groupId", groupId));
        return target;
    }

    @Transactional
    public void removeMember(Long groupId, Long memberId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireActiveGroupForUpdate(groupId);
        CommunityGroupMember actor = accessService.requireGroupManagerForUpdate(groupId, user.id());
        String key = textPolicy.scopedIdempotencyKey("REMOVE_MEMBER", rawIdempotencyKey);
        CommunityGroupMember target = memberMapper.selectById(memberId);
        if (target == null || !groupId.equals(target.groupId)) {
            throw accessService.hiddenResource();
        }
        target = memberMapper.findByGroupAndUserForUpdate(groupId, target.userId);
        if (key.equals(target.lastIdempotencyKey) && CommunityMemberStatus.REMOVED.matches(target.status)) {
            return;
        }
        if (eventService.isRecorded(
            CommunitySourceType.GROUP_ADMIN, "MEMBER_REMOVED", "MEMBER", target.id, user.id(), key)) {
            throw BusinessException.conflict("该 Idempotency-Key 对应的成员移除已处理");
        }
        if (!CommunityMemberStatus.ACTIVE.matches(target.status) || "OWNER".equals(target.memberRole)) {
            throw BusinessException.conflict("该成员不能被移除");
        }
        if ("ADMIN".equals(target.memberRole) && !"OWNER".equals(actor.memberRole)) {
            throw BusinessException.forbidden("仅组主可移除组管理员");
        }
        String fromStatus = target.status;
        target.status = CommunityMemberStatus.REMOVED.name();
        target.lastIdempotencyKey = key;
        target.lastRequestHash = textPolicy.fingerprint(groupId, memberId, "REMOVE");
        target.leftAt = LocalDateTime.now();
        target.updateTime = target.leftAt;
        memberMapper.updateById(target);
        record("MEMBER_REMOVED", "MEMBER", target.id, user, managerScope(groupId, user.id()),
            CommunitySourceType.GROUP_ADMIN, fromStatus, target.status, key, Map.of("groupId", groupId));
    }

    @Transactional
    public void leave(Long groupId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.scopedIdempotencyKey("LEAVE_GROUP", rawIdempotencyKey);
        accessService.requireActiveGroupForUpdate(groupId);
        CommunityGroupMember member = memberMapper.findByGroupAndUserForUpdate(groupId, user.id());
        if (member != null && key.equals(member.lastIdempotencyKey)
            && CommunityMemberStatus.LEFT.matches(member.status)) {
            return;
        }
        if (member != null && eventService.isRecorded(
            CommunitySourceType.USER, "MEMBER_LEFT", "MEMBER", member.id, user.id(), key)) {
            throw BusinessException.conflict("该 Idempotency-Key 对应的退出请求已处理");
        }
        if (member == null || !CommunityMemberStatus.ACTIVE.matches(member.status)) {
            throw BusinessException.conflict("当前不是有效小组成员");
        }
        if ("OWNER".equals(member.memberRole)) {
            throw BusinessException.badRequest("组主不能直接退出小组");
        }
        member.status = CommunityMemberStatus.LEFT.name();
        member.lastIdempotencyKey = key;
        member.lastRequestHash = textPolicy.fingerprint(groupId, user.id(), "LEAVE");
        member.leftAt = LocalDateTime.now();
        member.updateTime = member.leftAt;
        memberMapper.updateById(member);
        record("MEMBER_LEFT", "MEMBER", member.id, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            CommunityMemberStatus.ACTIVE.name(), CommunityMemberStatus.LEFT.name(),
            key, Map.of("groupId", groupId));
    }

    @Transactional
    public void delete(Long groupId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        CommunityGroup group = groupMapper.selectByIdForUpdate(groupId);
        if (group == null || !user.id().equals(group.ownerUserId)) {
            throw accessService.hiddenResource();
        }
        String eventKey = "DELETE_GROUP:" + groupId + ":" + key;
        if (CommunityGroupStatus.DELETED.matches(group.status)) {
            if (eventService.isRecorded(
                CommunitySourceType.GROUP_ADMIN, "GROUP_DELETED", "GROUP", group.id, user.id(), eventKey)) {
                return;
            }
            throw BusinessException.conflict("小组已由其他删除请求处理");
        }
        if (!CommunityGroupStatus.ACTIVE.matches(group.status)) {
            throw BusinessException.conflict("小组当前状态不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retainUntil = maximum(
            now.plusDays(30), group.retainUntil, groupMapper.findMaximumReasonRetention(group.id));
        group.status = CommunityGroupStatus.DELETED.name();
        group.deletedAt = group.deletedAt == null ? now : group.deletedAt;
        group.retainUntil = retainUntil;
        group.versionNo = group.versionNo + 1;
        group.updateTime = now;
        groupMapper.updateById(group);
        record("GROUP_DELETED", "GROUP", group.id, user, CommunityActorScope.GROUP, CommunitySourceType.GROUP_ADMIN,
            CommunityGroupStatus.ACTIVE.name(), CommunityGroupStatus.DELETED.name(),
            eventKey, Map.of("retainUntil", retainUntil.toString()));
    }

    private String requiredCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("小组类别不能为空");
        }
        String category = raw.trim();
        boolean known = dictDataMapper.findByType(GROUP_CATEGORY_DICT_TYPE).stream()
            .anyMatch(item -> item.value != null && category.equals(item.value));
        if (!known) {
            throw BusinessException.badRequest("小组类别不合法");
        }
        return category;
    }

    private String categoryFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String category = raw.trim();
        return UNCATEGORIZED.equalsIgnoreCase(category) ? UNCATEGORIZED : category;
    }

    /**
     * mine 查询的成员状态维度：默认 ACTIVE；PENDING 仅等待审核；ANY 为「我的小组」语义（ACTIVE+PENDING）。
     * 其余成员状态（REJECTED/LEFT/REMOVED）不对用户侧开放枚举；memberStatus 必须配合 mine=true。
     */
    private String memberStatusFilter(String raw, boolean mine) {
        if (raw == null || raw.isBlank()) {
            return "ACTIVE";
        }
        if (!mine) {
            throw BusinessException.badRequest("memberStatus 需配合 mine=true 使用");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "PENDING", "ANY").contains(normalized)) {
            throw BusinessException.badRequest("成员状态筛选不合法");
        }
        return normalized;
    }

    private CommunityActorScope managerScope(Long groupId, Long userId) {
        CommunityGroupMember member = memberMapper.findByGroupAndUser(groupId, userId);
        return CommunityActorScope.GROUP;
    }

    private void record(String eventType, String subjectType, Long subjectId, CurrentUser actor,
                        CommunityActorScope actorScope, CommunitySourceType sourceType, String fromStatus, String toStatus,
                        String idempotencyKey, Map<String, Object> metadata) {
        CommunityGovernanceEventService.EventCommand command = new CommunityGovernanceEventService.EventCommand();
        command.eventType = eventType;
        command.subjectType = subjectType;
        command.subjectId = subjectId;
        command.actorUserId = actor.id();
        command.actorScope = actorScope;
        command.sourceType = sourceType;
        command.fromStatus = fromStatus;
        command.toStatus = toStatus;
        command.resultStatus = "SUCCEEDED";
        command.idempotencyKey = idempotencyKey;
        command.metadata = metadata;
        eventService.record(command);
    }

    private <T> T requireSameRequest(String existingHash, String requestHash, T result) {
        if (!requestHash.equals(existingHash)) {
            throw BusinessException.conflict("同一 Idempotency-Key 对应了不同请求");
        }
        return result;
    }

    private String requiredCode(String raw, List<String> values, String field) {
        String value = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        if (value == null || !values.contains(value)) {
            throw BusinessException.badRequest(field + "不合法");
        }
        return value;
    }

    private <E extends Enum<E>> String enumName(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest(field + "不能为空");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(field + "不合法");
        }
    }

    private int normalizedLimit(int requested, int defaultValue, int max) {
        return requested <= 0 ? defaultValue : Math.min(requested, max);
    }

    private <T> CursorPage<T> page(List<T> source, int limit, IdExtractor<T> extractor) {
        boolean hasMore = source.size() > limit;
        List<T> records = hasMore ? List.copyOf(source.subList(0, limit)) : List.copyOf(source);
        Long nextCursor = hasMore && !records.isEmpty() ? extractor.id(records.get(records.size() - 1)) : null;
        return CursorPage.of(records, nextCursor, hasMore);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private LocalDateTime maximum(LocalDateTime... values) {
        LocalDateTime result = null;
        for (LocalDateTime value : values) {
            if (value != null && (result == null || value.isAfter(result))) {
                result = value;
            }
        }
        return result;
    }

    @FunctionalInterface
    private interface IdExtractor<T> {
        Long id(T value);
    }
}
