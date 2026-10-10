package com.example.venue.venue;

import com.example.venue.common.Result;
import com.example.venue.common.PageResult;
import com.example.venue.resource.dto.ResourceRuleChangeRequest;
import com.example.venue.resource.dto.ResourceRuleResponse;
import com.example.venue.venue.controller.VenueController;
import com.example.venue.venue.dto.VenueFacetsResponse;
import com.example.venue.venue.dto.VenueSpaceResponse;
import com.example.venue.venue.dto.VenueListItemResponse;
import com.example.venue.venue.dto.VenueQuery;
import com.example.venue.venue.service.VenueService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.DataBinder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VenueControllerTest {
    @Test
    void publicListQueryBindsSearchAndFilterParameters() {
        VenueQuery query = new VenueQuery();
        DataBinder binder = new DataBinder(query);

        binder.bind(new MutablePropertyValues(Map.of(
            "keyword", "网球",
            "area", "东区",
            "type", "SPORTS",
            "status", "DISABLED",
            "pageNum", "2",
            "pageSize", "20"
        )));

        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(query.keyword).isEqualTo("网球");
        assertThat(query.area).isEqualTo("东区");
        assertThat(query.type).isEqualTo("SPORTS");
        assertThat(query.status).isEqualTo("DISABLED");
        assertThat(query.getPageNum()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(20);
    }

    @Test
    void listReturnsDecisionReadyVenueItemsWithoutChangingThePublicRoute() {
        VenueService service = mock(VenueService.class);
        VenueController controller = new VenueController(service);
        VenueQuery query = new VenueQuery();
        VenueListItemResponse item = new VenueListItemResponse(
            2L, "星河体育馆", "东区 1 号", "东区", null, null, null,
            "综合球馆", null, "ACTIVE", null, null, 0,
            new BigDecimal("4.75"), 12L, new BigDecimal("68.00"), 6L,
            List.of("BADMINTON", "TENNIS"));
        PageResult<VenueListItemResponse> page = PageResult.of(List.of(item), 1, 1, 8);
        when(service.list(query)).thenReturn(page);

        Result<PageResult<VenueListItemResponse>> result = controller.list(query);

        assertThat(result.getData().getRecords()).containsExactly(item);
        verify(service).list(query);
    }

    @Test
    void facetsExposesAreaAndSpaceTypeOptionsOnThePublicRoute() {
        VenueService service = mock(VenueService.class);
        VenueController controller = new VenueController(service);
        when(service.facets()).thenReturn(new VenueFacetsResponse(List.of("东区", "西区"), List.of("BADMINTON")));

        Result<VenueFacetsResponse> result = controller.facets();

        assertThat(result.getData().areas()).containsExactly("东区", "西区");
        assertThat(result.getData().spaceTypes()).containsExactly("BADMINTON");
        verify(service).facets();
    }

    @Test
    void resourceRuleEndpointsUseSpaceManagementPermission() throws Exception {
        Method read = VenueController.class.getMethod("resourceRule", Long.class);
        Method configure = VenueController.class.getMethod(
            "configureResourceRule", Long.class, ResourceRuleChangeRequest.class);

        assertThat(read.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:space:manage')");
        assertThat(configure.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:space:manage')");
    }

    @Test
    void spacesReturnsResourceAwareResponses() {
        VenueService service = mock(VenueService.class);
        VenueController controller = new VenueController(service);
        VenueSpaceResponse space = new VenueSpaceResponse(
            10L, 2L, "一号自习室", "STUDY", "S-01", 20, null,
            null, null, "ACTIVE", null, null,
            new ResourceRuleResponse(null, 10L, "STUDY_ROOM", "CAPACITY", 20,
                20, 1, "ACTIVE", null, List.of()));
        when(service.spaces(2L)).thenReturn(List.of(space));

        Result<List<VenueSpaceResponse>> result = controller.spaces(2L);

        assertThat(result.getData()).containsExactly(space);
        verify(service).spaces(2L);
    }

    @Test
    void configureResourceRuleDelegatesToScopedService() {
        VenueService service = mock(VenueService.class);
        VenueController controller = new VenueController(service);
        ResourceRuleChangeRequest request = new ResourceRuleChangeRequest(
            "STUDY_ROOM", "CAPACITY", 12, List.of(), "调整共享容量");
        ResourceRuleResponse response = new ResourceRuleResponse(
            8L, 10L, "STUDY_ROOM", "CAPACITY", 12, 20, 2, "ACTIVE", null, List.of());
        when(service.configureResourceRule(10L, request)).thenReturn(response);

        Result<ResourceRuleResponse> result = controller.configureResourceRule(10L, request);

        assertThat(result.getData()).isSameAs(response);
        verify(service).configureResourceRule(10L, request);
    }
}
