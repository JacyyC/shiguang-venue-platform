package com.example.venue.funnel;

import com.example.venue.common.PageQuery;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.funnel.dto.FunnelOverviewQuery;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestParameterPropertyValues;
import org.springframework.web.bind.WebDataBinder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四批四域查询参数绑定合同（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-F16）。
 *
 * <p>Spring MVC 的 {@code @ModelAttribute} 绑定只写入可写 JavaBean 属性，纯 public 字段会被静默丢弃
 * （同第三批 B3-T56 的 {@code SiteOpsQuery}/{@code AssetQuery} 缺陷类）。S02 实测本批只有
 * {@link FunnelOverviewQuery} 中招：四个参数全部为 null，漏斗的日期窗、场馆范围与来源渠道过滤
 * 从未到达服务端。此处用 MVC 实际使用的绑定路径锁定四个域的全部查询 DTO：
 * 漏斗必须真正写入，其余三个域作为对照，防止任一 DTO 日后退回不可绑定状态。</p>
 */
class FunnelOverviewQueryBindingTest {

    @Test
    void funnelOverviewQueryBindsDateWindowVenueAndSourceChannel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("startDate", "2026-09-06");
        request.setParameter("endDate", "2026-09-12");
        request.setParameter("venueId", "20");
        request.setParameter("sourceChannel", "WEB");

        FunnelOverviewQuery query = new FunnelOverviewQuery();
        bind(query, request);

        assertThat(query.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(query.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(query.getVenueId()).isEqualTo(20L);
        assertThat(query.getSourceChannel()).isEqualTo("WEB");
    }

    @Test
    void funnelOverviewQueryLeavesUnsetParametersNullForServiceDefaults() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        FunnelOverviewQuery query = new FunnelOverviewQuery();
        bind(query, request);

        // 未传参时必须保持 null，由 normalizeWindow 回落「近 7 天」、由 resolveScope 回落可管理场馆集合。
        assertThat(query.getStartDate()).isNull();
        assertThat(query.getEndDate()).isNull();
        assertThat(query.getVenueId()).isNull();
        assertThat(query.getSourceChannel()).isNull();
    }

    @Test
    void siblingBatch04QueryDtosKeepBindingTheirFilters() {
        MockHttpServletRequest pageRequest = new MockHttpServletRequest();
        pageRequest.setParameter("pageNum", "3");
        pageRequest.setParameter("pageSize", "50");
        PageQuery page = new PageQuery();
        bind(page, pageRequest);
        assertThat(page.getPageNum()).isEqualTo(3L);
        assertThat(page.getPageSize()).isEqualTo(50L);

        MockHttpServletRequest reportRequest = new MockHttpServletRequest();
        reportRequest.setParameter("status", "OPEN");
        reportRequest.setParameter("targetType", "POST");
        reportRequest.setParameter("cursorId", "801");
        reportRequest.setParameter("limit", "50");
        CommunityRequests.AdminReportQuery reports = new CommunityRequests.AdminReportQuery();
        bind(reports, reportRequest);
        assertThat(reports.getStatus()).isEqualTo("OPEN");
        assertThat(reports.getTargetType()).isEqualTo("POST");
        assertThat(reports.getCursorId()).isEqualTo(801L);
        assertThat(reports.getLimit()).isEqualTo(50);

        MockHttpServletRequest eventRequest = new MockHttpServletRequest();
        eventRequest.setParameter("subjectType", "POST");
        eventRequest.setParameter("subjectId", "322");
        eventRequest.setParameter("reportId", "804");
        eventRequest.setParameter("cursorId", "70");
        eventRequest.setParameter("limit", "50");
        CommunityRequests.AdminEventQuery events = new CommunityRequests.AdminEventQuery();
        bind(events, eventRequest);
        assertThat(events.getSubjectType()).isEqualTo("POST");
        assertThat(events.getSubjectId()).isEqualTo(322L);
        assertThat(events.getReportId()).isEqualTo(804L);
        assertThat(events.getCursorId()).isEqualTo(70L);
        assertThat(events.getLimit()).isEqualTo(50);
    }

    private void bind(Object target, MockHttpServletRequest request) {
        WebDataBinder binder = new WebDataBinder(target);
        binder.setConversionService(new DefaultFormattingConversionService());
        binder.bind(new ServletRequestParameterPropertyValues(request));
    }
}
