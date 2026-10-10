package com.example.venue.promotion.source;

import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionPlacementSourceReaderTest {

    @Mock private PromotionCampaignMapper campaignMapper;
    @Mock private MemberAccountMapper memberAccountMapper;

    private PromotionPlacementSourceReader reader() {
        return new PromotionPlacementSourceReader(campaignMapper, memberAccountMapper);
    }

    private PromotionCampaign campaign(Long id, String status, String memberLevelCode,
                                       Long venueId, Integer deleted, LocalDateTime startTime, LocalDateTime endTime) {
        PromotionCampaign c = new PromotionCampaign();
        c.id = id;
        c.campaignName = "夏季促销";
        c.campaignType = "DISCOUNT";
        c.discountType = "PERCENTAGE";
        c.discountValue = new BigDecimal("0.8");
        c.status = status;
        c.memberLevelCode = memberLevelCode;
        c.venueId = venueId;
        c.deleted = deleted;
        c.startTime = startTime;
        c.endTime = endTime;
        c.createTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        c.updateTime = LocalDateTime.of(2030, 1, 1, 0, 0);
        return c;
    }

    private LocalDateTime now = LocalDateTime.now();

    @Test
    void readsActiveCampaignWithinTimeWindow() {
        when(campaignMapper.selectById(1L)).thenReturn(
            campaign(1L, "ACTIVE", null, 100L, 0, now.minusHours(1), now.plusHours(1)));

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sourceType()).isEqualTo(PlacementSourceType.PRICE_PROMOTION);
        assertThat(snapshot.title()).isEqualTo("夏季促销");
        assertThat(snapshot.deepLink()).isEqualTo("/venues/100");
        assertThat(snapshot.imageUrl()).isNull();
    }

    @Test
    void deepLinkFallsBackToVenuesListWhenNoVenueId() {
        when(campaignMapper.selectById(1L)).thenReturn(
            campaign(1L, "ACTIVE", null, null, 0, now.minusHours(1), now.plusHours(1)));

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);
        assertThat(snapshot.deepLink()).isEqualTo("/venues");
    }

    @Test
    void returnsNullForInactiveStatus() {
        when(campaignMapper.selectById(2L)).thenReturn(
            campaign(2L, "PAUSED", null, 100L, 0, now.minusHours(1), now.plusHours(1)));

        assertThat(reader().readForUser(2L, 10L)).isNull();
    }

    @Test
    void returnsNullForDeletedCampaign() {
        when(campaignMapper.selectById(3L)).thenReturn(
            campaign(3L, "ACTIVE", null, 100L, 1, now.minusHours(1), now.plusHours(1)));

        assertThat(reader().readForUser(3L, 10L)).isNull();
    }

    @Test
    void returnsNullForExpiredCampaign() {
        when(campaignMapper.selectById(4L)).thenReturn(
            campaign(4L, "ACTIVE", null, 100L, 0, now.minusHours(2), now.minusHours(1)));

        assertThat(reader().readForUser(4L, 10L)).isNull();
    }

    @Test
    void returnsNullForNotYetStartedCampaign() {
        when(campaignMapper.selectById(5L)).thenReturn(
            campaign(5L, "ACTIVE", null, 100L, 0, now.plusHours(1), now.plusHours(2)));

        assertThat(reader().readForUser(5L, 10L)).isNull();
    }

    @Test
    void memberLevelCampaignMatchesUserWithSameLevel() {
        when(campaignMapper.selectById(6L)).thenReturn(
            campaign(6L, "ACTIVE", "GOLD", 100L, 0, now.minusHours(1), now.plusHours(1)));
        MemberAccount account = new MemberAccount();
        account.levelCode = "GOLD";
        when(memberAccountMapper.findByUserId(10L)).thenReturn(account);

        PlacementSourceSnapshot snapshot = reader().readForUser(6L, 10L);
        assertThat(snapshot).isNotNull();
    }

    @Test
    void memberLevelCampaignRejectsUserWithDifferentLevel() {
        when(campaignMapper.selectById(7L)).thenReturn(
            campaign(7L, "ACTIVE", "GOLD", 100L, 0, now.minusHours(1), now.plusHours(1)));
        MemberAccount account = new MemberAccount();
        account.levelCode = "BASIC";
        when(memberAccountMapper.findByUserId(10L)).thenReturn(account);

        assertThat(reader().readForUser(7L, 10L)).isNull();
    }

    @Test
    void memberLevelCampaignRejectsUserWithNoMemberAccount() {
        when(campaignMapper.selectById(8L)).thenReturn(
            campaign(8L, "ACTIVE", "GOLD", 100L, 0, now.minusHours(1), now.plusHours(1)));
        when(memberAccountMapper.findByUserId(10L)).thenReturn(null);

        assertThat(reader().readForUser(8L, 10L)).isNull();
    }

    @Test
    void readForPreviewSkipsMemberLevelCheck() {
        when(campaignMapper.selectById(9L)).thenReturn(
            campaign(9L, "ACTIVE", "GOLD", 100L, 0, now.minusHours(1), now.plusHours(1)));

        // Preview does not check member level — operator previews regardless of their own level
        PlacementSourceSnapshot snapshot = reader().readForPreview(9L);
        assertThat(snapshot).isNotNull();
    }

    @Test
    void returnsNullForMissingCampaign() {
        when(campaignMapper.selectById(99L)).thenReturn(null);
        assertThat(reader().readForUser(99L, 10L)).isNull();
    }

    @Test
    void returnsNullForInvalidSourceId() {
        assertThat(reader().readForUser(null, 10L)).isNull();
        assertThat(reader().readForUser(0L, 10L)).isNull();
    }

    @Test
    void summaryIncludesCampaignTypeAndDiscount() {
        when(campaignMapper.selectById(1L)).thenReturn(
            campaign(1L, "ACTIVE", null, 100L, 0, now.minusHours(1), now.plusHours(1)));

        PlacementSourceSnapshot snapshot = reader().readForUser(1L, 10L);
        assertThat(snapshot.summary()).contains("DISCOUNT");
        assertThat(snapshot.summary()).contains("PERCENTAGE");
    }
}
