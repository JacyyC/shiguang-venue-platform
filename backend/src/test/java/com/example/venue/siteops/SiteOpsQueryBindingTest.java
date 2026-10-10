package com.example.venue.siteops;

import com.example.venue.siteops.dto.AssetQuery;
import com.example.venue.siteops.dto.SiteOpsQuery;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestParameterPropertyValues;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.format.support.DefaultFormattingConversionService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 现场与资产列表的查询参数绑定合同（SHIGUANG-BATCH-03-REFACTOR-20260914 S03）。
 *
 * Spring MVC 的 {@code @ModelAttribute} 绑定只写入可写 JavaBean 属性，纯 public 字段会被静默丢弃；
 * 第三批「当班/本班」范围依赖 venueId、shiftId、assetId、status 与 date 真正到达服务端，
 * 因此这里用 MVC 实际使用的绑定路径锁定这些参数，防止退回「过滤条件不生效」的基线状态。
 */
class SiteOpsQueryBindingTest {

    @Test
    void siteOpsQueryBindsVenueSpaceShiftAssetStatusAndOperationalDay() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("venueId", "20");
        request.setParameter("spaceId", "31");
        request.setParameter("shiftId", "41");
        request.setParameter("assetId", "501");
        request.setParameter("status", "IN_PROGRESS");
        request.setParameter("date", "2026-09-13");
        request.setParameter("pageNum", "2");
        request.setParameter("pageSize", "50");

        SiteOpsQuery query = new SiteOpsQuery();
        bind(query, request);

        assertThat(query.venueId).isEqualTo(20L);
        assertThat(query.spaceId).isEqualTo(31L);
        assertThat(query.shiftId).isEqualTo(41L);
        assertThat(query.assetId).isEqualTo(501L);
        assertThat(query.status).isEqualTo("IN_PROGRESS");
        assertThat(query.date).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(query.getPageNum()).isEqualTo(2L);
        assertThat(query.getPageSize()).isEqualTo(50L);
    }

    @Test
    void assetQueryBindsVenueSpaceAssetAndStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("venueId", "20");
        request.setParameter("spaceId", "31");
        request.setParameter("assetId", "501");
        request.setParameter("status", "INSPECTION_DUE");

        AssetQuery query = new AssetQuery();
        bind(query, request);

        assertThat(query.venueId).isEqualTo(20L);
        assertThat(query.spaceId).isEqualTo(31L);
        assertThat(query.assetId).isEqualTo(501L);
        assertThat(query.status).isEqualTo("INSPECTION_DUE");
    }

    private void bind(Object target, MockHttpServletRequest request) {
        WebDataBinder binder = new WebDataBinder(target);
        binder.setConversionService(new DefaultFormattingConversionService());
        binder.bind(new ServletRequestParameterPropertyValues(request));
    }
}
