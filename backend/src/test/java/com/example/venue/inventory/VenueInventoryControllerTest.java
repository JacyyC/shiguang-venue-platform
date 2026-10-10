package com.example.venue.inventory;

import com.example.venue.common.Result;
import com.example.venue.inventory.controller.VenueInventoryController;
import com.example.venue.inventory.dto.SaveBusinessCalendarRequest;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueBusinessCalendar;
import com.example.venue.inventory.service.VenueInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VenueInventoryControllerTest {
    @Test
    void controllerUsesFineGrainedInventoryPermissions() throws Exception {
        Method calendar = VenueInventoryController.class
            .getDeclaredMethod("calendar", Long.class, Long.class, String.class);
        assertThat(calendar.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:inventory:view')");

        Method blocks = VenueInventoryController.class
            .getDeclaredMethod("blocks", Long.class, Long.class, String.class);
        assertThat(blocks.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:inventory:view')");

        Method createBlock = VenueInventoryController.class
            .getDeclaredMethod("createBlock", SaveUnavailableBlockRequest.class);
        assertThat(createBlock.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:calendar:manage')");

        Method saveCalendar = VenueInventoryController.class
            .getDeclaredMethod("saveCalendar", SaveBusinessCalendarRequest.class);
        assertThat(saveCalendar.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:calendar:manage')");

        Method cancelBlock = VenueInventoryController.class
            .getDeclaredMethod("cancelBlock", Long.class, Map.class);
        assertThat(cancelBlock.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('venue:calendar:manage')");
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarResponseIncludesExistingBusinessCalendar() {
        VenueInventoryService inventoryService = mock(VenueInventoryService.class);
        VenueInventoryController controller = new VenueInventoryController(inventoryService);
        LocalDate date = LocalDate.parse("2026-07-02");
        VenueBusinessCalendar calendar = new VenueBusinessCalendar();
        calendar.id = 301L;
        calendar.venueId = 20L;
        calendar.calendarDate = date;
        calendar.businessStatus = "SPECIAL_HOURS";
        calendar.reasonText = "暑期开放";
        when(inventoryService.dailyInventory(20L, 10L, date, true)).thenReturn(List.of());
        when(inventoryService.businessCalendar(20L, date)).thenReturn(calendar);

        Result<Map<String, Object>> result = controller.calendar(20L, 10L, "2026-07-02");

        assertThat(result.getData()).containsEntry("businessCalendar", calendar);
        verify(inventoryService).businessCalendar(20L, date);
    }
}
