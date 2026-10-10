package com.example.venue.advertising.service;

import com.example.venue.advertising.dto.AdvertisingRequests;
import com.example.venue.advertising.entity.AdvertisingAdvertiser;
import com.example.venue.advertising.entity.AdvertisingAuditEvent;
import com.example.venue.advertising.entity.AdvertisingCampaign;
import com.example.venue.advertising.entity.AdvertisingCreativeVersion;
import com.example.venue.advertising.mapper.AdvertisingAdvertiserMapper;
import com.example.venue.advertising.mapper.AdvertisingAuditEventMapper;
import com.example.venue.advertising.mapper.AdvertisingCampaignMapper;
import com.example.venue.advertising.mapper.AdvertisingCreativeVersionMapper;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvertisingAdminServiceTest {

    @Mock private AdvertisingAdvertiserMapper advertiserMapper;
    @Mock private AdvertisingCampaignMapper campaignMapper;
    @Mock private AdvertisingCreativeVersionMapper creativeMapper;
    @Mock private AdvertisingAuditEventMapper auditMapper;
    @Mock private EntityExistenceService entityExistenceService;
    @Mock private VenueAccessService venueAccessService;

    private AdvertisingAdminService service;

    @BeforeEach
    void setUp() {
        service = new AdvertisingAdminService(
            advertiserMapper, campaignMapper, creativeMapper, auditMapper,
            entityExistenceService, venueAccessService);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAdvertiserWritesIndependentFactAndAudit() {
        authenticate(10L);
        doAnswer(invocation -> {
            AdvertisingAdvertiser advertiser = invocation.getArgument(0);
            advertiser.id = 1L;
            return 1;
        }).when(advertiserMapper).insert(any(AdvertisingAdvertiser.class));

        var response = service.createAdvertiser(
            new AdvertisingRequests.CreateAdvertiserRequest("平台广告主"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.advertiserName()).isEqualTo("平台广告主");
        verify(auditMapper).insert(any(AdvertisingAuditEvent.class));
    }

    @Test
    void createCreativeRejectsNonWhitelistedTargetType() {
        authenticate(10L);
        AdvertisingCampaign campaign = campaign(5L, 1L, null, "APPROVED");
        when(campaignMapper.selectByIdForUpdate(5L)).thenReturn(campaign);

        assertThatThrownBy(() -> service.createCreative(
            new AdvertisingRequests.CreateCreativeRequest(
                5L, 1, "标题", "正文", "VIEW", "EXTERNAL_URL", 99L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("目标类型");

        verify(creativeMapper, never()).insert(any(AdvertisingCreativeVersion.class));
    }

    @Test
    void creatorCannotApproveOwnCampaignAndDifferentReviewerCanApprove() {
        AdvertisingCampaign campaign = campaign(5L, 1L, null, "PENDING_REVIEW");
        campaign.createdBy = 10L;
        when(campaignMapper.selectByIdForUpdate(5L)).thenReturn(campaign);

        authenticate(10L);
        assertThatThrownBy(() -> service.approveCampaign(5L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("创建者不能审核");

        authenticate(11L);
        var response = service.approveCampaign(5L);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(campaign.reviewedBy).isEqualTo(11L);
        verify(campaignMapper).updateById(campaign);
        verify(auditMapper).insert(any(AdvertisingAuditEvent.class));
    }

    @Test
    void campaignCannotPublishBeforeApprovalAndCanPublishAfterApproval() {
        AdvertisingCampaign campaign = campaign(5L, 1L, null, "DRAFT");
        when(campaignMapper.selectByIdForUpdate(5L)).thenReturn(campaign);
        authenticate(11L);

        assertThatThrownBy(() -> service.publishCampaign(5L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("当前状态");

        campaign.status = "APPROVED";
        campaign.reviewedBy = 10L;
        AdvertisingAdvertiser advertiser = advertiser(1L, "ACTIVE");
        when(advertiserMapper.selectById(1L)).thenReturn(advertiser);

        var response = service.publishCampaign(5L);

        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(campaign.publishedBy).isEqualTo(11L);
    }

    @Test
    void creativeLifecycleUsesCampaignApprovalAndAppendsTransitions() {
        authenticate(10L);
        AdvertisingCampaign campaign = campaign(5L, 1L, null, "APPROVED");
        when(campaignMapper.selectByIdForUpdate(5L)).thenReturn(campaign);
        when(campaignMapper.selectById(5L)).thenReturn(campaign);
        doAnswer(invocation -> {
            AdvertisingCreativeVersion creative = invocation.getArgument(0);
            creative.id = 9L;
            return 1;
        }).when(creativeMapper).insert(any(AdvertisingCreativeVersion.class));

        var created = service.createCreative(new AdvertisingRequests.CreateCreativeRequest(
            5L, 1, "场馆优惠", "预约指定场馆可查看活动", "BOOK", "VENUE_LIST", null));
        assertThat(created.status()).isEqualTo("DRAFT");

        AdvertisingCreativeVersion creative = creative(9L, 5L, 10L, "DRAFT");
        when(creativeMapper.selectByIdForUpdate(9L)).thenReturn(creative);
        service.submitCreativeReview(9L);
        assertThat(creative.status).isEqualTo("PENDING_REVIEW");

        authenticate(11L);
        creative.status = "PENDING_REVIEW";
        service.approveCreative(9L);
        assertThat(creative.status).isEqualTo("APPROVED");

        creative.status = "APPROVED";
        when(advertiserMapper.selectById(1L)).thenReturn(advertiser(1L, "ACTIVE"));
        var published = service.publishCreative(9L);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(creative.status).isEqualTo("PUBLISHED");
    }

    @Test
    void venueScopedCampaignRejectsCrossVenueAccess() {
        authenticate(11L, Set.of("VENUE_ADMIN"));
        doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(99L);

        assertThatThrownBy(() -> service.createCampaign(
            new AdvertisingRequests.CreateCampaignRequest(
                1L, 99L, "场馆计划", LocalDateTime.now(), LocalDateTime.now().plusDays(1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理该场馆");

        verify(advertiserMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void transitionRejectsRetiredCampaign() {
        authenticate(11L);
        AdvertisingCampaign campaign = campaign(5L, 1L, null, "RETIRED");
        when(campaignMapper.selectByIdForUpdate(5L)).thenReturn(campaign);

        assertThatThrownBy(() -> service.pauseCampaign(5L, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("当前状态");
    }

    private AdvertisingAdvertiser advertiser(Long id, String status) {
        AdvertisingAdvertiser advertiser = new AdvertisingAdvertiser();
        advertiser.id = id;
        advertiser.advertiserNo = "ADV-ADVER-1";
        advertiser.advertiserName = "广告主";
        advertiser.status = status;
        advertiser.createdBy = 10L;
        return advertiser;
    }

    private AdvertisingCampaign campaign(Long id, Long advertiserId, Long venueId, String status) {
        AdvertisingCampaign campaign = new AdvertisingCampaign();
        campaign.id = id;
        campaign.campaignNo = "ADV-CAMP-1";
        campaign.advertiserId = advertiserId;
        campaign.venueId = venueId;
        campaign.campaignName = "计划";
        campaign.effectiveFrom = LocalDateTime.now();
        campaign.effectiveUntil = LocalDateTime.now().plusDays(1);
        campaign.status = status;
        campaign.createdBy = 10L;
        return campaign;
    }

    private AdvertisingCreativeVersion creative(Long id, Long campaignId, Long creatorId, String status) {
        AdvertisingCreativeVersion creative = new AdvertisingCreativeVersion();
        creative.id = id;
        creative.creativeNo = "ADV-CREAT-1";
        creative.campaignId = campaignId;
        creative.versionNo = 1;
        creative.headline = "标题";
        creative.bodyText = "正文";
        creative.callToAction = "BOOK";
        creative.targetType = "VENUE_LIST";
        creative.status = status;
        creative.createdBy = creatorId;
        return creative;
    }

    private void authenticate(Long userId) {
        authenticate(userId, Set.of("SYSTEM_ADMIN"));
    }

    private void authenticate(Long userId, Set<String> roles) {
        CurrentUser principal = new CurrentUser(userId, "operator-" + userId, roles,
            Set.of("advertising:manage", "advertising:review", "advertising:publish"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("advertising:manage"))));
    }
}
