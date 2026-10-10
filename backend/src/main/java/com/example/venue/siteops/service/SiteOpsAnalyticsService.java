package com.example.venue.siteops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.siteops.entity.AssetItem;
import com.example.venue.siteops.entity.AssetRepairRecord;
import com.example.venue.siteops.entity.SiteIncidentLog;
import com.example.venue.siteops.entity.SiteShift;
import com.example.venue.siteops.entity.SiteTask;
import com.example.venue.siteops.mapper.AssetItemMapper;
import com.example.venue.siteops.mapper.AssetRepairRecordMapper;
import com.example.venue.siteops.mapper.SiteIncidentLogMapper;
import com.example.venue.siteops.mapper.SiteShiftMapper;
import com.example.venue.siteops.mapper.SiteTaskMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SiteOpsAnalyticsService {
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_TODO = "TODO";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_EXCEPTION = "EXCEPTION";
    private static final String STATUS_EXCEPTION_COMPLETED = "EXCEPTION_COMPLETED";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_REPAIRING = "REPAIRING";
    private static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    private static final List<String> DATA_GAPS = List.of(
        "No IoT provider is integrated.",
        "No supplier billing provider or real repair payment record is integrated."
    );
    private static final List<String> EVIDENCE_REFS = List.of("site.ops_asset:$");

    private final SiteShiftMapper shiftMapper;
    private final SiteTaskMapper taskMapper;
    private final SiteIncidentLogMapper incidentMapper;
    private final AssetItemMapper assetMapper;
    private final AssetRepairRecordMapper repairMapper;
    private final VenueAccessService venueAccessService;

    public SiteOpsAnalyticsService(SiteShiftMapper shiftMapper,
                                   SiteTaskMapper taskMapper,
                                   SiteIncidentLogMapper incidentMapper,
                                   AssetItemMapper assetMapper,
                                   AssetRepairRecordMapper repairMapper,
                                   VenueAccessService venueAccessService) {
        this.shiftMapper = shiftMapper;
        this.taskMapper = taskMapper;
        this.incidentMapper = incidentMapper;
        this.assetMapper = assetMapper;
        this.repairMapper = repairMapper;
        this.venueAccessService = venueAccessService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> context(Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        Long venueId = longValue(safeInput.get("venueId"));
        Long spaceId = longValue(safeInput.get("spaceId"));
        Scope scope = resolveScope(venueId);
        if (scope.empty()) {
            return contextResult(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0L);
        }

        long shiftTotal = countShifts(scope, spaceId, null);
        long inProgress = countShifts(scope, spaceId, STATUS_IN_PROGRESS);
        long taskTotal = countTasks(scope, spaceId, (String) null);
        long openTasks = countTasks(scope, spaceId, List.of(STATUS_TODO, STATUS_IN_PROGRESS, STATUS_PENDING));
        long exceptionTasks = countTasks(scope, spaceId, List.of(STATUS_EXCEPTION, STATUS_EXCEPTION_COMPLETED));
        long incidentTotal = countIncidents(scope, spaceId, (String) null);
        long openIncidents = countIncidents(scope, spaceId, List.of(STATUS_OPEN, STATUS_IN_PROGRESS));
        long assetTotal = countAssets(scope, spaceId, null);
        long repairing = countAssets(scope, spaceId, STATUS_REPAIRING);
        long outOfService = countAssets(scope, spaceId, STATUS_OUT_OF_SERVICE);
        BigDecimal actualCostAmount = sumActualCostAmount(scope, spaceId);
        long linkedBlockCount = countLinkedUnavailableBlocks(scope, spaceId);

        return contextResult(shiftTotal, inProgress, taskTotal, openTasks, exceptionTasks, incidentTotal, openIncidents,
            assetTotal, repairing, outOfService, actualCostAmount, linkedBlockCount);
    }

    private Map<String, Object> contextResult(long shiftTotal, long inProgress, long taskTotal, long openTasks,
                                              long exceptionTasks, long incidentTotal, long openIncidents,
                                              long assetTotal, long repairing, long outOfService,
                                              BigDecimal actualCostAmount, long linkedBlockCount) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("shiftSummary", Map.of("total", shiftTotal, "inProgress", inProgress));
        data.put("taskSummary", Map.of("total", taskTotal, "open", openTasks, "exceptions", exceptionTasks));
        data.put("incidentSummary", Map.of("total", incidentTotal, "open", openIncidents));
        data.put("assetSummary", Map.of("total", assetTotal, "repairing", repairing, "outOfService", outOfService));
        data.put("maintenanceCostSummary", Map.of("actualCostAmount", actualCostAmount));
        data.put("inventoryImpact", Map.of("linkedUnavailableBlocks", linkedBlockCount));
        data.put("dataGaps", DATA_GAPS);
        data.put("evidenceRefs", EVIDENCE_REFS);
        return data;
    }

    private Scope resolveScope(Long venueId) {
        if (venueId != null) {
            venueAccessService.requireVenueManageAccess(venueId);
            return new Scope(venueId, null, false);
        }
        List<Long> manageableVenueIds = venueAccessService.manageableVenueIds();
        if (manageableVenueIds != null && manageableVenueIds.isEmpty()) {
            return new Scope(null, manageableVenueIds, true);
        }
        return new Scope(null, manageableVenueIds, false);
    }

    private long countShifts(Scope scope, Long spaceId, String status) {
        QueryWrapper<SiteShift> wrapper = scoped(new QueryWrapper<SiteShift>().eq("deleted", 0), scope, spaceId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return count(shiftMapper.selectCount(wrapper));
    }

    private long countTasks(Scope scope, Long spaceId, String status) {
        QueryWrapper<SiteTask> wrapper = scoped(new QueryWrapper<SiteTask>().eq("deleted", 0), scope, spaceId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return count(taskMapper.selectCount(wrapper));
    }

    private long countTasks(Scope scope, Long spaceId, List<String> statuses) {
        QueryWrapper<SiteTask> wrapper = scoped(new QueryWrapper<SiteTask>().eq("deleted", 0), scope, spaceId);
        wrapper.in("status", statuses);
        return count(taskMapper.selectCount(wrapper));
    }

    private long countIncidents(Scope scope, Long spaceId, String status) {
        QueryWrapper<SiteIncidentLog> wrapper = scoped(new QueryWrapper<SiteIncidentLog>().eq("deleted", 0), scope, spaceId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return count(incidentMapper.selectCount(wrapper));
    }

    private long countIncidents(Scope scope, Long spaceId, List<String> statuses) {
        QueryWrapper<SiteIncidentLog> wrapper = scoped(new QueryWrapper<SiteIncidentLog>().eq("deleted", 0), scope, spaceId);
        wrapper.in("status", statuses);
        return count(incidentMapper.selectCount(wrapper));
    }

    private long countAssets(Scope scope, Long spaceId, String status) {
        QueryWrapper<AssetItem> wrapper = scoped(new QueryWrapper<AssetItem>().eq("deleted", 0), scope, spaceId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return count(assetMapper.selectCount(wrapper));
    }

    private long countLinkedUnavailableBlocks(Scope scope, Long spaceId) {
        QueryWrapper<AssetRepairRecord> wrapper = scoped(new QueryWrapper<AssetRepairRecord>()
            .eq("deleted", 0)
            .isNotNull("unavailable_block_id"), scope, spaceId);
        return count(repairMapper.selectCount(wrapper));
    }

    private BigDecimal sumActualCostAmount(Scope scope, Long spaceId) {
        QueryWrapper<AssetRepairRecord> wrapper = scoped(new QueryWrapper<AssetRepairRecord>()
            .select("coalesce(sum(actual_cost_amount), 0) as actual_cost_amount")
            .eq("deleted", 0), scope, spaceId);
        List<Map<String, Object>> rows = repairMapper.selectMaps(wrapper);
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return decimalValue(rows.get(0).values().stream().findFirst().orElse(null));
    }

    private <T> QueryWrapper<T> scoped(QueryWrapper<T> wrapper, Scope scope, Long spaceId) {
        if (scope.venueId() != null) {
            wrapper.eq("venue_id", scope.venueId());
        } else if (scope.venueIds() != null) {
            wrapper.in("venue_id", scope.venueIds());
        }
        if (spaceId != null) {
            wrapper.eq("space_id", spaceId);
        }
        return wrapper;
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value != null && !value.toString().isBlank()) {
            return new BigDecimal(value.toString());
        }
        return BigDecimal.ZERO;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !value.toString().isBlank()) {
            return Long.parseLong(value.toString());
        }
        return null;
    }

    private record Scope(Long venueId, List<Long> venueIds, boolean empty) {
    }
}
