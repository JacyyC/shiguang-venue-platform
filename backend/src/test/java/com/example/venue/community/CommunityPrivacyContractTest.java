package com.example.venue.community;

import com.example.venue.common.AuditLog;
import com.example.venue.community.activity.dto.ActivityResponses;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.entity.CommunityMatchDecision;
import com.example.venue.community.controller.CommunityController;
import com.example.venue.community.dto.CommunityResponses;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.entity.CommunityReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityPrivacyContractTest {
    private static final Set<String> FORBIDDEN_PUBLIC_FIELDS = Set.of(
        "email", "phone", "internalnote", "reporterid", "reporteruserid",
        "privatelocation", "preciselocation", "address", "metadatajson",
        "idempotencykey", "requesthash");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void ordinaryCommunityDtosExposeOnlyThePublicUserProfileWhitelist() {
        List<Class<?>> publicTypes = List.of(
            CommunityResponses.Group.class,
            CommunityResponses.Member.class,
            CommunityResponses.Post.class,
            CommunityResponses.Comment.class,
            CommunityResponses.UserReport.class,
            CommunityResponses.BlockedUser.class,
            CommunityResponses.UserSummary.class,
            ActivityResponses.Activity.class,
            ActivityResponses.CompanionRequest.class,
            ActivityResponses.Location.class,
            ActivityResponses.Participation.class,
            ActivityResponses.Match.class);

        for (Class<?> type : publicTypes) {
            Set<String> fields = Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
            assertThat(fields)
                .as("ordinary DTO %s", type.getSimpleName())
                .doesNotContainAnyElementsOf(FORBIDDEN_PUBLIC_FIELDS);
        }

        Set<String> publicUserFields = Arrays.stream(CommunityResponses.UserSummary.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        assertThat(publicUserFields).containsExactlyInAnyOrder("id", "nickname", "avatar");
    }

    @Test
    void ordinaryResponseMappersDoNotSerializePrivateEntityOrGovernanceFields() throws Exception {
        CommunityGroup group = new CommunityGroup();
        group.id = 1L;
        group.name = "学习小组";
        group.description = "公开简介";
        group.ownerUserId = 7L;
        group.status = "ACTIVE";
        group.idempotencyKey = "secret-group-key";
        group.requestHash = "secret-group-hash";

        CommunityPost post = new CommunityPost();
        post.id = 2L;
        post.groupId = 1L;
        post.authorUserId = 7L;
        post.title = "公开标题";
        post.contentText = "公开正文";
        post.status = "PUBLISHED";
        post.idempotencyKey = "secret-post-key";
        post.requestHash = "secret-post-hash";
        post.hiddenReasonCode = "internal-hidden-reason";

        CommunityComment comment = new CommunityComment();
        comment.id = 3L;
        comment.postId = 2L;
        comment.groupId = 1L;
        comment.authorUserId = 8L;
        comment.contentText = "公开评论";
        comment.status = "PUBLISHED";
        comment.requestHash = "secret-comment-hash";

        CommunityReport report = new CommunityReport();
        report.id = 4L;
        report.reporterUserId = 7L;
        report.targetType = "POST";
        report.targetId = 2L;
        report.reasonText = "举报原因";
        report.status = "OPEN";
        report.internalNote = "仅运营可见";
        report.requestHash = "secret-report-hash";

        CommunityActivity activity = new CommunityActivity();
        activity.id = 5L;
        activity.activityNo = "ACT-5";
        activity.groupId = 1L;
        activity.organizerUserId = 7L;
        activity.title = "公开活动";
        activity.locationType = "VENUE_SPACE";
        activity.venueId = 10L;
        activity.spaceId = 20L;
        activity.reservationId = 91L;
        activity.locationCommitmentStatus = "CONFIRMED";
        activity.commitmentVersion = 3L;

        CommunityCompanionRequest companion = new CommunityCompanionRequest();
        companion.id = 6L;
        companion.requestNo = "CPR-6";
        companion.groupId = 1L;
        companion.ownerUserId = 8L;
        companion.title = "公开搭子需求";
        companion.locationType = "VENUE_SPACE";
        companion.venueId = 10L;
        companion.spaceId = 20L;
        companion.reservationId = 92L;
        companion.locationCommitmentStatus = "CONFIRMED";
        companion.commitmentVersion = 4L;

        CommunityActivityParticipation participation = new CommunityActivityParticipation();
        participation.id = 7L;
        participation.participationNo = "PAR-7";
        participation.targetType = "ACTIVITY";
        participation.targetId = activity.id;
        participation.userId = 9L;
        participation.applicationId = 93L;
        participation.status = "ACTIVE";
        participation.versionNo = 5L;

        CommunityMatchDecision decision = new CommunityMatchDecision();
        decision.id = 8L;
        decision.ruleVersion = "COMPANION_MATCH_V1";
        decision.score = 80;
        decision.reasonCodesJson = "[\"ACTIVITY_TYPE_MATCH\"]";
        decision.evidenceJson = "{\"overlapMinutes\":120}";

        JsonNode json = objectMapper.valueToTree(List.of(
            CommunityResponses.group(group),
            CommunityResponses.post(post),
            CommunityResponses.comment(comment),
            CommunityResponses.userReport(report),
            ActivityResponses.activity(activity),
            ActivityResponses.companion(companion),
            ActivityResponses.participation(participation),
            ActivityResponses.match(decision, companion, List.of("ACTIVITY_TYPE_MATCH"))));
        String serialized = objectMapper.writeValueAsString(json).toLowerCase(Locale.ROOT);

        assertThat(serialized)
            .contains("学习小组", "公开正文", "公开评论", "举报原因")
            .doesNotContain(
                "secret-group-key", "secret-group-hash", "secret-post-key", "secret-post-hash",
                "secret-comment-hash", "secret-report-hash", "internal-hidden-reason", "仅运营可见",
                "internalnote", "reporteruserid", "email", "phone", "privatelocation", "preciselocation",
                "reservationid", "commitmentversion", "applicationid", "evidencejson", "overlapminutes");
    }

    @Test
    void bodyBearingCommunityControllerEndpointsNeverUseGenericAuditLogCapture() {
        List<Method> endpoints = Arrays.stream(CommunityController.class.getDeclaredMethods())
            .filter(this::isEndpoint)
            .toList();

        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints)
            .allSatisfy(method -> assertThat(method.getAnnotation(AuditLog.class))
                .as("%s must not copy request bodies into operation logs", method.getName())
                .isNull());
    }

    private boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class);
    }
}
