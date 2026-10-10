package com.example.venue.notice;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NoticeExperimentResponse;
import com.example.venue.notice.dto.SaveNoticeExperimentRequest;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.provider.NotificationProvider;
import com.example.venue.notice.provider.NotificationProviderDescriptor;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.notice.service.NoticeExperimentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeExperimentServiceTest {
    @Mock
    private NoticeExperimentMapper experimentMapper;
    @Mock
    private NoticeExperimentVariantMapper variantMapper;
    @Mock
    private NotificationProviderRouter providerRouter;

    private NoticeExperimentService service;

    @BeforeEach
    void setUp() {
        NotificationProvider defaultProvider = mock(NotificationProvider.class);
        lenient().when(defaultProvider.descriptor()).thenReturn(inAppDescriptor());
        lenient().when(providerRouter.require("DEV", "IN_APP_MESSAGE")).thenReturn(defaultProvider);
        service = new NoticeExperimentService(experimentMapper, variantMapper, providerRouter);
        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createExperimentPersistsVariantsAndRejectsInvalidAllocation() {
        SaveNoticeExperimentRequest request = new SaveNoticeExperimentRequest();
        request.name = "暑期活动通知";
        request.targetMetric = "READ_RATE";
        request.sampleScope = "ALL_USERS";
        request.variants = List.of(variant("A", "标题A", "内容A", 50),
            variant("B", "标题B", "内容B", 50));
        when(experimentMapper.insert(any(NoticeExperiment.class))).thenAnswer(invocation -> {
            NoticeExperiment experiment = invocation.getArgument(0);
            experiment.id = 11L;
            return 1;
        });

        NoticeExperimentResponse response = service.create(request);

        assertThat(response.id).isEqualTo(11L);
        assertThat(response.name).isEqualTo("暑期活动通知");
        assertThat(response.status).isEqualTo("RUNNING");
        assertThat(response.targetMetric).isEqualTo("READ_RATE");
        assertThat(response.sampleScope).isEqualTo("ALL_USERS");
        assertThat(response.variants).extracting(variant -> variant.variantCode)
            .containsExactly("A", "B");
        verify(experimentMapper).insert(argThat((NoticeExperiment experiment) -> "RUNNING".equals(experiment.status)
            && Long.valueOf(1L).equals(experiment.ownerId)
            && experiment.experimentNo != null
            && experiment.startTime != null
            && Integer.valueOf(0).equals(experiment.deleted)));
        verify(variantMapper, times(2))
            .insert(argThat((NoticeExperimentVariant variant) -> Long.valueOf(11L).equals(variant.experimentId)));

        SaveNoticeExperimentRequest invalid = new SaveNoticeExperimentRequest();
        invalid.name = "错误实验";
        invalid.targetMetric = "READ_RATE";
        invalid.sampleScope = "ALL_USERS";
        invalid.variants = List.of(variant("A", "标题A", "内容A", 70),
            variant("B", "标题B", "内容B", 20));

        assertThatThrownBy(() -> service.create(invalid))
            .isInstanceOf(BusinessException.class)
            .hasMessage("实验版本流量占比合计必须为 100");
    }

    @Test
    void createExperimentRejectsAllocationPercentOutsideRangeEvenWhenTotalIsOneHundred() {
        SaveNoticeExperimentRequest request = new SaveNoticeExperimentRequest();
        request.name = "异常占比实验";
        request.targetMetric = "READ_RATE";
        request.sampleScope = "ALL_USERS";
        request.variants = List.of(variant("A", "标题A", "内容A", -10),
            variant("B", "标题B", "内容B", 110));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("实验版本流量占比必须在 0 到 100 之间");
    }

    @Test
    void experimentsValidateProviderContextWithoutBindingDefinitionsToProvider() {
        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.providerEnv = " sandbox ";
        query.providerName = " fake_message ";
        NotificationProvider provider = mock(NotificationProvider.class);
        when(providerRouter.require("SANDBOX", "FAKE_MESSAGE")).thenReturn(provider);
        NoticeExperiment experiment = new NoticeExperiment();
        experiment.id = 31L;
        experiment.name = "Provider 无关实验定义";
        experiment.status = "RUNNING";
        when(experimentMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<NoticeExperiment> page = invocation.getArgument(0);
            page.setRecords(List.of(experiment));
            page.setTotal(1);
            return page;
        });

        var response = service.experiments(query);

        verify(providerRouter).require("SANDBOX", "FAKE_MESSAGE");
        assertThat(response.getRecords()).extracting(record -> record.name)
            .containsExactly("Provider 无关实验定义");
    }

    @Test
    void experimentsRejectUnknownProviderContext() {
        NoticeDeliveryQuery query = providerQuery("SANDBOX", "UNKNOWN");
        when(providerRouter.require("SANDBOX", "UNKNOWN"))
            .thenThrow(BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND"));

        assertThatThrownBy(() -> service.experiments(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
    }

    @Test
    void experimentsRejectDisabledProviderContext() {
        NoticeDeliveryQuery query = providerQuery("SANDBOX", "DISABLED_FAKE");
        when(providerRouter.require("SANDBOX", "DISABLED_FAKE"))
            .thenThrow(BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND"));

        assertThatThrownBy(() -> service.experiments(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
    }

    @Test
    void experimentsRejectPartialProviderContext() {
        NoticeDeliveryQuery envOnly = providerQuery("SANDBOX", null);
        NoticeDeliveryQuery nameOnly = providerQuery(null, "FAKE_MESSAGE");
        when(providerRouter.require("SANDBOX", null))
            .thenThrow(BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND"));
        when(providerRouter.require(null, "FAKE_MESSAGE"))
            .thenThrow(BusinessException.notFound("NOTICE_PROVIDER_NOT_FOUND"));

        assertThatThrownBy(() -> service.experiments(envOnly))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
        assertThatThrownBy(() -> service.experiments(nameOnly))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOTICE_PROVIDER_NOT_FOUND");
    }

    private SaveNoticeExperimentRequest.Variant variant(String code, String title, String content, int allocation) {
        SaveNoticeExperimentRequest.Variant variant = new SaveNoticeExperimentRequest.Variant();
        variant.variantCode = code;
        variant.title = title;
        variant.content = content;
        variant.allocationPercent = allocation;
        return variant;
    }

    private NoticeDeliveryQuery providerQuery(String providerEnv, String providerName) {
        NoticeDeliveryQuery query = new NoticeDeliveryQuery();
        query.providerEnv = providerEnv;
        query.providerName = providerName;
        return query;
    }

    private NotificationProviderDescriptor inAppDescriptor() {
        return new NotificationProviderDescriptor(
            "notice.in_app.dev", "MESSAGE", "DEV", "IN_APP_MESSAGE", "IN_APP",
            "站内消息", "IN_APP_MESSAGE_V1", true);
    }

    private void authenticate() {
        CurrentUser user = new CurrentUser(1L, "admin", Set.of("SYSTEM_ADMIN"), Set.of("notice:analytics"));
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
