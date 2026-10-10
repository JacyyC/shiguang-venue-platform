package com.example.venue.funnel;

import com.example.venue.common.Result;
import com.example.venue.funnel.controller.FunnelController;
import com.example.venue.funnel.dto.FunnelEventBatchRequest;
import com.example.venue.funnel.dto.FunnelEventRequest;
import com.example.venue.funnel.dto.FunnelEventResponse;
import com.example.venue.funnel.dto.FunnelFavoriteResponse;
import com.example.venue.funnel.service.FunnelEventService;
import com.example.venue.funnel.service.FunnelFavoriteService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunnelControllerTest {
    @Test
    void funnelEventEndpointsUseExpectedRoutesAndDelegateToService() throws Exception {
        Method record = FunnelController.class.getDeclaredMethod("record", FunnelEventRequest.class);
        assertThat(record.getAnnotation(PostMapping.class).value()).containsExactly("/api/funnel/events");

        Method recordBatch = FunnelController.class.getDeclaredMethod("recordBatch", FunnelEventBatchRequest.class);
        assertThat(recordBatch.getAnnotation(PostMapping.class).value()).containsExactly("/api/funnel/events/batch");

        FunnelEventService eventService = mock(FunnelEventService.class);
        FunnelFavoriteService favoriteService = mock(FunnelFavoriteService.class);
        FunnelController controller = new FunnelController(eventService, favoriteService);
        FunnelEventRequest request = new FunnelEventRequest();
        FunnelEventBatchRequest batchRequest = new FunnelEventBatchRequest();
        FunnelEventResponse response = eventResponse(1L, "SEARCH_SUBMITTED");
        FunnelEventResponse batchResponse = eventResponse(2L, "VENUE_VIEWED");
        when(eventService.record(request)).thenReturn(response);
        when(eventService.recordBatch(batchRequest)).thenReturn(List.of(batchResponse));

        Result<FunnelEventResponse> result = controller.record(request);
        Result<List<FunnelEventResponse>> batchResult = controller.recordBatch(batchRequest);

        assertThat(result.getData()).isSameAs(response);
        assertThat(batchResult.getData()).containsExactly(batchResponse);
        verify(eventService).record(request);
        verify(eventService).recordBatch(batchRequest);
    }

    @Test
    void favoriteEndpointsUseExpectedRoutesPermissionsAndDelegateToService() throws Exception {
        Method myFavorites = FunnelController.class.getDeclaredMethod("myFavorites");
        assertThat(myFavorites.getAnnotation(GetMapping.class).value()).containsExactly("/api/funnel/favorites/my");
        assertThat(myFavorites.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:favorite:me')");

        Method favorite = FunnelController.class.getDeclaredMethod("favorite", Long.class, FunnelEventRequest.class);
        assertThat(favorite.getAnnotation(PostMapping.class).value())
            .containsExactly("/api/funnel/favorites/venues/{venueId}");
        assertThat(favorite.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:favorite:me')");
        assertThat(favorite.getParameters()[1].getAnnotation(RequestBody.class).required()).isFalse();

        Method cancel = FunnelController.class.getDeclaredMethod("cancelFavorite", Long.class, FunnelEventRequest.class);
        assertThat(cancel.getAnnotation(DeleteMapping.class).value())
            .containsExactly("/api/funnel/favorites/venues/{venueId}");
        assertThat(cancel.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:favorite:me')");
        assertThat(cancel.getParameters()[1].getAnnotation(RequestBody.class).required()).isFalse();

        Method status = FunnelController.class.getDeclaredMethod("favoriteStatus", Long.class);
        assertThat(status.getAnnotation(GetMapping.class).value())
            .containsExactly("/api/funnel/favorites/venues/{venueId}/status");
        assertThat(status.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('funnel:favorite:me')");

        FunnelEventService eventService = mock(FunnelEventService.class);
        FunnelFavoriteService favoriteService = mock(FunnelFavoriteService.class);
        FunnelController controller = new FunnelController(eventService, favoriteService);
        FunnelEventRequest context = new FunnelEventRequest();
        FunnelFavoriteResponse favoriteResponse = favoriteResponse();
        when(favoriteService.myFavorites()).thenReturn(List.of(favoriteResponse));
        when(favoriteService.favoriteVenue(20L, context)).thenReturn(favoriteResponse);
        when(favoriteService.isFavorite(20L)).thenReturn(true);

        Result<List<FunnelFavoriteResponse>> myResult = controller.myFavorites();
        Result<FunnelFavoriteResponse> favoriteResult = controller.favorite(20L, context);
        Result<Void> cancelResult = controller.cancelFavorite(20L, context);
        Result<Map<String, Boolean>> statusResult = controller.favoriteStatus(20L);

        assertThat(myResult.getData()).containsExactly(favoriteResponse);
        assertThat(favoriteResult.getData()).isSameAs(favoriteResponse);
        assertThat(cancelResult.getData()).isNull();
        assertThat(statusResult.getData()).containsEntry("favorite", true);
        verify(favoriteService).myFavorites();
        verify(favoriteService).favoriteVenue(20L, context);
        verify(favoriteService).cancelFavorite(20L, context);
        verify(favoriteService).isFavorite(20L);
    }

    private FunnelEventResponse eventResponse(Long id, String eventName) {
        FunnelEventResponse response = new FunnelEventResponse();
        response.id = id;
        response.eventName = eventName;
        response.eventStage = "SEARCH";
        response.resultStatus = "SUCCESS";
        return response;
    }

    private FunnelFavoriteResponse favoriteResponse() {
        FunnelFavoriteResponse response = new FunnelFavoriteResponse();
        response.id = 301L;
        response.userId = 7L;
        response.venueId = 20L;
        response.status = "ACTIVE";
        return response;
    }
}
