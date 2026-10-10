package com.example.venue.feedback.dto;

public class CustomerServiceCaseActionRequest {
    public Long assignedUserId;
    public String status;
    public String rootCauseCode;
    public String resolutionType;
    public String replyText;
    public String internalNote;
    public String closeReason;
    public String reopenReason;
    public Boolean requiresRefundReview;
    public Boolean requiresCouponReview;
    public Boolean requiresSiteFollowUp;
}
