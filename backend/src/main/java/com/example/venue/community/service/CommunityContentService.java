package com.example.venue.community.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityReport;
import com.example.venue.community.entity.CommunityUserBlock;
import com.example.venue.community.enums.CommunityActorScope;
import com.example.venue.community.enums.CommunityContentStatus;
import com.example.venue.community.enums.CommunityReportStatus;
import com.example.venue.community.enums.CommunityReportTargetType;
import com.example.venue.community.enums.CommunitySourceType;
import com.example.venue.community.mapper.CommunityCommentMapper;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.mapper.CommunityReportMapper;
import com.example.venue.community.mapper.CommunityUserBlockMapper;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
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
public class CommunityContentService {
    private final CommunityPostMapper postMapper;
    private final CommunityCommentMapper commentMapper;
    private final CommunityReportMapper reportMapper;
    private final CommunityUserBlockMapper blockMapper;
    private final SysUserMapper userMapper;
    private final CommunityAccessService accessService;
    private final CommunityTextPolicy textPolicy;
    private final CommunityRateLimitService rateLimitService;
    private final CommunityGovernanceEventService eventService;

    public CommunityContentService(CommunityPostMapper postMapper,
                                   CommunityCommentMapper commentMapper,
                                   CommunityReportMapper reportMapper,
                                   CommunityUserBlockMapper blockMapper,
                                   SysUserMapper userMapper,
                                   CommunityAccessService accessService,
                                   CommunityTextPolicy textPolicy,
                                   CommunityRateLimitService rateLimitService,
                                   CommunityGovernanceEventService eventService) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.reportMapper = reportMapper;
        this.blockMapper = blockMapper;
        this.userMapper = userMapper;
        this.accessService = accessService;
        this.textPolicy = textPolicy;
        this.rateLimitService = rateLimitService;
        this.eventService = eventService;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.PostRow> searchPosts(CommunityRequests.PostQuery query) {
        CurrentUser user = accessService.requireCurrentUser();
        CommunityRequests.PostQuery safe = query == null ? new CommunityRequests.PostQuery() : query;
        int limit = normalizedLimit(safe.limit, 20, 50);
        String moderationStatus = moderationStatusFilter(safe.status, safe.groupId);
        if (moderationStatus != null) {
            accessService.requireGroupManager(safe.groupId, user.id());
            List<CommunityQueryRows.PostRow> rows = postMapper.listForModeration(
                safe.groupId, moderationStatus, safe.cursorId, limit + 1);
            return page(rows, limit, row -> row.id);
        }
        String titlePrefix = safe.titlePrefix == null ? null : safe.titlePrefix.trim();
        String category = categoryFilter(safe.category);
        boolean platformOnly = platformOnly(safe.visibilityScope);
        List<CommunityQueryRows.PostRow> rows = postMapper.searchVisible(
            user.id(), safe.groupId, safe.authorId, titlePrefix, category, platformOnly, safe.cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional(readOnly = true)
    public CommunityQueryRows.PostRow getPost(Long postId) {
        CurrentUser user = accessService.requireCurrentUser();
        CommunityQueryRows.PostRow detail = postMapper.findVisibleDetail(user.id(), postId);
        if (detail == null) {
            throw accessService.hiddenResource();
        }
        return detail;
    }

    @Transactional
    public CommunityPost createPost(CommunityRequests.CreatePost request, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null || request.groupId == null) {
            throw BusinessException.badRequest("帖子参数不完整");
        }
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        String title = textPolicy.required(request.title, 80, "帖子标题");
        String content = textPolicy.required(request.content, 5000, "帖子正文");
        String hash = textPolicy.fingerprint(request.groupId, title, content);
        CommunityPost existing = postMapper.findByAuthorAndIdempotency(user.id(), key);
        if (existing != null) {
            return requireSameRequest(existing.requestHash, hash, existing);
        }
        CommunityGroup group = accessService.requireVisibleGroup(request.groupId, user.id());
        accessService.requireActiveMember(group.id, user.id());
        rateLimitService.check(user.id(), CommunityRateLimitService.Action.POST);
        group = accessService.requireVisibleGroupForUpdate(request.groupId, user.id());
        accessService.requireActiveMemberForUpdate(group.id, user.id());
        LocalDateTime now = LocalDateTime.now();
        CommunityPost post = new CommunityPost();
        post.postNo = "CP-" + compactUuid();
        post.groupId = group.id;
        post.authorUserId = user.id();
        post.title = title;
        post.contentText = content;
        post.status = CommunityContentStatus.PUBLISHED.name();
        post.idempotencyKey = key;
        post.requestHash = hash;
        post.versionNo = 0L;
        post.publishedAt = now;
        post.createTime = now;
        post.updateTime = now;
        try {
            postMapper.insert(post);
        } catch (DuplicateKeyException ex) {
            CommunityPost winner = postMapper.findByAuthorAndIdempotencyForUpdate(user.id(), key);
            if (winner != null) {
                return requireSameRequest(winner.requestHash, hash, winner);
            }
            throw ex;
        }
        record("POST_CREATED", "POST", post.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            null, CommunityContentStatus.PUBLISHED.name(),
            "CREATE_POST:" + key, null, Map.of("groupId", group.id));
        return post;
    }

    @Transactional
    public CommunityPost updatePost(Long postId, CommunityRequests.UpdatePost request,
                                    String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null || request.versionNo == null) {
            throw BusinessException.badRequest("帖子更新参数不完整");
        }
        CommunityPost visible = accessService.requireVisiblePost(postId, user.id());
        if (!user.id().equals(visible.authorUserId)) {
            throw BusinessException.forbidden("只能修改本人帖子");
        }
        String title = textPolicy.required(request.title, 80, "帖子标题");
        String content = textPolicy.required(request.content, 5000, "帖子正文");
        String key = textPolicy.scopedIdempotencyKey("UPDATE_POST_" + postId, rawIdempotencyKey);
        String requestHash = textPolicy.fingerprint(postId, request.versionNo, title, content);
        CommunityGroup group = accessService.requireVisibleGroupForUpdate(visible.groupId, user.id());
        CommunityPost current = accessService.requirePublishedPostForUpdate(postId, group.id, user.id());
        if (!user.id().equals(current.authorUserId)) {
            throw BusinessException.forbidden("只能修改本人帖子");
        }
        CommunityGovernanceEventService.RecordedRequest replay = eventService.findRecordedRequest(
            CommunitySourceType.USER, "POST_UPDATED", "POST", postId, user.id(), key);
        if (replay != null && replay.recorded()) {
            if (!requestHash.equals(replay.requestHash())) {
                throw BusinessException.conflict("同一 Idempotency-Key 对应了不同帖子更新请求");
            }
            if (Objects.equals(current.versionNo, replay.resultVersion())
                && Objects.equals(current.title, title)
                && Objects.equals(current.contentText, content)) {
                return current;
            }
            throw BusinessException.conflict("该帖子更新请求已处理，当前帖子已有后续修改");
        }
        if (!Objects.equals(current.versionNo, request.versionNo)) {
            throw BusinessException.conflict("帖子已被其他请求修改，请刷新后重试");
        }
        int changed = postMapper.update(null, new UpdateWrapper<CommunityPost>()
            .eq("id", postId)
            .eq("author_user_id", user.id())
            .eq("status", CommunityContentStatus.PUBLISHED.name())
            .eq("version_no", request.versionNo)
            .set("title", title)
            .set("content_text", content)
            .set("version_no", request.versionNo + 1)
            .set("update_time", LocalDateTime.now()));
        if (changed == 0) {
            throw BusinessException.conflict("帖子已被其他请求修改，请刷新后重试");
        }
        record("POST_UPDATED", "POST", postId, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            CommunityContentStatus.PUBLISHED.name(), CommunityContentStatus.PUBLISHED.name(),
            key, null, Map.of("groupId", current.groupId, "requestHash", requestHash,
                "baseVersion", request.versionNo, "resultVersion", request.versionNo + 1));
        return postMapper.selectById(postId);
    }

    @Transactional
    public void deletePost(Long postId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        CommunityPost post = postMapper.selectByIdForUpdate(postId);
        if (post == null || !user.id().equals(post.authorUserId)) {
            throw accessService.hiddenResource();
        }
        if (CommunityContentStatus.DELETED.matches(post.status)) {
            return;
        }
        if (!List.of(CommunityContentStatus.PUBLISHED.name(), CommunityContentStatus.HIDDEN.name())
            .contains(post.status)) {
            throw BusinessException.conflict("帖子当前状态不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retainUntil = maximum(now.plusDays(30), post.retainUntil,
            reportMapper.findMaximumReasonRetention("POST", post.id));
        String fromStatus = post.status;
        post.status = CommunityContentStatus.DELETED.name();
        post.deletedBy = user.id();
        post.deletionReason = "USER_REQUEST";
        post.deletedAt = post.deletedAt == null ? now : post.deletedAt;
        post.retainUntil = retainUntil;
        post.versionNo = post.versionNo + 1;
        post.updateTime = now;
        postMapper.updateById(post);
        record("POST_DELETED", "POST", post.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            fromStatus, CommunityContentStatus.DELETED.name(),
            "DELETE_POST:" + post.id + ":" + key, "USER_REQUEST", Map.of("retainUntil", retainUntil.toString()));
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.CommentRow> comments(Long postId, Long cursorId, int requestedLimit) {
        CurrentUser user = accessService.requireCurrentUser();
        accessService.requireVisiblePost(postId, user.id());
        int limit = normalizedLimit(requestedLimit, 20, 50);
        List<CommunityQueryRows.CommentRow> rows = commentMapper.listVisible(user.id(), postId, cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityComment createComment(Long postId, CommunityRequests.CreateComment request,
                                           String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        String content = textPolicy.required(request == null ? null : request.content, 1000, "评论正文");
        String hash = textPolicy.fingerprint(postId, content);
        CommunityComment existing = commentMapper.findByAuthorAndIdempotency(user.id(), key);
        if (existing != null) {
            return requireSameRequest(existing.requestHash, hash, existing);
        }
        CommunityPost post = accessService.requireVisiblePost(postId, user.id());
        accessService.requireActiveMember(post.groupId, user.id());
        rateLimitService.check(user.id(), CommunityRateLimitService.Action.COMMENT);
        CommunityGroup group = accessService.requireVisibleGroupForUpdate(post.groupId, user.id());
        post = accessService.requirePublishedPostForUpdate(postId, group.id, user.id());
        accessService.requireActiveMemberForUpdate(group.id, user.id());
        LocalDateTime now = LocalDateTime.now();
        CommunityComment comment = new CommunityComment();
        comment.commentNo = "CC-" + compactUuid();
        comment.postId = post.id;
        comment.groupId = post.groupId;
        comment.authorUserId = user.id();
        comment.contentText = content;
        comment.status = CommunityContentStatus.PUBLISHED.name();
        comment.idempotencyKey = key;
        comment.requestHash = hash;
        comment.versionNo = 0L;
        comment.publishedAt = now;
        comment.createTime = now;
        comment.updateTime = now;
        try {
            commentMapper.insert(comment);
        } catch (DuplicateKeyException ex) {
            CommunityComment winner = commentMapper.findByAuthorAndIdempotencyForUpdate(user.id(), key);
            if (winner != null) {
                return requireSameRequest(winner.requestHash, hash, winner);
            }
            throw ex;
        }
        record("COMMENT_CREATED", "COMMENT", comment.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            null, CommunityContentStatus.PUBLISHED.name(),
            "CREATE_COMMENT:" + key, null, Map.of("groupId", post.groupId, "postId", post.id));
        return comment;
    }

    @Transactional
    public CommunityComment updateComment(Long commentId, CommunityRequests.UpdateComment request,
                                          String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null || request.versionNo == null) {
            throw BusinessException.badRequest("评论更新参数不完整");
        }
        CommunityComment visible = accessService.requireVisibleComment(commentId, user.id());
        if (!user.id().equals(visible.authorUserId)) {
            throw BusinessException.forbidden("只能修改本人评论");
        }
        String content = textPolicy.required(request.content, 1000, "评论正文");
        String key = textPolicy.scopedIdempotencyKey("UPDATE_COMMENT_" + commentId, rawIdempotencyKey);
        String requestHash = textPolicy.fingerprint(commentId, request.versionNo, content);
        CommunityGroup group = accessService.requireVisibleGroupForUpdate(visible.groupId, user.id());
        accessService.requirePublishedPostForUpdate(visible.postId, group.id, user.id());
        CommunityComment current = accessService.requirePublishedCommentForUpdate(
            commentId, visible.postId, group.id, user.id());
        if (!user.id().equals(current.authorUserId)) {
            throw BusinessException.forbidden("只能修改本人评论");
        }
        CommunityGovernanceEventService.RecordedRequest replay = eventService.findRecordedRequest(
            CommunitySourceType.USER, "COMMENT_UPDATED", "COMMENT", commentId, user.id(), key);
        if (replay != null && replay.recorded()) {
            if (!requestHash.equals(replay.requestHash())) {
                throw BusinessException.conflict("同一 Idempotency-Key 对应了不同评论更新请求");
            }
            if (Objects.equals(current.versionNo, replay.resultVersion())
                && Objects.equals(current.contentText, content)) {
                return current;
            }
            throw BusinessException.conflict("该评论更新请求已处理，当前评论已有后续修改");
        }
        if (!Objects.equals(current.versionNo, request.versionNo)) {
            throw BusinessException.conflict("评论已被其他请求修改，请刷新后重试");
        }
        int changed = commentMapper.update(null, new UpdateWrapper<CommunityComment>()
            .eq("id", commentId)
            .eq("author_user_id", user.id())
            .eq("status", CommunityContentStatus.PUBLISHED.name())
            .eq("version_no", request.versionNo)
            .set("content_text", content)
            .set("version_no", request.versionNo + 1)
            .set("update_time", LocalDateTime.now()));
        if (changed == 0) {
            throw BusinessException.conflict("评论已被其他请求修改，请刷新后重试");
        }
        record("COMMENT_UPDATED", "COMMENT", commentId, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            CommunityContentStatus.PUBLISHED.name(), CommunityContentStatus.PUBLISHED.name(),
            key, null, Map.of("groupId", current.groupId, "postId", current.postId,
                "requestHash", requestHash, "baseVersion", request.versionNo,
                "resultVersion", request.versionNo + 1));
        return commentMapper.selectById(commentId);
    }

    @Transactional
    public void deleteComment(Long commentId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        CommunityComment comment = commentMapper.selectByIdForUpdate(commentId);
        if (comment == null || !user.id().equals(comment.authorUserId)) {
            throw accessService.hiddenResource();
        }
        if (CommunityContentStatus.DELETED.matches(comment.status)) {
            return;
        }
        if (!List.of(CommunityContentStatus.PUBLISHED.name(), CommunityContentStatus.HIDDEN.name())
            .contains(comment.status)) {
            throw BusinessException.conflict("评论当前状态不能删除");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retainUntil = maximum(now.plusDays(30), comment.retainUntil,
            reportMapper.findMaximumReasonRetention("COMMENT", comment.id));
        String fromStatus = comment.status;
        comment.status = CommunityContentStatus.DELETED.name();
        comment.deletedBy = user.id();
        comment.deletionReason = "USER_REQUEST";
        comment.deletedAt = comment.deletedAt == null ? now : comment.deletedAt;
        comment.retainUntil = retainUntil;
        comment.versionNo = comment.versionNo + 1;
        comment.updateTime = now;
        commentMapper.updateById(comment);
        record("COMMENT_DELETED", "COMMENT", comment.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            fromStatus, CommunityContentStatus.DELETED.name(),
            "DELETE_COMMENT:" + comment.id + ":" + key, "USER_REQUEST",
            Map.of("retainUntil", retainUntil.toString(), "postId", comment.postId));
    }

    @Transactional
    public CommunityReport report(CommunityRequests.CreateReport request, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        if (request == null || request.targetId == null) {
            throw BusinessException.badRequest("举报参数不完整");
        }
        String key = textPolicy.idempotencyKey(rawIdempotencyKey);
        String targetType = enumName(CommunityReportTargetType.class, request.targetType, "举报对象类型");
        String reason = textPolicy.required(request.reason, 1000, "举报原因");
        String hash = textPolicy.fingerprint(targetType, request.targetId, reason);
        CommunityReport existing = reportMapper.findByReporterAndIdempotency(user.id(), key);
        if (existing != null) {
            return requireSameRequest(existing.requestHash, hash, existing);
        }
        Long targetOwner = requireReportableTarget(targetType, request.targetId, user.id());
        if (user.id().equals(targetOwner)) {
            throw BusinessException.badRequest("不能举报本人内容");
        }
        rateLimitService.check(user.id(), CommunityRateLimitService.Action.REPORT);
        LocalDateTime now = LocalDateTime.now();
        CommunityReport report = new CommunityReport();
        report.reportNo = "CR-" + compactUuid();
        report.reporterUserId = user.id();
        report.targetType = targetType;
        report.targetId = request.targetId;
        report.reasonText = reason;
        report.status = CommunityReportStatus.OPEN.name();
        report.idempotencyKey = key;
        report.requestHash = hash;
        report.minimalRetainUntil = now.plusDays(365);
        report.createTime = now;
        report.updateTime = now;
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException ex) {
            CommunityReport winner = reportMapper.findByReporterAndIdempotencyForUpdate(user.id(), key);
            if (winner != null) {
                return requireSameRequest(winner.requestHash, hash, winner);
            }
            throw ex;
        }
        record("REPORT_SUBMITTED", "REPORT", report.id, report.id, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            null, CommunityReportStatus.OPEN.name(),
            "CREATE_REPORT:" + key, "USER_REPORT", Map.of("targetType", targetType, "targetId", request.targetId));
        return report;
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityReport> myReports(Long cursorId, int requestedLimit) {
        CurrentUser user = accessService.requireCurrentUser();
        int limit = normalizedLimit(requestedLimit, 20, 50);
        QueryWrapper<CommunityReport> query = new QueryWrapper<CommunityReport>()
            .eq("reporter_user_id", user.id());
        if (cursorId != null) {
            query.lt("id", cursorId);
        }
        List<CommunityReport> rows = reportMapper.selectList(query.orderByDesc("id").last("limit " + (limit + 1)));
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public CommunityUserBlock block(Long targetUserId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        requireTargetUser(targetUserId, user.id());
        String key = textPolicy.scopedIdempotencyKey("BLOCK", rawIdempotencyKey);
        CommunityUserBlock block = blockMapper.findPairForUpdate(user.id(), targetUserId);
        if (block != null && key.equals(block.lastIdempotencyKey) && "ACTIVE".equals(block.status)) {
            return block;
        }
        if (block != null && eventService.isRecorded(
            CommunitySourceType.USER, "USER_BLOCKED", "BLOCK", block.id, user.id(), key)) {
            throw BusinessException.conflict("该 Idempotency-Key 对应的屏蔽请求已处理");
        }
        if (block != null && "ACTIVE".equals(block.status)) {
            return block;
        }
        LocalDateTime now = LocalDateTime.now();
        if (block == null) {
            block = new CommunityUserBlock();
            block.blockerUserId = user.id();
            block.blockedUserId = targetUserId;
            block.status = "ACTIVE";
            block.lastIdempotencyKey = key;
            block.createdAt = now;
            block.updateTime = now;
            try {
                blockMapper.insert(block);
            } catch (DuplicateKeyException ex) {
                CommunityUserBlock winner = blockMapper.findPairForUpdate(user.id(), targetUserId);
                if (winner != null && "ACTIVE".equals(winner.status)) {
                    return winner;
                }
                throw ex;
            }
        } else {
            block.status = "ACTIVE";
            block.lastIdempotencyKey = key;
            block.createdAt = now;
            block.removedAt = null;
            block.updateTime = now;
            blockMapper.updateById(block);
        }
        record("USER_BLOCKED", "BLOCK", block.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            "REMOVED", "ACTIVE",
            key, "USER_PROTECTION", Map.of("blockedUserId", targetUserId));
        return block;
    }

    @Transactional
    public void unblock(Long targetUserId, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String key = textPolicy.scopedIdempotencyKey("UNBLOCK", rawIdempotencyKey);
        CommunityUserBlock block = blockMapper.findPairForUpdate(user.id(), targetUserId);
        if (block == null || "REMOVED".equals(block.status)) {
            return;
        }
        block.status = "REMOVED";
        block.lastIdempotencyKey = key;
        block.removedAt = LocalDateTime.now();
        block.updateTime = block.removedAt;
        blockMapper.updateById(block);
        record("USER_UNBLOCKED", "BLOCK", block.id, null, user, CommunityActorScope.SELF, CommunitySourceType.USER,
            "ACTIVE", "REMOVED",
            key, "USER_PROTECTION", Map.of("blockedUserId", targetUserId));
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunityQueryRows.BlockRow> blockedUsers(Long cursorId, int requestedLimit) {
        CurrentUser user = accessService.requireCurrentUser();
        int limit = normalizedLimit(requestedLimit, 20, 50);
        List<CommunityQueryRows.BlockRow> rows = blockMapper.listActive(user.id(), cursorId, limit + 1);
        return page(rows, limit, row -> row.id);
    }

    @Transactional
    public void groupModerate(Long groupId, String targetType, Long targetId, boolean restore,
                              CommunityRequests.ModerationAction request, String rawIdempotencyKey) {
        CurrentUser user = accessService.requireCurrentUser();
        String normalizedType = requiredTargetContentType(targetType);
        String key = textPolicy.scopedIdempotencyKey(
            "GROUP_MODERATE_" + normalizedType + "_" + targetId + "_" + restore, rawIdempotencyKey);
        String reasonCode = textPolicy.required(request == null ? null : request.reasonCode, 64, "处置原因代码")
            .toUpperCase(Locale.ROOT);
        accessService.requireActiveGroupForUpdate(groupId);
        if ("POST".equals(normalizedType)) {
            CommunityPost post = postMapper.selectByIdForUpdate(targetId);
            if (post == null || !groupId.equals(post.groupId)) {
                throw accessService.hiddenResource();
            }
            accessService.requireGroupManagerForUpdate(groupId, user.id());
            String eventType = restore ? "POST_RESTORED" : "POST_HIDDEN";
            if (eventService.isRecorded(
                CommunitySourceType.GROUP_ADMIN, eventType, "POST", post.id, user.id(), key)) {
                return;
            }
            moderatePost(post, restore, user, CommunityActorScope.GROUP, CommunitySourceType.GROUP_ADMIN, reasonCode,
                key, null);
            return;
        }
        CommunityComment visible = commentMapper.selectById(targetId);
        if (visible == null || !groupId.equals(visible.groupId)) {
            throw accessService.hiddenResource();
        }
        CommunityPost parentPost = postMapper.selectByIdForUpdate(visible.postId);
        if (parentPost == null || !groupId.equals(parentPost.groupId)
            || !CommunityContentStatus.PUBLISHED.matches(parentPost.status)) {
            throw BusinessException.conflict("父帖子当前状态不能处置评论");
        }
        accessService.requireGroupManagerForUpdate(groupId, user.id());
        CommunityComment comment = commentMapper.selectByIdForUpdate(targetId);
        if (comment == null || !groupId.equals(comment.groupId)
            || !parentPost.id.equals(comment.postId)) {
            throw accessService.hiddenResource();
        }
        String eventType = restore ? "COMMENT_RESTORED" : "COMMENT_HIDDEN";
        if (eventService.isRecorded(
            CommunitySourceType.GROUP_ADMIN, eventType, "COMMENT", comment.id, user.id(), key)) {
            return;
        }
        moderateComment(comment, restore, user, CommunityActorScope.GROUP, CommunitySourceType.GROUP_ADMIN, reasonCode,
            key, null);
    }

    void moderatePost(CommunityPost post, boolean restore, CurrentUser actor, CommunityActorScope actorScope,
                      CommunitySourceType sourceType, String reasonCode, String idempotencyKey, Long reportId) {
        String fromStatus = post.status;
        LocalDateTime now = LocalDateTime.now();
        if (restore) {
            if (!CommunityContentStatus.HIDDEN.matches(post.status)) {
                throw BusinessException.conflict("帖子当前状态不能恢复");
            }
            if (CommunitySourceType.GROUP_ADMIN == sourceType && !"GROUP".equals(post.hiddenScope)) {
                throw BusinessException.forbidden("组管理员不能恢复平台处置的帖子");
            }
            post.status = CommunityContentStatus.PUBLISHED.name();
            post.hiddenBy = null;
            post.hiddenScope = null;
            post.hiddenReasonCode = null;
            post.hiddenAt = null;
            post.deletedAt = null;
            post.retainUntil = null;
        } else {
            boolean platformUpgrade = CommunitySourceType.CONTENT_OPERATOR == sourceType
                && CommunityContentStatus.HIDDEN.matches(post.status) && "GROUP".equals(post.hiddenScope);
            if (!CommunityContentStatus.PUBLISHED.matches(post.status) && !platformUpgrade) {
                throw BusinessException.conflict("帖子当前状态不能隐藏");
            }
            post.status = CommunityContentStatus.HIDDEN.name();
            post.hiddenBy = actor.id();
            post.hiddenScope = CommunityActorScope.CONTENT == actorScope ? "PLATFORM" : "GROUP";
            post.hiddenReasonCode = reasonCode;
            post.hiddenAt = post.hiddenAt == null ? now : post.hiddenAt;
            post.deletedAt = post.deletedAt == null ? now : post.deletedAt;
            post.retainUntil = maximum(now.plusDays(180), post.retainUntil);
        }
        post.versionNo = post.versionNo + 1;
        post.updateTime = now;
        postMapper.updateById(post);
        record(restore ? "POST_RESTORED" : "POST_HIDDEN", "POST", post.id, reportId, actor,
            actorScope, sourceType, fromStatus, post.status, idempotencyKey, reasonCode,
            Map.of("groupId", post.groupId, "retentionDays", restore ? 0 : 180));
    }

    void moderateComment(CommunityComment comment, boolean restore, CurrentUser actor, CommunityActorScope actorScope,
                         CommunitySourceType sourceType, String reasonCode, String idempotencyKey, Long reportId) {
        String fromStatus = comment.status;
        LocalDateTime now = LocalDateTime.now();
        if (restore) {
            if (!CommunityContentStatus.HIDDEN.matches(comment.status)) {
                throw BusinessException.conflict("评论当前状态不能恢复");
            }
            if (CommunitySourceType.GROUP_ADMIN == sourceType && !"GROUP".equals(comment.hiddenScope)) {
                throw BusinessException.forbidden("组管理员不能恢复平台处置的评论");
            }
            comment.status = CommunityContentStatus.PUBLISHED.name();
            comment.hiddenBy = null;
            comment.hiddenScope = null;
            comment.hiddenReasonCode = null;
            comment.hiddenAt = null;
            comment.deletedAt = null;
            comment.retainUntil = null;
        } else {
            boolean platformUpgrade = CommunitySourceType.CONTENT_OPERATOR == sourceType
                && CommunityContentStatus.HIDDEN.matches(comment.status) && "GROUP".equals(comment.hiddenScope);
            if (!CommunityContentStatus.PUBLISHED.matches(comment.status) && !platformUpgrade) {
                throw BusinessException.conflict("评论当前状态不能隐藏");
            }
            comment.status = CommunityContentStatus.HIDDEN.name();
            comment.hiddenBy = actor.id();
            comment.hiddenScope = CommunityActorScope.CONTENT == actorScope ? "PLATFORM" : "GROUP";
            comment.hiddenReasonCode = reasonCode;
            comment.hiddenAt = comment.hiddenAt == null ? now : comment.hiddenAt;
            comment.deletedAt = comment.deletedAt == null ? now : comment.deletedAt;
            comment.retainUntil = maximum(now.plusDays(180), comment.retainUntil);
        }
        comment.versionNo = comment.versionNo + 1;
        comment.updateTime = now;
        commentMapper.updateById(comment);
        record(restore ? "COMMENT_RESTORED" : "COMMENT_HIDDEN", "COMMENT", comment.id, reportId, actor,
            actorScope, sourceType, fromStatus, comment.status, idempotencyKey, reasonCode,
            Map.of("groupId", comment.groupId, "postId", comment.postId,
                "retentionDays", restore ? 0 : 180));
    }

    private Long requireReportableTarget(String targetType, Long targetId, Long userId) {
        return switch (targetType) {
            case "GROUP" -> accessService.requireVisibleGroup(targetId, userId).ownerUserId;
            case "POST" -> accessService.requireVisiblePost(targetId, userId).authorUserId;
            case "COMMENT" -> accessService.requireVisibleComment(targetId, userId).authorUserId;
            default -> throw BusinessException.badRequest("举报对象类型不合法");
        };
    }

    private void requireTargetUser(Long targetUserId, Long currentUserId) {
        if (targetUserId == null || targetUserId.equals(currentUserId)) {
            throw BusinessException.badRequest("屏蔽对象不合法");
        }
        SysUser target = userMapper.selectById(targetUserId);
        if (target == null || target.deleted != null && target.deleted == 1 || !"ACTIVE".equals(target.status)) {
            throw accessService.hiddenResource();
        }
    }

    private String requiredTargetContentType(String targetType) {
        String normalized = targetType == null ? null : targetType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("POST", "COMMENT").contains(normalized)) {
            throw BusinessException.badRequest("内容类型不合法");
        }
        return normalized;
    }

    /** 帖子查询的小组类别过滤：OTHER 专指未分类（category_code 为空）的存量小组，与小组查询同口径。 */
    private String categoryFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String category = raw.trim();
        return "OTHER".equalsIgnoreCase(category) ? "OTHER" : category;
    }

    /** visibilityScope 唯一合法值 PLATFORM_VISIBLE：阅读流只取广场可见小组的帖子。 */
    private boolean platformOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (!"PLATFORM_VISIBLE".equals(raw.trim().toUpperCase(Locale.ROOT))) {
            throw BusinessException.badRequest("可见范围筛选不合法");
        }
        return true;
    }

    /**
     * 组内治理状态维度（G8）：null/PUBLISHED 返回 null 走常规可见性读取；
     * HIDDEN/ALL 必须配合 groupId 且由服务层 requireGroupManager 收口权限。
     */
    private String moderationStatusFilter(String raw, Long groupId) {
        if (raw == null || raw.isBlank() || "PUBLISHED".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        if (groupId == null) {
            throw BusinessException.badRequest("内容状态筛选需配合 groupId 使用");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("HIDDEN", "ALL").contains(normalized)) {
            throw BusinessException.badRequest("内容状态筛选不合法");
        }
        return normalized;
    }

    private void record(String eventType, String subjectType, Long subjectId, Long reportId,
                        CurrentUser actor, CommunityActorScope actorScope, CommunitySourceType sourceType,
                        String fromStatus, String toStatus, String idempotencyKey,
                        String reasonCode, Map<String, Object> metadata) {
        CommunityGovernanceEventService.EventCommand command = new CommunityGovernanceEventService.EventCommand();
        command.eventType = eventType;
        command.subjectType = subjectType;
        command.subjectId = subjectId;
        command.reportId = reportId;
        command.actorUserId = actor.id();
        command.actorScope = actorScope;
        command.sourceType = sourceType;
        command.fromStatus = fromStatus;
        command.toStatus = toStatus;
        command.reasonCode = reasonCode;
        command.reasonSummary = reasonCode;
        command.resultStatus = "SUCCEEDED";
        command.idempotencyKey = idempotencyKey;
        command.metadata = metadata;
        eventService.record(command);
    }

    private LocalDateTime maximum(LocalDateTime first, LocalDateTime... others) {
        LocalDateTime result = first;
        for (LocalDateTime other : others) {
            if (other != null && other.isAfter(result)) {
                result = other;
            }
        }
        return result;
    }

    private <T> T requireSameRequest(String existingHash, String requestHash, T result) {
        if (!requestHash.equals(existingHash)) {
            throw BusinessException.conflict("同一 Idempotency-Key 对应了不同请求");
        }
        return result;
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

    @FunctionalInterface
    private interface IdExtractor<T> {
        Long id(T value);
    }
}
