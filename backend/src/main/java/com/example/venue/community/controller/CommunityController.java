package com.example.venue.community.controller;

import com.example.venue.common.Result;
import com.example.venue.community.dto.CommunityEchoItem;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CommunityResponses;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.service.CommunityContentService;
import com.example.venue.community.service.CommunityEchoService;
import com.example.venue.community.service.CommunityGroupService;
import com.example.venue.security.SecurityContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;

@Validated
@RestController
@RequestMapping("/api/community")
@PreAuthorize("hasAuthority('community:access')")
public class CommunityController {
    private final CommunityGroupService groupService;
    private final CommunityContentService contentService;
    private final CommunityEchoService echoService;

    public CommunityController(CommunityGroupService groupService, CommunityContentService contentService,
                               CommunityEchoService echoService) {
        this.groupService = groupService;
        this.contentService = contentService;
        this.echoService = echoService;
    }

    @GetMapping("/echo")
    public Result<List<CommunityEchoItem>> echo(@RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit) {
        return Result.ok(echoService.echo(SecurityContext.currentUserId(), limit));
    }

    @GetMapping("/groups")
    public Result<CursorPage<CommunityResponses.Group>> groups(@Valid CommunityRequests.GroupQuery query) {
        return Result.ok(mapPage(groupService.search(query), CommunityResponses::group));
    }

    @GetMapping("/groups/{groupId}")
    public Result<CommunityResponses.Group> group(@PathVariable Long groupId) {
        return Result.ok(CommunityResponses.group(groupService.get(groupId)));
    }

    @PostMapping("/groups")
    public Result<CommunityResponses.Group> createGroup(
        @Valid @RequestBody CommunityRequests.CreateGroup request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.group(groupService.create(request, idempotencyKey)));
    }

    @PutMapping("/groups/{groupId}")
    public Result<CommunityResponses.Group> updateGroup(@PathVariable Long groupId,
                                                         @Valid @RequestBody CommunityRequests.UpdateGroup request,
                                                         @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.group(groupService.update(groupId, request, idempotencyKey)));
    }

    @DeleteMapping("/groups/{groupId}")
    public Result<Void> deleteGroup(@PathVariable Long groupId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        groupService.delete(groupId, idempotencyKey);
        return Result.ok();
    }

    @PostMapping("/groups/{groupId}/join")
    public Result<CommunityResponses.Member> joinGroup(@PathVariable Long groupId,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.member(groupService.join(groupId, idempotencyKey)));
    }

    @PostMapping("/groups/{groupId}/leave")
    public Result<Void> leaveGroup(@PathVariable Long groupId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        groupService.leave(groupId, idempotencyKey);
        return Result.ok();
    }

    @GetMapping("/groups/{groupId}/members")
    public Result<CursorPage<CommunityResponses.Member>> members(
        @PathVariable Long groupId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return Result.ok(mapPage(groupService.members(groupId, status, cursorId, limit), CommunityResponses::member));
    }

    @PostMapping("/groups/{groupId}/members/{memberId}/decision")
    public Result<CommunityResponses.Member> decideMembership(
        @PathVariable Long groupId,
        @PathVariable Long memberId,
        @Valid @RequestBody CommunityRequests.MembershipDecision request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.member(
            groupService.decideMembership(groupId, memberId, request, idempotencyKey)));
    }

    @PutMapping("/groups/{groupId}/members/{memberId}/role")
    public Result<CommunityResponses.Member> changeMemberRole(
        @PathVariable Long groupId,
        @PathVariable Long memberId,
        @Valid @RequestBody CommunityRequests.ChangeMemberRole request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.member(
            groupService.changeRole(groupId, memberId, request, idempotencyKey)));
    }

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    public Result<Void> removeMember(@PathVariable Long groupId,
                                     @PathVariable Long memberId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey) {
        groupService.removeMember(groupId, memberId, idempotencyKey);
        return Result.ok();
    }

    @PostMapping("/groups/{groupId}/content/{targetType}/{targetId}/hide")
    public Result<Void> groupHideContent(@PathVariable Long groupId,
                                         @PathVariable String targetType,
                                         @PathVariable Long targetId,
                                         @Valid @RequestBody CommunityRequests.ModerationAction request,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.groupModerate(groupId, targetType, targetId, false, request, idempotencyKey);
        return Result.ok();
    }

    @PostMapping("/groups/{groupId}/content/{targetType}/{targetId}/restore")
    public Result<Void> groupRestoreContent(@PathVariable Long groupId,
                                            @PathVariable String targetType,
                                            @PathVariable Long targetId,
                                            @Valid @RequestBody CommunityRequests.ModerationAction request,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.groupModerate(groupId, targetType, targetId, true, request, idempotencyKey);
        return Result.ok();
    }

    @GetMapping("/posts")
    public Result<CursorPage<CommunityResponses.Post>> posts(@Valid CommunityRequests.PostQuery query) {
        return Result.ok(mapPage(contentService.searchPosts(query), CommunityResponses::post));
    }

    @GetMapping("/posts/{postId}")
    public Result<CommunityResponses.Post> post(@PathVariable Long postId) {
        return Result.ok(CommunityResponses.post(contentService.getPost(postId)));
    }

    @PostMapping("/posts")
    public Result<CommunityResponses.Post> createPost(
        @Valid @RequestBody CommunityRequests.CreatePost request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.post(contentService.createPost(request, idempotencyKey)));
    }

    @PutMapping("/posts/{postId}")
    public Result<CommunityResponses.Post> updatePost(@PathVariable Long postId,
                                                       @Valid @RequestBody CommunityRequests.UpdatePost request,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.post(contentService.updatePost(postId, request, idempotencyKey)));
    }

    @DeleteMapping("/posts/{postId}")
    public Result<Void> deletePost(@PathVariable Long postId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.deletePost(postId, idempotencyKey);
        return Result.ok();
    }

    @GetMapping("/posts/{postId}/comments")
    public Result<CursorPage<CommunityResponses.Comment>> comments(
        @PathVariable Long postId,
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return Result.ok(mapPage(contentService.comments(postId, cursorId, limit), CommunityResponses::comment));
    }

    @PostMapping("/posts/{postId}/comments")
    public Result<CommunityResponses.Comment> createComment(
        @PathVariable Long postId,
        @Valid @RequestBody CommunityRequests.CreateComment request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.comment(contentService.createComment(postId, request, idempotencyKey)));
    }

    @PutMapping("/comments/{commentId}")
    public Result<CommunityResponses.Comment> updateComment(
        @PathVariable Long commentId,
        @Valid @RequestBody CommunityRequests.UpdateComment request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.comment(
            contentService.updateComment(commentId, request, idempotencyKey)));
    }

    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long commentId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.deleteComment(commentId, idempotencyKey);
        return Result.ok();
    }

    @PostMapping("/reports")
    public Result<CommunityResponses.UserReport> createReport(
        @Valid @RequestBody CommunityRequests.CreateReport request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.ok(CommunityResponses.userReport(contentService.report(request, idempotencyKey)));
    }

    @GetMapping("/reports/mine")
    public Result<CursorPage<CommunityResponses.UserReport>> myReports(
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return Result.ok(mapPage(contentService.myReports(cursorId, limit), CommunityResponses::userReport));
    }

    @PostMapping("/blocks/{targetUserId}")
    public Result<Void> block(@PathVariable Long targetUserId,
                              @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.block(targetUserId, idempotencyKey);
        return Result.ok();
    }

    @DeleteMapping("/blocks/{targetUserId}")
    public Result<Void> unblock(@PathVariable Long targetUserId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        contentService.unblock(targetUserId, idempotencyKey);
        return Result.ok();
    }

    @GetMapping("/blocks")
    public Result<CursorPage<CommunityResponses.BlockedUser>> blockedUsers(
        @RequestParam(required = false) Long cursorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return Result.ok(mapPage(contentService.blockedUsers(cursorId, limit), CommunityResponses::blockedUser));
    }

    private <S, T> CursorPage<T> mapPage(CursorPage<S> source, Function<S, T> converter) {
        return CursorPage.of(source.records().stream().map(converter).toList(),
            source.nextCursor(), source.hasMore());
    }
}
