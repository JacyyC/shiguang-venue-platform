package com.example.venue.member.dto;

import com.example.venue.member.entity.MemberRewardEvent;

import java.time.LocalDateTime;

public class MemberRewardEventResponse {
    public Long id;
    public String eventType;
    public Long redemptionId;
    public Long catalogItemId;
    public Long catalogVersionId;
    public Long actorUserId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public String fromStatus;
    public String toStatus;
    public String resultStatus;
    public LocalDateTime occurredAt;

    public static MemberRewardEventResponse from(MemberRewardEvent entity) {
        MemberRewardEventResponse response = new MemberRewardEventResponse();
        response.id = entity.id;
        response.eventType = entity.eventType;
        response.redemptionId = entity.redemptionId;
        response.catalogItemId = entity.catalogItemId;
        response.catalogVersionId = entity.catalogVersionId;
        response.actorUserId = entity.actorUserId;
        response.sourceType = entity.sourceType;
        response.reasonCode = entity.reasonCode;
        response.reasonText = entity.reasonText;
        response.fromStatus = entity.fromStatus;
        response.toStatus = entity.toStatus;
        response.resultStatus = entity.resultStatus;
        response.occurredAt = entity.occurredAt;
        return response;
    }
}
