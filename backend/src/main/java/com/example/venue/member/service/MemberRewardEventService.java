package com.example.venue.member.service;

import com.example.venue.member.entity.MemberRewardCatalogItem;
import com.example.venue.member.entity.MemberRewardCatalogVersion;
import com.example.venue.member.entity.MemberRewardEvent;
import com.example.venue.member.entity.MemberRewardRedemption;
import com.example.venue.member.mapper.MemberRewardEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class MemberRewardEventService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final MemberRewardEventMapper mapper;
    private final ObjectMapper objectMapper;

    public MemberRewardEventService(MemberRewardEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void redemption(MemberRewardRedemption redemption, String type, String key, String from, String to,
                           String result, Long actorId, String source, String reasonCode, String reasonText) {
        LocalDateTime now = LocalDateTime.now();
        MemberRewardEvent event = new MemberRewardEvent();
        event.eventNo = "MRE" + now.format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        event.eventKey = key;
        event.eventType = type;
        event.redemptionId = redemption.id;
        event.catalogItemId = redemption.catalogItemId;
        event.catalogVersionId = redemption.catalogVersionId;
        event.actorUserId = actorId;
        event.sourceType = source;
        event.reasonCode = reasonCode;
        event.reasonText = reasonText;
        event.fromStatus = from;
        event.toStatus = to;
        event.resultStatus = result;
        event.metadataJson = json(Map.of("redemptionNo", redemption.redemptionNo));
        event.occurredAt = now;
        event.createTime = now;
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Stable event keys make replay a no-op.
        }
    }

    public void catalogVersionPublished(MemberRewardCatalogItem item, MemberRewardCatalogVersion version,
                                        String fromStatus) {
        LocalDateTime now = LocalDateTime.now();
        appendCatalog(item, version.id, "CATALOG_VERSION_PUBLISHED:" + version.id,
            "CATALOG_VERSION_PUBLISHED", fromStatus, "ACTIVE", version.publishedBy,
            "CATALOG_VERSION_PUBLISHED", "兑换版本发布", now,
            json(Map.of("itemCode", item.itemCode, "versionNo", version.versionNo)));
    }

    public void catalogStatusChanged(MemberRewardCatalogItem item, String fromStatus, String toStatus) {
        LocalDateTime now = LocalDateTime.now();
        appendCatalog(item, null,
            "CATALOG_ITEM_STATUS_CHANGED:" + item.id + ":" + item.updateTime + ":" + toStatus,
            "CATALOG_ITEM_STATUS_CHANGED", fromStatus, toStatus, item.updatedBy,
            "CATALOG_ITEM_STATUS_CHANGED", "兑换目录状态变更", now,
            json(Map.of("itemCode", item.itemCode)));
    }

    private void appendCatalog(MemberRewardCatalogItem item, Long versionId, String key, String type,
                               String from, String to, Long actorId, String reasonCode, String reasonText,
                               LocalDateTime now, String metadataJson) {
        MemberRewardEvent event = new MemberRewardEvent();
        event.eventNo = "MRE" + now.format(NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        event.eventKey = key;
        event.eventType = type;
        event.catalogItemId = item.id;
        event.catalogVersionId = versionId;
        event.actorUserId = actorId;
        event.sourceType = "ADMIN";
        event.reasonCode = reasonCode;
        event.reasonText = reasonText;
        event.fromStatus = from;
        event.toStatus = to;
        event.resultStatus = "SUCCESS";
        event.metadataJson = metadataJson;
        event.occurredAt = now;
        event.createTime = now;
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Stable event keys make replay a no-op.
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
