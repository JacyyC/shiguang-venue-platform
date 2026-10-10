package com.example.venue.checkin;

import com.example.venue.checkin.dto.CheckinAdminQuery;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestParameterPropertyValues;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.format.support.DefaultFormattingConversionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台履约列表的查询参数绑定合同（SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-14）。
 *
 * Spring MVC 的 {@code @ModelAttribute} 绑定只写入可写 JavaBean 属性，纯 public 字段会被静默丢弃；
 * 第三波调查证明八个领域筛选字段从未进入服务端（五个差异化请求 total 恒为 40，实际 SQL 无任何谓词）。
 * 因此这里用 MVC 实际使用的绑定路径（{@link WebDataBinder}）锁定八个领域字段与分页参数，
 * 防止退回「筛选条件不生效、静默忽略 venueId」的基线状态。
 */
class CheckinAdminQueryBindingTest {

    @Test
    void checkinAdminQueryBindsAllEightDomainFieldsAndPaging() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("reservationId", "104");
        request.setParameter("userId", "7");
        request.setParameter("venueId", "20");
        request.setParameter("spaceId", "31");
        request.setParameter("credentialStatus", "ISSUED");
        request.setParameter("attendanceStatus", "CANCELED");
        request.setParameter("checkinStatus", "CHECKED_IN");
        request.setParameter("noShowStatus", "CONFIRMED");
        request.setParameter("pageNum", "3");
        request.setParameter("pageSize", "25");

        CheckinAdminQuery query = new CheckinAdminQuery();
        bind(query, request);

        assertThat(query.reservationId).isEqualTo(104L);
        assertThat(query.userId).isEqualTo(7L);
        assertThat(query.venueId).isEqualTo(20L);
        assertThat(query.spaceId).isEqualTo(31L);
        assertThat(query.credentialStatus).isEqualTo("ISSUED");
        assertThat(query.attendanceStatus).isEqualTo("CANCELED");
        assertThat(query.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(query.noShowStatus).isEqualTo("CONFIRMED");
        assertThat(query.getPageNum()).isEqualTo(3L);
        assertThat(query.getPageSize()).isEqualTo(25L);
    }

    @Test
    void pagingStillBindsWhenDomainFieldsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", "2");
        request.setParameter("pageSize", "50");

        CheckinAdminQuery query = new CheckinAdminQuery();
        bind(query, request);

        assertThat(query.reservationId).isNull();
        assertThat(query.userId).isNull();
        assertThat(query.venueId).isNull();
        assertThat(query.spaceId).isNull();
        assertThat(query.credentialStatus).isNull();
        assertThat(query.attendanceStatus).isNull();
        assertThat(query.checkinStatus).isNull();
        assertThat(query.noShowStatus).isNull();
        assertThat(query.getPageNum()).isEqualTo(2L);
        assertThat(query.getPageSize()).isEqualTo(50L);
    }

    private void bind(Object target, MockHttpServletRequest request) {
        WebDataBinder binder = new WebDataBinder(target);
        binder.setConversionService(new DefaultFormattingConversionService());
        binder.bind(new ServletRequestParameterPropertyValues(request));
    }
}
