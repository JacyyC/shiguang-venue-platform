package com.example.venue.siteops;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.EntityExistenceService;
import com.example.venue.common.PageResult;
import com.example.venue.siteops.dto.SiteCancelRequest;
import com.example.venue.siteops.dto.SiteHandoverRequest;
import com.example.venue.siteops.dto.SiteIncidentResolveRequest;
import com.example.venue.siteops.dto.SiteIncidentRequest;
import com.example.venue.siteops.dto.SiteOpsQuery;
import com.example.venue.siteops.dto.SiteShiftRequest;
import com.example.venue.siteops.dto.SiteTaskCompleteRequest;
import com.example.venue.siteops.dto.SiteTaskRequest;
import com.example.venue.siteops.entity.SiteIncidentLog;
import com.example.venue.siteops.entity.SiteShift;
import com.example.venue.siteops.entity.SiteShiftHandover;
import com.example.venue.siteops.entity.SiteTask;
import com.example.venue.siteops.mapper.SiteIncidentLogMapper;
import com.example.venue.siteops.mapper.SiteShiftHandoverMapper;
import com.example.venue.siteops.mapper.SiteShiftMapper;
import com.example.venue.siteops.mapper.SiteTaskMapper;
import com.example.venue.siteops.service.SiteOpsService;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteOpsServiceTest {
    @Mock
    private SiteShiftMapper shiftMapper;
    @Mock
    private SiteTaskMapper taskMapper;
    @Mock
    private SiteShiftHandoverMapper handoverMapper;
    @Mock
    private SiteIncidentLogMapper incidentMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private BusinessEventService businessEventService;
    @Mock
    private EntityExistenceService entityExistenceService;
    @Mock
    private VenueAdminRelationMapper venueAdminRelationMapper;

    private SiteOpsService service;

    @BeforeEach
    void setUp() {
        service = new SiteOpsService(shiftMapper, taskMapper, handoverMapper, incidentMapper,
            venueAccessService, businessEventService, entityExistenceService, venueAdminRelationMapper);
        org.mockito.Mockito.lenient().when(shiftMapper.lockAssignmentGuard(
            org.mockito.ArgumentMatchers.anyLong())).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "site-admin", Set.of("SYSTEM_ADMIN"), Set.of()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createShiftRejectsOverlappingAssignmentForSameUser() {
        SiteShiftRequest request = shiftRequest();
        // AUD-P2-03：assignedUserId=7L 已通过存在+场馆归属校验后才进入重叠检测。
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("人员已存在重叠班次");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createShiftPersistsPlannedShiftAndRecordsEvent() {
        SiteShiftRequest request = shiftRequest();
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(0L);
        when(shiftMapper.insert(any(SiteShift.class))).thenAnswer(invocation -> {
            SiteShift shift = invocation.getArgument(0);
            shift.id = 10L;
            return 1;
        });

        SiteShift result = service.createShift(request);

        assertThat(result.shiftNo).startsWith("SITE_SHIFT");
        assertThat(result.status).isEqualTo("PLANNED");
        assertThat(result.sourceType).isEqualTo("ADMIN");
        assertThat(result.handoverStatus).isEqualTo("NONE");
        assertThat(result.createTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_SHIFT_CREATED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.SITE_OPERATIONS);
        assertThat(event.entityType).isEqualTo("SITE_SHIFT");
        assertThat(event.entityId).isEqualTo(10L);
        assertThat(event.idempotencyKey).isEqualTo("SITE_SHIFT_CREATED:" + result.shiftNo);
    }

    @Test
    void createShiftLocksSameUserAndRoleBeforeCheckingOverlap() {
        SiteShiftRequest request = shiftRequest();
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(0L);
        when(shiftMapper.insert(any(SiteShift.class))).thenAnswer(invocation -> {
            SiteShift shift = invocation.getArgument(0);
            shift.id = 10L;
            return 1;
        });

        service.createShift(request);

        verify(shiftMapper).insertAssignmentGuard(7L);
        verify(shiftMapper).lockAssignmentGuard(7L);
    }

    @Test
    void listShiftsWithSpecificVenueRequiresAccessAndReturnsPage() {
        SiteOpsQuery query = new SiteOpsQuery();
        query.venueId = 20L;
        query.status = "PLANNED";
        Page<SiteShift> page = new Page<>(1, 10);
        page.setRecords(List.of(shift("PLANNED")));
        page.setTotal(1);
        when(shiftMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<SiteShift> result = service.shifts(query);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(shiftMapper).selectPage(any(), any());
    }

    @Test
    void listShiftsAppliesOperationalDayFilterOnServerSide() {
        SiteOpsQuery query = new SiteOpsQuery();
        query.venueId = 20L;
        query.date = LocalDate.of(2026, 9, 13);
        Page<SiteShift> page = new Page<>(1, 10);
        page.setRecords(List.of(shift("PLANNED")));
        page.setTotal(1);
        ArgumentCaptor<QueryWrapper<SiteShift>> wrapper = ArgumentCaptor.forClass(QueryWrapper.class);
        when(shiftMapper.selectPage(any(), wrapper.capture())).thenReturn(page);

        service.shifts(query);

        assertThat(wrapper.getValue().getTargetSql()).contains("shift_date =");
        assertThat(wrapper.getValue().getParamNameValuePairs().values()).contains(LocalDate.of(2026, 9, 13));
    }

    @Test
    void listShiftsWithoutOperationalDayKeepsFullDateRange() {
        SiteOpsQuery query = new SiteOpsQuery();
        query.venueId = 20L;
        Page<SiteShift> page = new Page<>(1, 10);
        page.setRecords(List.of(shift("PLANNED")));
        page.setTotal(1);
        ArgumentCaptor<QueryWrapper<SiteShift>> wrapper = ArgumentCaptor.forClass(QueryWrapper.class);
        when(shiftMapper.selectPage(any(), wrapper.capture())).thenReturn(page);

        service.shifts(query);

        assertThat(wrapper.getValue().getTargetSql()).doesNotContain("shift_date");
    }

    @Test
    void listTasksWithoutVenueAndNoManageableVenuesReturnsEmptyPage() {
        SiteOpsQuery query = new SiteOpsQuery();
        query.setPageNum(2);
        query.setPageSize(5);
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of());

        PageResult<SiteTask> result = service.tasks(query);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(5);
        verify(venueAccessService).manageableVenueIds();
        verify(taskMapper, never()).selectPage(any(), any());
    }

    @Test
    void updateShiftChangesEditableFieldsWithoutChangingStatus() {
        SiteShift shift = shift("IN_PROGRESS");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        // A26-P2-12：updateShift 非终态分支校验被指派人存在+场馆归属。
        when(venueAdminRelationMapper.countRelation(9L, 20L)).thenReturn(1);
        // A26-P2-11：rejectShiftOverlap 查询重叠班次（恢复人级语义后不再按 role_code 收窄）。
        when(shiftMapper.selectCount(any())).thenReturn(0L);
        SiteShiftRequest request = shiftRequest();
        request.shiftName = "晚班";
        request.roleCode = "LEAD";
        request.assignedUserId = 9L;
        request.metadataJson = "{\"note\":\"adjusted\"}";

        SiteShift result = service.updateShift(10L, request);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        assertThat(result.shiftName).isEqualTo("晚班");
        assertThat(result.roleCode).isEqualTo("LEAD");
        assertThat(result.assignedUserId).isEqualTo(9L);
        assertThat(result.metadataJson).isEqualTo("{\"note\":\"adjusted\"}");
        assertThat(result.updateTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(9L);
        // A26-P2-16：updateShift 用 UpdateWrapper.set 显式 set 可空字段（spaceId/metadataJson）。
        verify(shiftMapper).update(eq(shift), any());
    }

    @Test
    void completedShiftRejectsCoreOwnershipChangeButAllowsNonCoreUpdate() {
        SiteShift shift = shift("COMPLETED");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        SiteShiftRequest changedOwnership = shiftRequest();
        changedOwnership.assignedUserId = 8L;

        assertThatThrownBy(() -> service.updateShift(10L, changedOwnership))
            .isInstanceOf(BusinessException.class)
            .hasMessage("已完成班次不可改写核心归属");
        verify(shiftMapper, never()).updateById(any(SiteShift.class));

        SiteShiftRequest nonCore = shiftRequest();
        nonCore.shiftName = "早班交接完成";
        nonCore.metadataJson = "{\"note\":\"归档补充\"}";

        SiteShift result = service.updateShift(10L, nonCore);

        assertThat(result.shiftName).isEqualTo("早班交接完成");
        assertThat(result.status).isEqualTo("COMPLETED");
        // A26-P2-16：COMPLETED 分支同样用 UpdateWrapper.set 显式 set metadata_json。
        verify(shiftMapper).update(eq(shift), any());
    }

    @Test
    void clockInMovesPlannedShiftToInProgressAndRecordsEvent() {
        SiteShift shift = shift("PLANNED");
        when(shiftMapper.selectById(10L)).thenReturn(shift);

        SiteShift result = service.clockIn(10L);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        assertThat(result.actualClockInTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(shiftMapper).updateById(shift);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_SHIFT_CLOCKED_IN);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.SITE_OPERATIONS);
        assertThat(event.entityType).isEqualTo("SITE_SHIFT");
        assertThat(event.entityId).isEqualTo(10L);
        assertThat(event.fromStatus).isEqualTo("PLANNED");
        assertThat(event.toStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.idempotencyKey).isEqualTo("SITE_SHIFT_CLOCKED_IN:10");
    }

    @Test
    void clockOutMovesInProgressShiftToCompletedAndRecordsEvent() {
        SiteShift shift = shift("IN_PROGRESS");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(taskMapper.selectCount(any())).thenReturn(0L);

        SiteShift result = service.clockOut(10L);

        assertThat(result.status).isEqualTo("COMPLETED");
        assertThat(result.actualClockOutTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(shiftMapper).updateById(shift);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_SHIFT_COMPLETED);
        assertThat(event.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.toStatus).isEqualTo("COMPLETED");
        assertThat(event.idempotencyKey).isEqualTo("SITE_SHIFT_COMPLETED:10");
    }

    @Test
    void clockOutRejectsUnfinishedTasksWithoutAcceptedHandover() {
        SiteShift shift = shift("IN_PROGRESS");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(taskMapper.selectCount(any())).thenReturn(1L);
        when(handoverMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.clockOut(10L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("存在未完成任务，请先完成交接确认");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(shiftMapper, never()).updateById(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void clockOutAllowsUnfinishedTasksWhenAcceptedHandoverExists() {
        SiteShift shift = shift("IN_PROGRESS");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(taskMapper.selectCount(any())).thenReturn(1L);
        when(handoverMapper.selectCount(any())).thenReturn(1L);

        SiteShift result = service.clockOut(10L);

        assertThat(result.status).isEqualTo("COMPLETED");
        assertThat(result.actualClockOutTime).isNotNull();
        verify(taskMapper).selectCount(any());
        verify(handoverMapper).selectCount(any());
        verify(shiftMapper).updateById(shift);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_SHIFT_COMPLETED);
        assertThat(event.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.toStatus).isEqualTo("COMPLETED");
    }

    @Test
    void createTaskPersistsTodoTaskAndRecordsEvent() {
        SiteTaskRequest request = taskRequest();
        // AUD-P2-03：assignedUserId=7L 已通过存在+场馆归属校验。
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(taskMapper.insert(any(SiteTask.class))).thenAnswer(invocation -> {
            SiteTask task = invocation.getArgument(0);
            task.id = 20L;
            return 1;
        });

        SiteTask result = service.createTask(request);

        assertThat(result.taskNo).startsWith("SITE_TASK");
        assertThat(result.status).isEqualTo("TODO");
        assertThat(result.priority).isEqualTo("HIGH");
        assertThat(result.sourceType).isEqualTo("ADMIN");
        assertThat(result.reservationId).isEqualTo(100L);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_TASK_CREATED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.SITE_OPERATIONS);
        assertThat(event.entityType).isEqualTo("SITE_TASK");
        assertThat(event.entityId).isEqualTo(20L);
        assertThat(event.toStatus).isEqualTo("TODO");
        assertThat(event.idempotencyKey).isEqualTo("SITE_TASK_CREATED:" + result.taskNo);
    }

    @Test
    void startTaskMovesTodoTaskToInProgress() {
        SiteTask task = task("TODO");
        when(taskMapper.selectById(20L)).thenReturn(task);

        SiteTask result = service.startTask(20L);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        assertThat(result.startedAt).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper).updateById(task);
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void startTaskStillAcceptsLegacyPendingTaskDuringMigrationCompatibility() {
        SiteTask task = task("PENDING");
        when(taskMapper.selectById(20L)).thenReturn(task);

        SiteTask result = service.startTask(20L);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        verify(taskMapper).updateById(task);
    }

    @Test
    void completeTaskRequiresCompletionNote() {
        SiteTask task = task("IN_PROGRESS");
        // AUD-P2-01：completeTask 改为先 FOR UPDATE 锁行再校验。
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        SiteTaskCompleteRequest request = new SiteTaskCompleteRequest();

        assertThatThrownBy(() -> service.completeTask(20L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("完成说明不能为空");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper, never()).update(any(), any());
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void completeTaskMovesTaskToDoneAndRecordsEvent() {
        SiteTask task = task("IN_PROGRESS");
        // AUD-P2-01：completeTask 改为 FOR UPDATE 锁行 + CAS 更新。
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        SiteTaskCompleteRequest request = new SiteTaskCompleteRequest();
        request.completionNote = "已完成清洁并复位";
        request.evidence = "{\"photo\":\"ok\"}";

        SiteTask result = service.completeTask(20L, request);

        assertThat(result.status).isEqualTo("DONE");
        assertThat(result.completionNote).isEqualTo("已完成清洁并复位");
        assertThat(result.evidenceJson).isEqualTo("{\"photo\":\"ok\"}");
        assertThat(result.completedAt).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper).update(eq(task), any());

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_TASK_COMPLETED);
        assertThat(event.fromStatus).isEqualTo("IN_PROGRESS");
        assertThat(event.toStatus).isEqualTo("DONE");
        assertThat(event.reasonText).isEqualTo("已完成清洁并复位");
        assertThat(event.idempotencyKey).isEqualTo("SITE_TASK_COMPLETED:20");
    }

    @Test
    void completeTaskWithExceptionCodeMovesTaskToException() {
        SiteTask task = task("TODO");
        // AUD-P2-01：completeTask 改为 FOR UPDATE 锁行 + CAS 更新。
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        SiteTaskCompleteRequest request = new SiteTaskCompleteRequest();
        request.completionNote = "设备异常，已转维修";
        request.exceptionCode = "ASSET_FAILURE";

        SiteTask result = service.completeTask(20L, request);

        assertThat(result.status).isEqualTo("EXCEPTION");
        assertThat(result.exceptionCode).isEqualTo("ASSET_FAILURE");

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.fromStatus).isEqualTo("TODO");
        assertThat(event.toStatus).isEqualTo("EXCEPTION");
        assertThat(event.reasonCode).isEqualTo("ASSET_FAILURE");
    }

    @Test
    void updateTaskChangesEditableFieldsWithoutChangingStatus() {
        SiteTask task = task("IN_PROGRESS");
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        // A26-P2-12：updateTask 非终态分支校验被指派人存在+场馆归属（assignedUserId=8L 非空时校验）。
        when(venueAdminRelationMapper.countRelation(8L, 20L)).thenReturn(1);
        SiteTaskRequest request = taskRequest();
        request.title = "复查器材";
        request.priority = "LOW";
        request.assignedUserId = 8L;
        request.complaintId = 66L;

        SiteTask result = service.updateTask(20L, request);

        assertThat(result.status).isEqualTo("IN_PROGRESS");
        assertThat(result.title).isEqualTo("复查器材");
        assertThat(result.priority).isEqualTo("LOW");
        assertThat(result.assignedUserId).isEqualTo(8L);
        assertThat(result.complaintId).isEqualTo(66L);
        assertThat(result.updateTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(8L);
        // A26-P2-16：updateTask 用 UpdateWrapper.set 显式 set 可空字段（assignedUserId/spaceId/...）。
        verify(taskMapper).update(eq(task), any());
    }

    @Test
    void terminalTaskRejectsCoreAssociationChangeButAllowsNonCoreUpdate() {
        SiteTask task = task("DONE");
        applyTaskRequestAssociations(task);
        when(taskMapper.selectById(20L)).thenReturn(task);
        SiteTaskRequest changedOwnership = taskRequest();
        changedOwnership.assetId = 401L;

        assertThatThrownBy(() -> service.updateTask(20L, changedOwnership))
            .isInstanceOf(BusinessException.class)
            .hasMessage("终态任务不可改写核心归属");
        verify(taskMapper, never()).updateById(any(SiteTask.class));

        SiteTaskRequest nonCore = taskRequest();
        nonCore.title = "完成记录补充";
        nonCore.priority = "LOW";
        nonCore.metadataJson = "{\"note\":\"归档补充\"}";

        SiteTask result = service.updateTask(20L, nonCore);

        assertThat(result.title).isEqualTo("完成记录补充");
        assertThat(result.priority).isEqualTo("LOW");
        assertThat(result.status).isEqualTo("DONE");
        // A26-P2-16：终态分支同样用 UpdateWrapper.set 显式 set 可空字段（dueTime/metadataJson）。
        verify(taskMapper).update(eq(task), any());
    }

    @Test
    void cancelTaskMovesOpenTaskToCanceled() {
        SiteTask task = task("TODO");
        // AUD-P2-01：cancelTask 改为 FOR UPDATE 锁行 + CAS 更新。
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        SiteCancelRequest request = new SiteCancelRequest();
        request.reason = "任务重复";

        SiteTask result = service.cancelTask(20L, request);

        assertThat(result.status).isEqualTo("CANCELED");
        assertThat(result.completionNote).isEqualTo("任务重复");
        assertThat(result.completedAt).isNotNull();
        assertThat(result.updateTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper).update(eq(task), any());
    }

    // AUD-P2-01：任务在 FOR UPDATE 锁行期间被并发改成终态，CAS 更新返回 0 行应抛 conflict，不允许覆盖。

    @Test
    void completeTaskRejectsConcurrentStatusChange() {
        SiteTask task = task("IN_PROGRESS");
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        // CAS 更新返回 0 行，模拟另一事务已把 status 从 IN_PROGRESS 改为终态。
        when(taskMapper.update(any(), any())).thenReturn(0);
        SiteTaskCompleteRequest request = new SiteTaskCompleteRequest();
        request.completionNote = "已完成清洁并复位";

        assertThatThrownBy(() -> service.completeTask(20L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("任务状态已变更");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void cancelTaskRejectsConcurrentStatusChange() {
        SiteTask task = task("TODO");
        when(taskMapper.selectByIdForUpdate(20L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(0);
        SiteCancelRequest request = new SiteCancelRequest();
        request.reason = "任务重复";

        assertThatThrownBy(() -> service.cancelTask(20L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("任务状态已变更");
    }

    // AUD-P2-03：assignedUserId 校验拒绝场景——用户不存在/禁用/无场馆归属。

    @Test
    void createShiftRejectsAssignedUserNotExist() {
        SiteShiftRequest request = shiftRequest();
        // EntityExistenceService.requireActiveUser 在用户不存在时抛 badRequest。
        doThrow(BusinessException.badRequest("用户不存在"))
            .when(entityExistenceService).requireActiveUser(7L);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户不存在");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createShiftRejectsAssignedUserNotBelongingToVenue() {
        SiteShiftRequest request = shiftRequest();
        // 用户存在+启用但与 venueId=20L 无关联，应拒绝。
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createTaskRejectsAssignedUserDisabled() {
        SiteTaskRequest request = taskRequest();
        // EntityExistenceService.requireActiveUser 在用户已禁用时抛 badRequest。
        doThrow(BusinessException.badRequest("用户已禁用"))
            .when(entityExistenceService).requireActiveUser(7L);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));

        assertThatThrownBy(() -> service.createTask(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户已禁用");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
        verify(taskMapper, never()).insert(any(SiteTask.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createTaskRejectsAssignedUserNotBelongingToVenue() {
        SiteTaskRequest request = taskRequest();
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(0);

        assertThatThrownBy(() -> service.createTask(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);
        verify(taskMapper, never()).insert(any(SiteTask.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverCountsOpenTasksAndRecordsEvent() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift sourceShift = shift("PLANNED");
        when(shiftMapper.selectById(10L)).thenReturn(sourceShift);
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        // AUD-P1-04：接收人校验要求接收班次指派人等于请求 toUserId（8L）。
        receivingShift.assignedUserId = 8L;
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);
        // AUD-P1-04：防重复交接先查 handoverMapper.selectCount，需放行返回 0。
        when(handoverMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(handoverMapper.insert(any(SiteShiftHandover.class))).thenAnswer(invocation -> {
            SiteShiftHandover handover = invocation.getArgument(0);
            handover.id = 30L;
            return 1;
        });

        SiteShiftHandover handover = service.submitHandover(request);

        assertThat(handover.status).isEqualTo("SUBMITTED");
        assertThat(handover.unresolvedTaskCount).isEqualTo(2);
        assertThat(sourceShift.handoverStatus).isEqualTo("SUBMITTED");
        assertThat(receivingShift.handoverStatus).isEqualTo("PENDING");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(handoverMapper).insert(handover);
        verify(shiftMapper).updateById(sourceShift);
        verify(shiftMapper).updateById(receivingShift);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_HANDOVER_SUBMITTED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.SITE_OPERATIONS);
        assertThat(event.entityType).isEqualTo("SITE_HANDOVER");
        assertThat(event.entityId).isEqualTo(30L);
        assertThat(event.idempotencyKey).isEqualTo("SITE_HANDOVER_SUBMITTED:" + handover.handoverNo);
    }

    @Test
    void createTaskRejectsShiftFromAnotherVenueBeforeWriting() {
        SiteTaskRequest request = taskRequest();
        SiteShift otherVenueShift = shift("PLANNED");
        otherVenueShift.venueId = 21L;
        when(shiftMapper.selectById(10L)).thenReturn(otherVenueShift);

        assertThatThrownBy(() -> service.createTask(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("关联班次不属于请求场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper, never()).insert(any(SiteTask.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void updateTaskRejectsShiftFromAnotherVenueBeforeWriting() {
        SiteTask task = task("TODO");
        SiteTaskRequest request = taskRequest();
        SiteShift otherVenueShift = shift("PLANNED");
        otherVenueShift.venueId = 21L;
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(shiftMapper.selectById(10L)).thenReturn(otherVenueShift);

        assertThatThrownBy(() -> service.updateTask(20L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("关联班次不属于请求场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper, never()).updateById(any(SiteTask.class));
    }

    @Test
    void submitHandoverRejectsSourceShiftFromAnotherVenueBeforeWriting() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift otherVenueShift = shift("PLANNED");
        otherVenueShift.venueId = 21L;
        when(shiftMapper.selectById(10L)).thenReturn(otherVenueShift);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("关联班次不属于请求场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(taskMapper, never()).selectCount(any());
        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsTargetShiftFromAnotherVenueBeforeWriting() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        receivingShift.venueId = 21L;
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("关联班次不属于请求场馆");

        verify(taskMapper, never()).selectCount(any());
        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void clockOutScopesLegacyTaskAndHandoverChecksToTheCurrentShiftVenue() {
        SiteShift shift = shift("IN_PROGRESS");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(taskMapper.selectCount(any())).thenReturn(1L);
        when(handoverMapper.selectCount(any())).thenReturn(1L);

        service.clockOut(10L);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> taskQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> handoverQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(taskMapper).selectCount(taskQuery.capture());
        verify(handoverMapper).selectCount(handoverQuery.capture());
        // A26-P3-18：task 查询只按 shift_id 过滤，不再带 venue_id（班次跨场馆移动后旧 venue_id 漏计）。
        assertThat(taskQuery.getValue().getSqlSegment()).contains("shift_id");
        assertThat(taskQuery.getValue().getSqlSegment()).doesNotContain("venue_id");
        assertThat(taskQuery.getValue().getParamNameValuePairs().values()).contains(10L);
        assertThat(taskQuery.getValue().getParamNameValuePairs().values()).doesNotContain(20L);
        // AUD-P1-04：签退前置收紧为只认 ACCEPTED 交接，handover 查询需包含 status=ACCEPTED。
        assertThat(handoverQuery.getValue().getSqlSegment()).contains("venue_id", "from_shift_id", "status");
        assertThat(handoverQuery.getValue().getParamNameValuePairs().values()).contains(20L, 10L, "ACCEPTED");
    }

    @Test
    void acceptHandoverMovesSubmittedHandoverToAccepted() {
        SiteShiftHandover handover = handover("SUBMITTED");
        SiteShift sourceShift = shift("COMPLETED");
        sourceShift.handoverStatus = "SUBMITTED";
        SiteShift receivingShift = shift("IN_PROGRESS");
        receivingShift.id = 12L;
        receivingShift.handoverStatus = "PENDING";
        when(handoverMapper.selectById(30L)).thenReturn(handover);
        when(shiftMapper.selectById(10L)).thenReturn(sourceShift);
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);

        SiteShiftHandover result = service.acceptHandover(30L);

        assertThat(result.status).isEqualTo("ACCEPTED");
        assertThat(result.acceptedAt).isNotNull();
        assertThat(result.updateTime).isNotNull();
        assertThat(sourceShift.handoverStatus).isEqualTo("ACCEPTED");
        assertThat(receivingShift.handoverStatus).isEqualTo("ACCEPTED");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(handoverMapper).updateById(handover);
        verify(shiftMapper).updateById(sourceShift);
        verify(shiftMapper).updateById(receivingShift);
    }

    @Test
    void submitHandoverRejectsMissingTargetShiftAndUser() {
        SiteHandoverRequest request = handoverRequest();
        request.toShiftId = null;
        request.toUserId = null;

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("交接信息不完整");

        // 参数校验先于场馆权限校验，故二者均不应被触达。
        verify(venueAccessService, never()).requireVenueManageAccess(any());
        verify(shiftMapper, never()).selectById(any());
        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsSelfHandoverForSameShift() {
        SiteHandoverRequest request = handoverRequest();
        request.toShiftId = request.fromShiftId;
        request.toUserId = 8L;
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("交接班次不可与源班次相同");

        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsSelfHandoverForSameUser() {
        SiteHandoverRequest request = handoverRequest();
        request.toUserId = request.fromUserId;
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        receivingShift.assignedUserId = 7L;
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("接收人不可与交班人相同");

        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsSubmitterNotAssignedToSourceShift() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift sourceShift = shift("PLANNED");
        sourceShift.assignedUserId = 99L;
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        receivingShift.assignedUserId = 8L;
        when(shiftMapper.selectById(10L)).thenReturn(sourceShift);
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("交班人不是源班次的指派人");

        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsReceiverNotAssignedToTargetShift() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        // 接收班次指派人是 7L，但请求 toUserId=8L，应被拒绝。
        receivingShift.assignedUserId = 7L;
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("接收人不是接收班次的指派人");

        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void submitHandoverRejectsDuplicateSubmission() {
        SiteHandoverRequest request = handoverRequest();
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        receivingShift.assignedUserId = 8L;
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);
        // 已存在待接收或已接收交接，应拒绝重复提交。
        when(handoverMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submitHandover(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("源班次已存在待接收或已接收交接");

        verify(taskMapper, never()).selectCount(any());
        verify(handoverMapper, never()).insert(any(SiteShiftHandover.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void recordIncidentCreatesOpenIncidentAndRecordsEvent() {
        SiteIncidentRequest request = incidentRequest();
        when(incidentMapper.insert(any(SiteIncidentLog.class))).thenAnswer(invocation -> {
            SiteIncidentLog incident = invocation.getArgument(0);
            incident.id = 40L;
            return 1;
        });

        SiteIncidentLog incident = service.recordIncident(request);

        assertThat(incident.incidentNo).startsWith("SITE_INCIDENT");
        assertThat(incident.status).isEqualTo("OPEN");
        assertThat(incident.reportedBy).isEqualTo(7L);
        assertThat(incident.sourceType).isEqualTo("ADMIN");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(incidentMapper).insert(incident);

        BusinessEventRecordRequest event = capturedEvent();
        assertThat(event.eventType).isEqualTo(BusinessEventType.SITE_INCIDENT_RECORDED);
        assertThat(event.businessDomain).isEqualTo(BusinessDomain.SITE_OPERATIONS);
        assertThat(event.entityType).isEqualTo("SITE_INCIDENT");
        assertThat(event.entityId).isEqualTo(40L);
        assertThat(event.reasonText).isEqualTo("用户滑倒，已通知值班人员");
        assertThat(event.idempotencyKey).isEqualTo("SITE_INCIDENT_RECORDED:" + incident.incidentNo);
    }

    @Test
    void resolveIncidentMovesOpenIncidentToResolved() {
        SiteIncidentLog incident = incident("OPEN");
        // A26-P3-19：resolveIncident 改为 FOR UPDATE 锁行 + CAS UpdateWrapper。
        when(incidentMapper.selectByIdForUpdate(40L)).thenReturn(incident);
        when(incidentMapper.update(any(), any())).thenReturn(1);
        SiteIncidentResolveRequest request = new SiteIncidentResolveRequest();
        request.resolution = "现场处理完成";

        SiteIncidentLog result = service.resolveIncident(40L, request);

        assertThat(result.status).isEqualTo("RESOLVED");
        assertThat(result.resolution).isEqualTo("现场处理完成");
        assertThat(result.updateTime).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(incidentMapper).selectByIdForUpdate(40L);
        verify(incidentMapper).update(eq(incident), any());
    }

    // A26-P3-19：resolveIncident CAS 返回 0 行时抛 conflict，拒绝并发覆盖先写的处理结论。
    @Test
    void resolveIncidentRejectsConcurrentStatusChange() {
        SiteIncidentLog incident = incident("OPEN");
        when(incidentMapper.selectByIdForUpdate(40L)).thenReturn(incident);
        // CAS 更新返回 0 行，模拟另一事务已把 status 从 OPEN 改为 RESOLVED。
        when(incidentMapper.update(any(), any())).thenReturn(0);
        SiteIncidentResolveRequest request = new SiteIncidentResolveRequest();
        request.resolution = "现场处理完成";

        assertThatThrownBy(() -> service.resolveIncident(40L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("异常状态已变更");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(incidentMapper).selectByIdForUpdate(40L);
    }

    // A26-P3-21：lockShiftAssignment catch DeadlockLoserDataAccessException 返回 409，
    // 并发排班一方以干净的 conflict 失败而非 500（数据侧仍正确）。
    @Test
    void createShiftTranslatesDeadlockToConflict() {
        SiteShiftRequest request = shiftRequest();
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DeadlockLoserDataAccessException(
                "Deadlock found when trying to get lock", null))
            .when(shiftMapper).insertAssignmentGuard(7L);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("排班并发冲突，请重试");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);
        verify(shiftMapper, never()).selectCount(any());
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P3-21：lockAssignmentGuard 死锁同样翻译为 conflict 409。
    @Test
    void createShiftTranslatesDeadlockOnLockGuardToConflict() {
        SiteShiftRequest request = shiftRequest();
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DeadlockLoserDataAccessException(
                "Deadlock found when trying to get lock", null))
            .when(shiftMapper).lockAssignmentGuard(7L);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("排班并发冲突，请重试");

        verify(shiftMapper).insertAssignmentGuard(7L);
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P3-18：countOpenTasks 只按 shift_id 过滤，移除 venue_id 谓词，
    // 班次跨场馆移动后旧 venue_id 下的未完成任务不再被漏计，签退门槛（AUD-P1-04）不致被架空。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void submitHandoverCountsOpenTasksByShiftIdWithoutVenuePredicate() {
        SiteHandoverRequest request = handoverRequest();
        // 班次仍在 venueId=20L，但其下任务可能因历史跨场馆移动保留旧 venue_id；
        // countOpenTasks 应只按 shift_id 过滤，不再因 venue_id 不一致漏计。
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        SiteShift receivingShift = shift("PLANNED");
        receivingShift.id = 12L;
        receivingShift.assignedUserId = 8L;
        when(shiftMapper.selectById(12L)).thenReturn(receivingShift);
        when(handoverMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(2L);
        when(handoverMapper.insert(any(SiteShiftHandover.class))).thenAnswer(invocation -> {
            SiteShiftHandover handover = invocation.getArgument(0);
            handover.id = 30L;
            return 1;
        });

        service.submitHandover(request);

        ArgumentCaptor<QueryWrapper> taskQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(taskMapper).selectCount(taskQuery.capture());
        // task 查询只按 shift_id 过滤，venue_id 谓词纯属有害（shift_id 已全局唯一）。
        assertThat(taskQuery.getValue().getSqlSegment()).contains("shift_id");
        assertThat(taskQuery.getValue().getSqlSegment()).doesNotContain("venue_id");
        assertThat(taskQuery.getValue().getParamNameValuePairs().values()).contains(10L);
        assertThat(taskQuery.getValue().getParamNameValuePairs().values()).doesNotContain(20L);
    }

    // A26-P2-11：rejectShiftOverlap 恢复人级语义——同一人不同岗位时间重叠被拒绝。
    @Test
    void createShiftRejectsOverlappingAssignmentForSameUserAcrossRoles() {
        SiteShiftRequest request = shiftRequest();
        request.roleCode = "LEAD"; // 与既有 OPS 班次不同岗位，但同一人同一时段
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createShift(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("人员已存在重叠班次");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(7L);
        verify(shiftMapper, never()).insert(any(SiteShift.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-11：updateShift 同样恢复人级语义——同一人不同岗位时间重叠被拒绝。
    @Test
    void updateShiftRejectsOverlappingAssignmentForSameUserAcrossRoles() {
        SiteShift shift = shift("PLANNED");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(venueAdminRelationMapper.countRelation(9L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(1L);
        SiteShiftRequest request = shiftRequest();
        request.roleCode = "LEAD";
        request.assignedUserId = 9L;

        assertThatThrownBy(() -> service.updateShift(10L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("人员已存在重叠班次");

        verify(shiftMapper, never()).update(any(SiteShift.class), any());
    }

    // A26-P2-12：updateShift 校验被指派人场馆归属，拒绝跨场馆幻影 ID。
    @Test
    void updateShiftRejectsAssignedUserNotBelongingToVenue() {
        SiteShift shift = shift("PLANNED");
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(venueAdminRelationMapper.countRelation(9L, 20L)).thenReturn(0);
        SiteShiftRequest request = shiftRequest();
        request.assignedUserId = 9L;

        assertThatThrownBy(() -> service.updateShift(10L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(9L);
        verify(shiftMapper, never()).update(any(SiteShift.class), any());
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    // A26-P2-12：updateTask 校验被指派人场馆归属，拒绝跨场馆幻影 ID。
    @Test
    void updateTaskRejectsAssignedUserNotBelongingToVenue() {
        SiteTask task = task("TODO");
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        when(venueAdminRelationMapper.countRelation(8L, 20L)).thenReturn(0);
        SiteTaskRequest request = taskRequest();
        request.assignedUserId = 8L;

        assertThatThrownBy(() -> service.updateTask(20L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("指派人员不属于该场馆");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(entityExistenceService).requireActiveUser(8L);
        verify(taskMapper, never()).update(any(SiteTask.class), any());
    }

    // A26-P2-12：updateTask 允许 assignedUserId 为 null（任务不指派），null 时不校验。
    @Test
    void updateTaskAllowsNullAssignedUserIdWithoutValidation() {
        SiteTask task = task("TODO");
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        SiteTaskRequest request = taskRequest();
        request.assignedUserId = null;

        SiteTask result = service.updateTask(20L, request);

        assertThat(result.assignedUserId).isNull();
        // assignedUserId 为 null 时不调 requireActiveUser/countRelation。
        verify(entityExistenceService, never()).requireActiveUser(any());
        verify(venueAdminRelationMapper, never()).countRelation(any(), any());
        // A26-P2-16：assignedUserId=null 通过 UpdateWrapper.set 显式落库。
        verify(taskMapper).update(eq(task), any());
    }

    // A26-P2-16：updateShift 把 spaceId 置 null 时通过 UpdateWrapper.set 显式落库。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void updateShiftPersistsNullSpaceIdViaUpdateWrapper() {
        SiteShift shift = shift("PLANNED");
        shift.spaceId = 11L; // 原先绑定场地
        when(shiftMapper.selectById(10L)).thenReturn(shift);
        when(venueAdminRelationMapper.countRelation(7L, 20L)).thenReturn(1);
        when(shiftMapper.selectCount(any())).thenReturn(0L);
        SiteShiftRequest request = shiftRequest();
        request.spaceId = null; // 解绑场地

        SiteShift result = service.updateShift(10L, request);

        assertThat(result.spaceId).isNull();
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(shiftMapper).update(eq(shift), wrapperCaptor.capture());
        // UpdateWrapper 的 set SQL 应包含 space_id，确保 null 被显式写入 SET 子句。
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("space_id");
    }

    // A26-P2-16：updateTask 把 assignedUserId 置 null 时通过 UpdateWrapper.set 显式落库。
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void updateTaskPersistsNullAssignedUserIdViaUpdateWrapper() {
        SiteTask task = task("IN_PROGRESS");
        task.assignedUserId = 7L; // 原先已指派
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(shiftMapper.selectById(10L)).thenReturn(shift("PLANNED"));
        SiteTaskRequest request = taskRequest();
        request.assignedUserId = null; // 取消指派

        SiteTask result = service.updateTask(20L, request);

        assertThat(result.assignedUserId).isNull();
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(eq(task), wrapperCaptor.capture());
        // UpdateWrapper 的 set SQL 应包含 assigned_user_id，确保 null 被显式写入 SET 子句。
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("assigned_user_id");
    }

    private BusinessEventRecordRequest capturedEvent() {
        ArgumentCaptor<BusinessEventRecordRequest> eventCaptor =
            ArgumentCaptor.forClass(BusinessEventRecordRequest.class);
        verify(businessEventService).record(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private SiteShiftRequest shiftRequest() {
        SiteShiftRequest request = new SiteShiftRequest();
        request.venueId = 20L;
        request.spaceId = 11L;
        request.shiftDate = LocalDate.of(2026, 7, 6);
        request.shiftName = "早班";
        request.roleCode = "OPS";
        request.assignedUserId = 7L;
        request.plannedStartTime = LocalDateTime.of(2026, 7, 6, 8, 0);
        request.plannedEndTime = LocalDateTime.of(2026, 7, 6, 16, 0);
        return request;
    }

    private SiteShift shift(String status) {
        SiteShift shift = new SiteShift();
        shift.id = 10L;
        shift.shiftNo = "SITE_SHIFT20260706080000AAAAAAAA";
        shift.venueId = 20L;
        shift.spaceId = 11L;
        shift.shiftDate = LocalDate.of(2026, 7, 6);
        shift.shiftName = "早班";
        shift.roleCode = "OPS";
        shift.assignedUserId = 7L;
        shift.plannedStartTime = LocalDateTime.of(2026, 7, 6, 8, 0);
        shift.plannedEndTime = LocalDateTime.of(2026, 7, 6, 16, 0);
        shift.status = status;
        shift.sourceType = "ADMIN";
        shift.handoverStatus = "NONE";
        shift.deleted = 0;
        return shift;
    }

    private SiteTaskRequest taskRequest() {
        SiteTaskRequest request = new SiteTaskRequest();
        request.venueId = 20L;
        request.spaceId = 11L;
        request.shiftId = 10L;
        request.taskType = "CLEANING";
        request.title = "羽毛球场清洁";
        request.priority = "HIGH";
        request.assignedUserId = 7L;
        request.reservationId = 100L;
        request.orderId = 200L;
        request.lifecycleId = 300L;
        request.assetId = 400L;
        request.unavailableBlockId = 500L;
        request.complaintId = 66L;
        request.dueTime = LocalDateTime.of(2026, 7, 6, 17, 0);
        return request;
    }

    private SiteTask task(String status) {
        SiteTask task = new SiteTask();
        task.id = 20L;
        task.taskNo = "SITE_TASK20260706080000BBBBBBBB";
        task.venueId = 20L;
        task.spaceId = 11L;
        task.shiftId = 10L;
        task.taskType = "CLEANING";
        task.title = "羽毛球场清洁";
        task.priority = "HIGH";
        task.status = status;
        task.assignedUserId = 7L;
        task.deleted = 0;
        return task;
    }

    private void applyTaskRequestAssociations(SiteTask task) {
        task.reservationId = 100L;
        task.orderId = 200L;
        task.lifecycleId = 300L;
        task.assetId = 400L;
        task.unavailableBlockId = 500L;
        task.complaintId = 66L;
    }

    private SiteShiftHandover handover(String status) {
        SiteShiftHandover handover = new SiteShiftHandover();
        handover.id = 30L;
        handover.handoverNo = "SITE_HANDOVER20260706080000CCCCCCCC";
        handover.fromShiftId = 10L;
        handover.toShiftId = 12L;
        handover.venueId = 20L;
        handover.fromUserId = 7L;
        handover.toUserId = 8L;
        handover.status = status;
        handover.unresolvedTaskCount = 0;
        handover.deleted = 0;
        return handover;
    }

    private SiteHandoverRequest handoverRequest() {
        SiteHandoverRequest request = new SiteHandoverRequest();
        request.fromShiftId = 10L;
        request.toShiftId = 12L;
        request.venueId = 20L;
        request.fromUserId = 7L;
        request.toUserId = 8L;
        request.incidentSummary = "无重大异常";
        request.assetIssueSummary = "球网待复查";
        request.note = "交接给晚班";
        return request;
    }

    private SiteIncidentRequest incidentRequest() {
        SiteIncidentRequest request = new SiteIncidentRequest();
        request.venueId = 20L;
        request.spaceId = 11L;
        request.shiftId = 10L;
        request.taskId = 20L;
        request.reservationId = 100L;
        request.orderId = 200L;
        request.assetId = 400L;
        request.complaintId = 900L;
        request.incidentType = "SAFETY";
        request.severity = "HIGH";
        request.occurredAt = LocalDateTime.of(2026, 7, 6, 9, 30);
        request.description = "用户滑倒，已通知值班人员";
        return request;
    }

    private SiteIncidentLog incident(String status) {
        SiteIncidentLog incident = new SiteIncidentLog();
        incident.id = 40L;
        incident.incidentNo = "SITE_INCIDENT20260706080000DDDDDDDD";
        incident.venueId = 20L;
        incident.spaceId = 11L;
        incident.shiftId = 10L;
        incident.taskId = 20L;
        incident.assetId = 400L;
        incident.incidentType = "SAFETY";
        incident.severity = "HIGH";
        incident.status = status;
        incident.description = "用户滑倒，已通知值班人员";
        incident.deleted = 0;
        return incident;
    }
}
