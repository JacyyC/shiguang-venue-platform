package com.example.venue.advertising.dto;

import com.example.venue.advertising.entity.AdvertisingAdvertiser;
import com.example.venue.advertising.entity.AdvertisingAuditEvent;
import com.example.venue.advertising.entity.AdvertisingCampaign;
import com.example.venue.advertising.entity.AdvertisingCreativeVersion;

import java.time.LocalDateTime;

public final class AdvertisingResponses {
    private AdvertisingResponses() {
    }

    public record AdvertiserResponse(
        Long id, String advertiserNo, String advertiserName, String status,
        LocalDateTime createTime, LocalDateTime updateTime
    ) {
        public static AdvertiserResponse from(AdvertisingAdvertiser entity) {
            return new AdvertiserResponse(entity.id, entity.advertiserNo, entity.advertiserName,
                entity.status, entity.createTime, entity.updateTime);
        }
    }

    public record CampaignResponse(
        Long id, String campaignNo, Long advertiserId, Long venueId, String campaignName,
        LocalDateTime effectiveFrom, LocalDateTime effectiveUntil, String status,
        LocalDateTime submittedAt, LocalDateTime reviewedAt, LocalDateTime publishedAt,
        LocalDateTime pausedAt, LocalDateTime retiredAt, LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
        public static CampaignResponse from(AdvertisingCampaign entity) {
            return new CampaignResponse(entity.id, entity.campaignNo, entity.advertiserId, entity.venueId,
                entity.campaignName, entity.effectiveFrom, entity.effectiveUntil, entity.status,
                entity.submittedAt, entity.reviewedAt, entity.publishedAt, entity.pausedAt,
                entity.retiredAt, entity.createTime, entity.updateTime);
        }
    }

    public record CreativeResponse(
        Long id, String creativeNo, Long campaignId, Integer versionNo, String headline,
        String bodyText, String callToAction, String targetType, Long targetId, String status,
        LocalDateTime submittedAt, LocalDateTime reviewedAt, LocalDateTime publishedAt,
        LocalDateTime pausedAt, LocalDateTime retiredAt,
        LocalDateTime createTime, LocalDateTime updateTime
    ) {
        public static CreativeResponse from(AdvertisingCreativeVersion entity) {
            return new CreativeResponse(entity.id, entity.creativeNo, entity.campaignId, entity.versionNo,
                entity.headline, entity.bodyText, entity.callToAction, entity.targetType, entity.targetId,
                entity.status, entity.submittedAt, entity.reviewedAt, entity.publishedAt, entity.pausedAt,
                entity.retiredAt, entity.createTime, entity.updateTime);
        }
    }

    /** 审计 DTO 不返回审核备注原文，避免把内部备注扩散到普通运营页面。 */
    public record AuditResponse(
        Long id, String auditNo, String entityType, Long entityId, String entityNo, Long venueId,
        String relatedEntityType, Long relatedEntityId, String action,
        String sourceType, String reasonCode, String beforeStatus, String afterStatus,
        String resultStatus, LocalDateTime createTime
    ) {
        public static AuditResponse from(AdvertisingAuditEvent entity) {
            return new AuditResponse(entity.id, entity.auditNo, entity.entityType, entity.entityId,
                entity.entityNo, entity.venueId, entity.relatedEntityType, entity.relatedEntityId,
                entity.action, entity.sourceType, entity.reasonCode,
                entity.beforeStatus, entity.afterStatus, entity.resultStatus, entity.createTime);
        }
    }
}
