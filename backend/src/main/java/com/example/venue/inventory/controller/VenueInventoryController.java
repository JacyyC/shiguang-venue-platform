package com.example.venue.inventory.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.Result;
import com.example.venue.inventory.dto.InventorySlot;
import com.example.venue.inventory.dto.InventorySummary;
import com.example.venue.inventory.dto.SaveBusinessCalendarRequest;
import com.example.venue.inventory.dto.SaveUnavailableBlockRequest;
import com.example.venue.inventory.entity.VenueBusinessCalendar;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import com.example.venue.inventory.service.VenueInventoryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory")
public class VenueInventoryController {
    private final VenueInventoryService inventoryService;

    public VenueInventoryController(VenueInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('venue:inventory:view')")
    public Result<Map<String, Object>> calendar(@RequestParam Long venueId,
                                                @RequestParam Long spaceId,
                                                @RequestParam String date) {
        LocalDate inventoryDate = LocalDate.parse(date);
        List<InventorySlot> slots = inventoryService.dailyInventory(venueId, spaceId, inventoryDate, true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slots", slots);
        payload.put("summary", summarize(venueId, spaceId, inventoryDate, slots));
        payload.put("businessCalendar", inventoryService.businessCalendar(venueId, inventoryDate));
        return Result.ok(payload);
    }

    @GetMapping("/blocks")
    @PreAuthorize("hasAuthority('venue:inventory:view')")
    public Result<List<VenueUnavailableBlock>> blocks(@RequestParam Long venueId,
                                                      @RequestParam(required = false) Long spaceId,
                                                      @RequestParam String date) {
        return Result.ok(inventoryService.blocks(venueId, spaceId, LocalDate.parse(date)));
    }

    @AuditLog(module = "运营日历", action = "保存营业日历")
    @RequestMapping(value = "/calendar", method = {RequestMethod.PUT, RequestMethod.POST})
    @PreAuthorize("hasAuthority('venue:calendar:manage')")
    public Result<VenueBusinessCalendar> saveCalendar(@Valid @RequestBody SaveBusinessCalendarRequest request) {
        return Result.ok(inventoryService.saveCalendar(request));
    }

    @AuditLog(module = "运营日历", action = "新增不可售时段")
    @PostMapping("/blocks")
    @PreAuthorize("hasAuthority('venue:calendar:manage')")
    public Result<VenueUnavailableBlock> createBlock(@Valid @RequestBody SaveUnavailableBlockRequest request) {
        return Result.ok(inventoryService.createBlock(request));
    }

    @AuditLog(module = "运营日历", action = "取消不可售时段")
    @PutMapping("/blocks/{id}/cancel")
    @PreAuthorize("hasAuthority('venue:calendar:manage')")
    public Result<Void> cancelBlock(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? "取消不可售" : body.getOrDefault("reason", "取消不可售");
        inventoryService.cancelBlock(id, reason);
        return Result.ok();
    }

    private InventorySummary summarize(Long venueId, Long spaceId, LocalDate date, List<InventorySlot> slots) {
        InventorySummary summary = new InventorySummary();
        summary.venueId = venueId;
        summary.spaceId = spaceId;
        summary.date = date;
        for (InventorySlot slot : slots) {
            int minutes = (int) ChronoUnit.MINUTES.between(slot.startTime, slot.endTime);
            switch (slot.status) {
                case "AVAILABLE" -> {
                    summary.availableSlots += 1;
                    summary.availableMinutes += minutes;
                }
                case "RESERVED" -> {
                    summary.reservedSlots += 1;
                    summary.reservedMinutes += minutes;
                }
                case "MANUAL_HOLD" -> {
                    summary.manualHoldSlots += 1;
                    summary.manualHoldMinutes += minutes;
                }
                case "CLOSED" -> {
                    summary.closedSlots += 1;
                    summary.closedMinutes += minutes;
                }
                case "BUFFER" -> {
                    summary.bufferSlots += 1;
                    summary.bufferMinutes += minutes;
                }
                default -> {
                    summary.unavailableSlots += 1;
                    summary.unavailableMinutes += minutes;
                }
            }
        }
        return summary;
    }
}
