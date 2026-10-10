package com.example.venue.community.activity.source;

import com.example.venue.common.BusinessException;
import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.service.CommunityAccessService;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityActivityPlacementSourceReaderTest {

    @Mock private CommunityActivityMapper activityMapper;
    @Mock private CommunityAccessService accessService;
    @Mock private CommunityPostMapper postMapper;

    private CommunityActivityPlacementSourceReader reader;
    private LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        reader = new CommunityActivityPlacementSourceReader(activityMapper, accessService, postMapper);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void withAuthority(String authority) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
            "user", null, List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CommunityActivity activity(Long id, String status, Long groupId, Long referencePostId,
                                       Long organizerUserId, LocalDateTime endTime) {
        CommunityActivity a = new CommunityActivity();
        a.id = id;
        a.activityNo = "ACT-" + id;
        a.groupId = groupId;
        a.referencePostId = referencePostId;
        a.organizerUserId = organizerUserId;
        a.title = "周末羽毛球活动";
        a.description = "欢迎参加";
        a.startTime = now.plusHours(1);
        a.endTime = endTime;
        a.status = status;
        a.versionNo = 1L;
        return a;
    }

    private CommunityPost post(Long id, Long groupId, String status) {
        CommunityPost p = new CommunityPost();
        p.id = id;
        p.groupId = groupId;
        p.status = status;
        return p;
    }

    @Test
    void readForUserReturnsSnapshotForOpenActivityWithAccess() {
        withAuthority("community:access");
        CommunityActivity a = activity(1L, "PUBLISHED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(1L)).thenReturn(a);

        PlacementSourceSnapshot snapshot = reader.readForUser(1L, 30L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sourceType()).isEqualTo(PlacementSourceType.COMMUNITY_ACTIVITY);
        assertThat(snapshot.title()).isEqualTo("周末羽毛球活动");
        assertThat(snapshot.deepLink()).isEqualTo("/activities/1");
        assertThat(snapshot.imageUrl()).isNull();
        verify(accessService).requireActivityContext(10L, null, 30L);
        verify(accessService).requireNoBlock(30L, 20L);
    }

    @Test
    void returnsNullWithoutCommunityAccessAuthority() {
        withAuthority("user:me");

        assertThat(reader.readForUser(1L, 30L)).isNull();
        verify(activityMapper, never()).selectById(anyLong());
        verify(accessService, never()).requireActivityContext(anyLong(), anyLong(), anyLong());
    }

    @Test
    void returnsNullForDraftActivity() {
        withAuthority("community:access");
        CommunityActivity a = activity(2L, "DRAFT", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(2L)).thenReturn(a);

        assertThat(reader.readForUser(2L, 30L)).isNull();
    }

    @Test
    void returnsNullForCanceledActivity() {
        withAuthority("community:access");
        CommunityActivity a = activity(3L, "CANCELED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(3L)).thenReturn(a);

        assertThat(reader.readForUser(3L, 30L)).isNull();
    }

    @Test
    void returnsNullForExpiredActivity() {
        withAuthority("community:access");
        CommunityActivity a = activity(4L, "PUBLISHED", 10L, null, 20L, now.minusHours(1));
        when(activityMapper.selectById(4L)).thenReturn(a);

        assertThat(reader.readForUser(4L, 30L)).isNull();
    }

    @Test
    void returnsNullWhenActivityContextCheckThrows() {
        withAuthority("community:access");
        CommunityActivity a = activity(5L, "PUBLISHED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(5L)).thenReturn(a);
        doThrow(BusinessException.forbidden("不可见"))
            .when(accessService).requireActivityContext(10L, null, 30L);

        assertThat(reader.readForUser(5L, 30L)).isNull();
        // Should not leak the reason — returns null without throwing
    }

    @Test
    void returnsNullWhenBlockExistsBetweenUserAndOrganizer() {
        withAuthority("community:access");
        CommunityActivity a = activity(6L, "PUBLISHED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(6L)).thenReturn(a);
        doThrow(BusinessException.forbidden("双向屏蔽"))
            .when(accessService).requireNoBlock(30L, 20L);

        assertThat(reader.readForUser(6L, 30L)).isNull();
    }

    @Test
    void returnsNullForMissingActivity() {
        withAuthority("community:access");
        when(activityMapper.selectById(99L)).thenReturn(null);
        assertThat(reader.readForUser(99L, 30L)).isNull();
    }

    @Test
    void returnsNullForInvalidSourceId() {
        withAuthority("community:access");
        assertThat(reader.readForUser(null, 30L)).isNull();
        assertThat(reader.readForUser(0L, 30L)).isNull();
    }

    @Test
    void returnsNullForNullUserId() {
        withAuthority("community:access");

        assertThat(reader.readForUser(1L, null)).isNull();
        verify(activityMapper, never()).selectById(anyLong());
    }

    @Test
    void fullStatusActivityIsStillReadable() {
        withAuthority("community:access");
        CommunityActivity a = activity(7L, "FULL", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(7L)).thenReturn(a);

        PlacementSourceSnapshot snapshot = reader.readForUser(7L, 30L);
        assertThat(snapshot).isNotNull();
    }

    @Test
    void readForPreviewChecksGroupActiveAndReferencePost() {
        CommunityActivity a = activity(8L, "PUBLISHED", 10L, 50L, 20L, now.plusDays(1));
        when(activityMapper.selectById(8L)).thenReturn(a);
        CommunityGroup group = new CommunityGroup();
        group.id = 10L;
        group.status = "ACTIVE";
        when(accessService.requireActiveGroup(10L)).thenReturn(group);
        CommunityPost refPost = post(50L, 10L, "PUBLISHED");
        when(postMapper.selectById(50L)).thenReturn(refPost);

        PlacementSourceSnapshot snapshot = reader.readForPreview(8L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.title()).isEqualTo("周末羽毛球活动");
    }

    @Test
    void readForPreviewReturnsNullWhenGroupInactive() {
        CommunityActivity a = activity(9L, "PUBLISHED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(9L)).thenReturn(a);
        doThrow(BusinessException.forbidden("小组停用"))
            .when(accessService).requireActiveGroup(10L);

        assertThat(reader.readForPreview(9L)).isNull();
    }

    @Test
    void readForPreviewReturnsNullWhenReferencePostHidden() {
        CommunityActivity a = activity(10L, "PUBLISHED", 10L, 50L, 20L, now.plusDays(1));
        when(activityMapper.selectById(10L)).thenReturn(a);
        CommunityGroup group = new CommunityGroup();
        group.id = 10L;
        group.status = "ACTIVE";
        when(accessService.requireActiveGroup(10L)).thenReturn(group);
        when(postMapper.selectById(50L)).thenReturn(post(50L, 10L, "HIDDEN"));

        assertThat(reader.readForPreview(10L)).isNull();
    }

    @Test
    void readForPreviewReturnsNullWhenReferencePostInDifferentGroup() {
        CommunityActivity a = activity(11L, "PUBLISHED", 10L, 50L, 20L, now.plusDays(1));
        when(activityMapper.selectById(11L)).thenReturn(a);
        CommunityGroup group = new CommunityGroup();
        group.id = 10L;
        group.status = "ACTIVE";
        when(accessService.requireActiveGroup(10L)).thenReturn(group);
        when(postMapper.selectById(50L)).thenReturn(post(50L, 99L, "PUBLISHED"));

        assertThat(reader.readForPreview(11L)).isNull();
    }

    @Test
    void readForPreviewSkipsReferencePostCheckWhenNull() {
        CommunityActivity a = activity(12L, "PUBLISHED", 10L, null, 20L, now.plusDays(1));
        when(activityMapper.selectById(12L)).thenReturn(a);
        CommunityGroup group = new CommunityGroup();
        group.id = 10L;
        group.status = "ACTIVE";
        when(accessService.requireActiveGroup(10L)).thenReturn(group);

        PlacementSourceSnapshot snapshot = reader.readForPreview(12L);
        assertThat(snapshot).isNotNull();
        verify(postMapper, never()).selectById(anyLong());
    }
}
