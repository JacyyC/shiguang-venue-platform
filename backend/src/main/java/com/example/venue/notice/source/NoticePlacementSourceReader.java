package com.example.venue.notice.source;

import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公告域 Source Reader。复用公告自身 JSON 解析和文本清洗规则，只返回白名单字段。
 * 来源合同：deleted=0、status=PUBLISHED、type=GLOBAL、target_venue_id IS NULL、
 * targetRule.targetScope=ALL_USERS。深链固定为 /me/messages。不返回受众 JSON、publisher、
 * batch、experiment 或 outbox 字段。全局公告对所有登录用户可见，readForPreview 与 readForUser
 * 合同一致。
 */
@Component
public class NoticePlacementSourceReader implements PlacementSourceReader {

    private static final String DEEP_LINK = "/me/messages";
    private static final int SUMMARY_MAX_LENGTH = 200;

    private final NoticeMapper noticeMapper;
    private final ObjectMapper objectMapper;

    public NoticePlacementSourceReader(NoticeMapper noticeMapper, ObjectMapper objectMapper) {
        this.noticeMapper = noticeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlacementSourceType sourceType() {
        return PlacementSourceType.GLOBAL_NOTICE;
    }

    @Override
    public PlacementSourceSnapshot readForPreview(Long sourceId) {
        return read(sourceId);
    }

    @Override
    public PlacementSourceSnapshot readForUser(Long sourceId, Long userId) {
        return read(sourceId);
    }

    private PlacementSourceSnapshot read(Long sourceId) {
        if (sourceId == null || sourceId <= 0) {
            return null;
        }
        Notice notice = noticeMapper.selectById(sourceId);
        if (!isAnonymousPublicNotice(notice)) {
            return null;
        }
        String title = truncate(notice.title, 128);
        String summary = summarize(notice.content);
        String sourceVersion = versionOf(notice);
        return new PlacementSourceSnapshot(
            PlacementSourceType.GLOBAL_NOTICE,
            notice.id,
            sourceVersion,
            title,
            summary,
            null,
            notice.publishTime,
            null,
            DEEP_LINK
        );
    }

    private boolean isAnonymousPublicNotice(Notice notice) {
        if (notice == null
            || notice.deleted != null && notice.deleted == 1
            || !"PUBLISHED".equals(notice.status)
            || !"GLOBAL".equals(notice.type)
            || notice.targetVenueId != null
            || notice.targetRuleJson == null) {
            return false;
        }
        try {
            JsonNode targetRule = objectMapper.readTree(notice.targetRuleJson);
            return "ALL_USERS".equals(targetRule.path("targetScope").asText());
        } catch (Exception exception) {
            return false;
        }
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String plain = Jsoup.clean(content, Safelist.none());
        return truncate(plain, SUMMARY_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String versionOf(Notice notice) {
        LocalDateTime version = notice.updateTime != null ? notice.updateTime : notice.publishTime;
        return version == null ? null : version.toString();
    }
}
