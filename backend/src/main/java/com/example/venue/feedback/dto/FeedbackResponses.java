package com.example.venue.feedback.dto;

import com.example.venue.common.PageResult;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.entity.CustomerServiceCase;
import com.example.venue.feedback.entity.CustomerServiceCaseEvent;
import com.example.venue.feedback.entity.VenueReview;
import com.example.venue.feedback.service.FeedbackTextSanitizer;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.function.Function;

public final class FeedbackResponses {
    private static final Set<String> USER_VISIBLE_EVENT_NOTES = Set.of("REPLY_ADDED", "RESOLVED");

    private FeedbackResponses() {
    }

    public static <S, T> PageResult<T> page(PageResult<S> source, Function<S, T> mapper) {
        if (source == null) {
            return PageResult.of(java.util.List.of(), 0, 1, 10);
        }
        return PageResult.of(
            source.getRecords() == null ? java.util.List.of() : source.getRecords().stream().map(mapper).toList(),
            source.getTotal(),
            source.getPageNum(),
            source.getPageSize());
    }

    public static UserReview userReview(VenueReview review, FeedbackTextSanitizer sanitizer) {
        UserReview response = new UserReview();
        response.id = review.id;
        response.reviewNo = review.reviewNo;
        response.venueId = review.venueId;
        response.spaceId = review.spaceId;
        response.reservationId = review.reservationId;
        response.lifecycleId = review.lifecycleId;
        response.overallRating = review.overallRating;
        response.venueRating = review.venueRating;
        response.spaceRating = review.spaceRating;
        response.serviceRating = review.serviceRating;
        response.tagsJson = review.tagsJson;
        response.contentSanitized = sanitizeForOutput(review.contentSanitized, review.contentText, sanitizer);
        response.sensitiveFlag = review.sensitiveFlag;
        response.status = review.status;
        response.submittedAt = review.submittedAt;
        response.publishedAt = review.publishedAt;
        return response;
    }

    public static UserComplaint userComplaint(CustomerComplaint complaint, FeedbackTextSanitizer sanitizer) {
        UserComplaint response = new UserComplaint();
        response.id = complaint.id;
        response.complaintNo = complaint.complaintNo;
        response.venueId = complaint.venueId;
        response.spaceId = complaint.spaceId;
        response.reservationId = complaint.reservationId;
        response.lifecycleId = complaint.lifecycleId;
        response.noticeId = complaint.noticeId;
        response.assetId = complaint.assetId;
        response.siteIncidentId = complaint.siteIncidentId;
        response.complaintType = complaint.complaintType;
        response.severity = complaint.severity;
        response.status = complaint.status;
        response.title = sanitizeForOutput(null, complaint.title, sanitizer);
        response.contentSanitized = sanitizeForOutput(complaint.contentSanitized, complaint.contentText, sanitizer);
        response.sensitiveFlag = complaint.sensitiveFlag;
        response.submittedAt = complaint.submittedAt;
        response.triagedAt = complaint.triagedAt;
        response.resolvedAt = complaint.resolvedAt;
        response.closedAt = complaint.closedAt;
        return response;
    }

    public static UserCase userCase(CustomerServiceCase serviceCase, FeedbackTextSanitizer sanitizer) {
        UserCase response = new UserCase();
        response.id = serviceCase.id;
        response.caseNo = serviceCase.caseNo;
        response.complaintId = serviceCase.complaintId;
        response.venueId = serviceCase.venueId;
        response.priority = serviceCase.priority;
        response.status = serviceCase.status;
        response.resolutionType = serviceCase.resolutionType;
        response.customerVisibleReply = sanitizeForOutput(serviceCase.customerVisibleReply, null, sanitizer);
        response.requiresRefundReview = serviceCase.requiresRefundReview;
        response.requiresCouponReview = serviceCase.requiresCouponReview;
        response.requiresSiteFollowUp = serviceCase.requiresSiteFollowUp;
        response.openedAt = serviceCase.openedAt;
        response.resolvedAt = serviceCase.resolvedAt;
        response.closedAt = serviceCase.closedAt;
        response.reopenedAt = serviceCase.reopenedAt;
        return response;
    }

    public static AdminComplaint adminComplaint(CustomerComplaint complaint, FeedbackTextSanitizer sanitizer) {
        return adminComplaintFromUser(complaint, sanitizer);
    }

    public static AdminReview adminReview(VenueReview review, FeedbackTextSanitizer sanitizer) {
        AdminReview response = new AdminReview();
        response.id = review.id;
        response.reviewNo = review.reviewNo;
        response.venueId = review.venueId;
        response.spaceId = review.spaceId;
        response.reservationId = review.reservationId;
        response.lifecycleId = review.lifecycleId;
        response.overallRating = review.overallRating;
        response.venueRating = review.venueRating;
        response.spaceRating = review.spaceRating;
        response.serviceRating = review.serviceRating;
        response.tagsJson = review.tagsJson;
        response.contentSanitized = sanitizeForOutput(review.contentSanitized, review.contentText, sanitizer);
        response.sensitiveFlag = review.sensitiveFlag;
        response.status = review.status;
        response.submittedAt = review.submittedAt;
        response.publishedAt = review.publishedAt;
        return response;
    }

    public static AdminCase adminCase(CustomerServiceCase serviceCase, FeedbackTextSanitizer sanitizer) {
        AdminCase response = new AdminCase();
        response.id = serviceCase.id;
        response.caseNo = serviceCase.caseNo;
        response.complaintId = serviceCase.complaintId;
        response.venueId = serviceCase.venueId;
        response.priority = serviceCase.priority;
        response.status = serviceCase.status;
        response.rootCauseCode = serviceCase.rootCauseCode;
        response.resolutionType = serviceCase.resolutionType;
        response.customerVisibleReply = sanitizeForOutput(serviceCase.customerVisibleReply, null, sanitizer);
        response.requiresRefundReview = serviceCase.requiresRefundReview;
        response.requiresCouponReview = serviceCase.requiresCouponReview;
        response.requiresSiteFollowUp = serviceCase.requiresSiteFollowUp;
        response.openedAt = serviceCase.openedAt;
        response.assignedAt = serviceCase.assignedAt;
        response.resolvedAt = serviceCase.resolvedAt;
        response.closedAt = serviceCase.closedAt;
        response.reopenedAt = serviceCase.reopenedAt;
        return response;
    }

    public static UserCaseEvent userCaseEvent(
        CustomerServiceCaseEvent event,
        FeedbackTextSanitizer sanitizer) {
        UserCaseEvent response = new UserCaseEvent();
        response.id = event.id;
        response.eventType = event.eventType;
        response.fromStatus = event.fromStatus;
        response.toStatus = event.toStatus;
        response.noteSanitized = USER_VISIBLE_EVENT_NOTES.contains(event.eventType)
            ? sanitizeForOutput(event.noteSanitized, event.noteText, sanitizer)
            : null;
        response.occurredAt = event.occurredAt;
        return response;
    }

    public static AdminCaseEvent adminCaseEvent(
        CustomerServiceCaseEvent event,
        FeedbackTextSanitizer sanitizer) {
        AdminCaseEvent response = new AdminCaseEvent();
        response.id = event.id;
        response.eventType = event.eventType;
        response.fromStatus = event.fromStatus;
        response.toStatus = event.toStatus;
        response.noteSanitized = sanitizeForOutput(event.noteSanitized, event.noteText, sanitizer);
        response.internalNoteSanitized = sanitizeForOutput(null, event.internalNote, sanitizer);
        response.actorUsername = sanitizeForOutput(null, event.actorUsername, sanitizer);
        response.sourceType = event.sourceType;
        response.occurredAt = event.occurredAt;
        return response;
    }

    public static CaseEvent caseEvent(CustomerServiceCaseEvent event, FeedbackTextSanitizer sanitizer) {
        CaseEvent response = new CaseEvent();
        response.id = event.id;
        response.eventNo = event.eventNo;
        response.caseId = event.caseId;
        response.complaintId = event.complaintId;
        response.eventType = event.eventType;
        response.fromStatus = event.fromStatus;
        response.toStatus = event.toStatus;
        response.noteSanitized = sanitizeForOutput(event.noteSanitized, event.noteText, sanitizer);
        response.sourceType = event.sourceType;
        response.occurredAt = event.occurredAt;
        return response;
    }

    private static AdminComplaint adminComplaintFromUser(CustomerComplaint complaint, FeedbackTextSanitizer sanitizer) {
        AdminComplaint response = new AdminComplaint();
        response.id = complaint.id;
        response.complaintNo = complaint.complaintNo;
        response.venueId = complaint.venueId;
        response.spaceId = complaint.spaceId;
        response.reservationId = complaint.reservationId;
        response.lifecycleId = complaint.lifecycleId;
        response.noticeId = complaint.noticeId;
        response.assetId = complaint.assetId;
        response.siteIncidentId = complaint.siteIncidentId;
        response.complaintType = complaint.complaintType;
        response.severity = complaint.severity;
        response.status = complaint.status;
        response.title = sanitizeForOutput(null, complaint.title, sanitizer);
        response.contentSanitized = sanitizeForOutput(complaint.contentSanitized, complaint.contentText, sanitizer);
        response.sensitiveFlag = complaint.sensitiveFlag;
        response.submittedAt = complaint.submittedAt;
        response.triagedAt = complaint.triagedAt;
        response.resolvedAt = complaint.resolvedAt;
        response.closedAt = complaint.closedAt;
        return response;
    }

    private static String sanitizeForOutput(String storedSanitized, String rawText, FeedbackTextSanitizer sanitizer) {
        String chosen = storedSanitized != null && !storedSanitized.isBlank() ? storedSanitized : rawText;
        return sanitizer.sanitize(chosen).text();
    }

    public static class UserReview {
        public Long id;
        public String reviewNo;
        public Long venueId;
        public Long spaceId;
        public Long reservationId;
        public Long lifecycleId;
        public Integer overallRating;
        public Integer venueRating;
        public Integer spaceRating;
        public Integer serviceRating;
        public String tagsJson;
        public String contentSanitized;
        public Integer sensitiveFlag;
        public String status;
        public LocalDateTime submittedAt;
        public LocalDateTime publishedAt;
    }

    public static class UserComplaint {
        public Long id;
        public String complaintNo;
        public Long venueId;
        public Long spaceId;
        public Long reservationId;
        public Long lifecycleId;
        public Long noticeId;
        public Long assetId;
        public Long siteIncidentId;
        public String complaintType;
        public String severity;
        public String status;
        public String title;
        public String contentSanitized;
        public Integer sensitiveFlag;
        public LocalDateTime submittedAt;
        public LocalDateTime triagedAt;
        public LocalDateTime resolvedAt;
        public LocalDateTime closedAt;
    }

    public static class UserCase {
        public Long id;
        public String caseNo;
        public Long complaintId;
        public Long venueId;
        public String priority;
        public String status;
        public String resolutionType;
        public String customerVisibleReply;
        public Integer requiresRefundReview;
        public Integer requiresCouponReview;
        public Integer requiresSiteFollowUp;
        public LocalDateTime openedAt;
        public LocalDateTime resolvedAt;
        public LocalDateTime closedAt;
        public LocalDateTime reopenedAt;
    }

    public static class AdminComplaint extends UserComplaint {
    }

    public static class AdminReview extends UserReview {
    }

    public static class AdminCase extends UserCase {
        public String rootCauseCode;
        public LocalDateTime assignedAt;
    }

    public static class UserCaseEvent {
        public Long id;
        public String eventType;
        public String fromStatus;
        public String toStatus;
        public String noteSanitized;
        public LocalDateTime occurredAt;
    }

    public static class AdminCaseEvent {
        public Long id;
        public String eventType;
        public String fromStatus;
        public String toStatus;
        public String noteSanitized;
        public String internalNoteSanitized;
        public String actorUsername;
        public String sourceType;
        public LocalDateTime occurredAt;
    }

    public static class CaseEvent {
        public Long id;
        public String eventNo;
        public Long caseId;
        public Long complaintId;
        public String eventType;
        public String fromStatus;
        public String toStatus;
        public String noteSanitized;
        public String sourceType;
        public LocalDateTime occurredAt;
    }
}
