package com.example.venue.notice.source;

import com.example.venue.notice.entity.Notice;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticePlacementSourceReaderTest {

    @Mock private NoticeMapper noticeMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private NoticePlacementSourceReader reader() {
        return new NoticePlacementSourceReader(noticeMapper, objectMapper);
    }

    private Notice notice(Long id, String type, String status, String targetRuleJson, Long targetVenueId, Integer deleted) {
        Notice notice = new Notice();
        notice.id = id;
        notice.title = "全局公告标题";
        notice.content = "<p>正文内容</p>";
        notice.type = type;
        notice.status = status;
        notice.targetRuleJson = targetRuleJson;
        notice.targetVenueId = targetVenueId;
        notice.deleted = deleted;
        notice.publishTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        notice.updateTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        return notice;
    }

    @Test
    void readsAnonymousGlobalNoticeWithAllUsersScope() {
        when(noticeMapper.selectById(1L)).thenReturn(
            notice(1L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 0));

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sourceType()).isEqualTo(PlacementSourceType.GLOBAL_NOTICE);
        assertThat(snapshot.title()).isEqualTo("全局公告标题");
        assertThat(snapshot.summary()).isEqualTo("正文内容");
        assertThat(snapshot.imageUrl()).isNull();
        assertThat(snapshot.deepLink()).isEqualTo("/me/messages");
    }

    @Test
    void returnsNullForTargetedVenueNotice() {
        when(noticeMapper.selectById(2L)).thenReturn(
            notice(2L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", 500L, 0));

        assertThat(reader().readForUser(2L, 10L)).isNull();
    }

    @Test
    void returnsNullForNonGlobalType() {
        when(noticeMapper.selectById(3L)).thenReturn(
            notice(3L, "VENUE", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 0));

        assertThat(reader().readForUser(3L, 10L)).isNull();
    }

    @Test
    void returnsNullForUnpublishedNotice() {
        when(noticeMapper.selectById(4L)).thenReturn(
            notice(4L, "GLOBAL", "DRAFT", "{\"targetScope\":\"ALL_USERS\"}", null, 0));

        assertThat(reader().readForUser(4L, 10L)).isNull();
    }

    @Test
    void returnsNullForDeletedNotice() {
        when(noticeMapper.selectById(5L)).thenReturn(
            notice(5L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 1));

        assertThat(reader().readForUser(5L, 10L)).isNull();
    }

    @Test
    void returnsNullForNonAllUsersAudience() {
        when(noticeMapper.selectById(6L)).thenReturn(
            notice(6L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"MEMBERS_ONLY\"}", null, 0));

        assertThat(reader().readForUser(6L, 10L)).isNull();
    }

    @Test
    void returnsNullForMalformedTargetRuleJson() {
        when(noticeMapper.selectById(7L)).thenReturn(
            notice(7L, "GLOBAL", "PUBLISHED", "not-json", null, 0));

        assertThat(reader().readForUser(7L, 10L)).isNull();
    }

    @Test
    void returnsNullForMissingNotice() {
        when(noticeMapper.selectById(99L)).thenReturn(null);
        assertThat(reader().readForUser(99L, 10L)).isNull();
    }

    @Test
    void returnsNullForInvalidSourceId() {
        assertThat(reader().readForUser(null, 10L)).isNull();
        assertThat(reader().readForUser(0L, 10L)).isNull();
        assertThat(reader().readForUser(-1L, 10L)).isNull();
    }

    @Test
    void readForPreviewHasSameContractAsReadForUser() {
        when(noticeMapper.selectById(1L)).thenReturn(
            notice(1L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 0));

        PlacementSourceSnapshot preview = reader().readForPreview(1L);
        PlacementSourceSnapshot userSnapshot = reader().readForUser(1L, 10L);

        assertThat(preview).isNotNull();
        assertThat(userSnapshot).isNotNull();
        assertThat(preview.title()).isEqualTo(userSnapshot.title());
        assertThat(preview.deepLink()).isEqualTo(userSnapshot.deepLink());
    }

    @Test
    void stripsHtmlFromSummary() {
        Notice notice = notice(1L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 0);
        notice.content = "<script>alert('xss')</script><p>安全文本</p>";
        when(noticeMapper.selectById(1L)).thenReturn(notice);

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);
        assertThat(snapshot.summary()).doesNotContain("<", ">", "script");
        assertThat(snapshot.summary()).contains("安全文本");
    }

    @Test
    void truncatesLongTitle() {
        Notice notice = notice(1L, "GLOBAL", "PUBLISHED", "{\"targetScope\":\"ALL_USERS\"}", null, 0);
        notice.title = "很".repeat(200);
        when(noticeMapper.selectById(1L)).thenReturn(notice);

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);
        assertThat(snapshot.title()).hasSize(128);
    }
}
