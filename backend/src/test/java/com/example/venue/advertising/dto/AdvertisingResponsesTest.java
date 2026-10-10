package com.example.venue.advertising.dto;

import com.example.venue.advertising.entity.AdvertisingAdvertiser;
import com.example.venue.advertising.entity.AdvertisingAuditEvent;
import com.example.venue.advertising.entity.AdvertisingCampaign;
import com.example.venue.advertising.entity.AdvertisingCreativeVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AdvertisingResponsesTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void operationalResponsesDoNotExposeSubjectIds() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);

        AdvertisingAdvertiser advertiser = new AdvertisingAdvertiser();
        advertiser.id = 1L;
        advertiser.advertiserNo = "ADV-1";
        advertiser.advertiserName = "广告主";
        advertiser.status = "ACTIVE";
        advertiser.createdBy = 101L;
        advertiser.createTime = now;
        advertiser.updateTime = now;

        AdvertisingCampaign campaign = new AdvertisingCampaign();
        campaign.id = 2L;
        campaign.campaignNo = "CAM-2";
        campaign.advertiserId = 1L;
        campaign.status = "PUBLISHED";
        campaign.createdBy = 101L;
        campaign.submittedBy = 102L;
        campaign.reviewedBy = 103L;
        campaign.publishedBy = 104L;
        campaign.createTime = now;
        campaign.updateTime = now;

        AdvertisingCreativeVersion creative = new AdvertisingCreativeVersion();
        creative.id = 3L;
        creative.creativeNo = "CREATIVE-3";
        creative.campaignId = 2L;
        creative.status = "PUBLISHED";
        creative.createdBy = 101L;
        creative.submittedBy = 102L;
        creative.reviewedBy = 103L;
        creative.publishedBy = 104L;
        creative.createTime = now;
        creative.updateTime = now;

        AdvertisingAuditEvent audit = new AdvertisingAuditEvent();
        audit.id = 4L;
        audit.auditNo = "AUDIT-4";
        audit.entityType = "CAMPAIGN";
        audit.entityId = 2L;
        audit.entityNo = "CAM-2";
        audit.action = "PUBLISH";
        audit.actorId = 104L;
        audit.createTime = now;

        assertThat(json(AdvertisingResponses.AdvertiserResponse.from(advertiser)))
            .doesNotContain("createdBy");
        assertThat(json(AdvertisingResponses.CampaignResponse.from(campaign)))
            .doesNotContain("createdBy", "submittedBy", "reviewedBy", "publishedBy", "pausedBy", "retiredBy");
        assertThat(json(AdvertisingResponses.CreativeResponse.from(creative)))
            .doesNotContain("createdBy", "submittedBy", "reviewedBy", "publishedBy", "pausedBy", "retiredBy");
        assertThat(json(AdvertisingResponses.AuditResponse.from(audit)))
            .doesNotContain("actorId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
