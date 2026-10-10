package com.example.venue.siteops;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.siteops.controller.SiteOpsController;
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
import com.example.venue.siteops.service.SiteOpsService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteOpsControllerTest {
    @Test
    void siteOpsControllerUsesAdminSiteRoute() {
        RequestMapping mapping = SiteOpsController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping.value()).containsExactly("/api/admin/site");
    }

    @Test
    void siteOpsMutationsRequireSiteOpsManagePermissionAndAuditLog() throws Exception {
        assertPostEndpoint("createShift", new Class<?>[]{SiteShiftRequest.class}, "/shifts", "创建现场班次");
        assertPutEndpoint("updateShift", new Class<?>[]{Long.class, SiteShiftRequest.class}, "/shifts/{id}",
            "更新现场班次");
        assertPostEndpoint("clockIn", new Class<?>[]{Long.class}, "/shifts/{id}/clock-in", "现场班次签到");
        assertPostEndpoint("clockOut", new Class<?>[]{Long.class}, "/shifts/{id}/clock-out", "现场班次签退");
        assertPostEndpoint("createTask", new Class<?>[]{SiteTaskRequest.class}, "/tasks", "创建现场任务");
        assertPutEndpoint("updateTask", new Class<?>[]{Long.class, SiteTaskRequest.class}, "/tasks/{id}",
            "更新现场任务");
        assertPostEndpoint("startTask", new Class<?>[]{Long.class}, "/tasks/{id}/start", "开始现场任务");
        assertPostEndpoint("completeTask", new Class<?>[]{Long.class, SiteTaskCompleteRequest.class},
            "/tasks/{id}/complete", "完成现场任务");
        assertPostEndpoint("cancelTask", new Class<?>[]{Long.class, SiteCancelRequest.class}, "/tasks/{id}/cancel",
            "取消现场任务");
        assertPostEndpoint("submitHandover", new Class<?>[]{SiteHandoverRequest.class},
            "/handovers", "提交交接班");
        assertPostEndpoint("acceptHandover", new Class<?>[]{Long.class}, "/handovers/{id}/accept", "接收交接班");
        assertPostEndpoint("recordIncident", new Class<?>[]{SiteIncidentRequest.class},
            "/incidents", "记录现场异常");
        assertPutEndpoint("resolveIncident", new Class<?>[]{Long.class, SiteIncidentResolveRequest.class},
            "/incidents/{id}/resolve", "解决现场异常");
    }

    @Test
    void siteOpsReadEndpointsRequireSiteOpsManagePermission() throws Exception {
        assertReadEndpoint("shifts", new Class<?>[]{SiteOpsQuery.class}, "/shifts");
        assertReadEndpoint("tasks", new Class<?>[]{SiteOpsQuery.class}, "/tasks");
        assertReadEndpoint("handovers", new Class<?>[]{SiteOpsQuery.class}, "/handovers");
        assertReadEndpoint("incidents", new Class<?>[]{SiteOpsQuery.class}, "/incidents");
    }

    @Test
    void createShiftDelegatesToService() {
        SiteOpsService service = mock(SiteOpsService.class);
        SiteOpsController controller = new SiteOpsController(service);
        SiteShiftRequest request = new SiteShiftRequest();
        SiteShift shift = new SiteShift();
        when(service.createShift(request)).thenReturn(shift);

        Result<SiteShift> result = controller.createShift(request);

        assertThat(result.getData()).isSameAs(shift);
        verify(service).createShift(request);
    }

    @Test
    void shiftReadsAndUpdatesDelegateToService() {
        SiteOpsService service = mock(SiteOpsService.class);
        SiteOpsController controller = new SiteOpsController(service);
        SiteOpsQuery query = new SiteOpsQuery();
        SiteShiftRequest request = new SiteShiftRequest();
        SiteShift shift = new SiteShift();
        PageResult<SiteShift> page = PageResult.of(java.util.List.of(shift), 1, 1, 10);
        when(service.shifts(query)).thenReturn(page);
        when(service.updateShift(10L, request)).thenReturn(shift);

        assertThat(controller.shifts(query).getData()).isSameAs(page);
        assertThat(controller.updateShift(10L, request).getData()).isSameAs(shift);

        verify(service).shifts(query);
        verify(service).updateShift(10L, request);
    }

    @Test
    void shiftActionsDelegateToService() {
        SiteOpsService service = mock(SiteOpsService.class);
        SiteOpsController controller = new SiteOpsController(service);
        SiteShift clockedIn = new SiteShift();
        SiteShift clockedOut = new SiteShift();
        when(service.clockIn(10L)).thenReturn(clockedIn);
        when(service.clockOut(10L)).thenReturn(clockedOut);

        assertThat(controller.clockIn(10L).getData()).isSameAs(clockedIn);
        assertThat(controller.clockOut(10L).getData()).isSameAs(clockedOut);

        verify(service).clockIn(10L);
        verify(service).clockOut(10L);
    }

    @Test
    void taskActionsDelegateToService() {
        SiteOpsService service = mock(SiteOpsService.class);
        SiteOpsController controller = new SiteOpsController(service);
        SiteOpsQuery query = new SiteOpsQuery();
        SiteTaskRequest createRequest = new SiteTaskRequest();
        SiteTaskRequest updateRequest = new SiteTaskRequest();
        SiteTaskCompleteRequest completeRequest = new SiteTaskCompleteRequest();
        SiteCancelRequest cancelRequest = new SiteCancelRequest();
        SiteTask created = new SiteTask();
        SiteTask updated = new SiteTask();
        SiteTask started = new SiteTask();
        SiteTask completed = new SiteTask();
        SiteTask canceled = new SiteTask();
        PageResult<SiteTask> page = PageResult.of(java.util.List.of(created), 1, 1, 10);
        when(service.tasks(query)).thenReturn(page);
        when(service.createTask(createRequest)).thenReturn(created);
        when(service.updateTask(20L, updateRequest)).thenReturn(updated);
        when(service.startTask(20L)).thenReturn(started);
        when(service.completeTask(20L, completeRequest)).thenReturn(completed);
        when(service.cancelTask(20L, cancelRequest)).thenReturn(canceled);

        assertThat(controller.tasks(query).getData()).isSameAs(page);
        assertThat(controller.createTask(createRequest).getData()).isSameAs(created);
        assertThat(controller.updateTask(20L, updateRequest).getData()).isSameAs(updated);
        assertThat(controller.startTask(20L).getData()).isSameAs(started);
        assertThat(controller.completeTask(20L, completeRequest).getData()).isSameAs(completed);
        assertThat(controller.cancelTask(20L, cancelRequest).getData()).isSameAs(canceled);

        verify(service).tasks(query);
        verify(service).createTask(createRequest);
        verify(service).updateTask(20L, updateRequest);
        verify(service).startTask(20L);
        verify(service).completeTask(20L, completeRequest);
        verify(service).cancelTask(20L, cancelRequest);
    }

    @Test
    void handoverAndIncidentDelegateToService() {
        SiteOpsService service = mock(SiteOpsService.class);
        SiteOpsController controller = new SiteOpsController(service);
        SiteOpsQuery query = new SiteOpsQuery();
        SiteHandoverRequest handoverRequest = new SiteHandoverRequest();
        SiteIncidentRequest incidentRequest = new SiteIncidentRequest();
        SiteIncidentResolveRequest resolveRequest = new SiteIncidentResolveRequest();
        SiteShiftHandover handover = new SiteShiftHandover();
        SiteShiftHandover accepted = new SiteShiftHandover();
        SiteIncidentLog incident = new SiteIncidentLog();
        SiteIncidentLog resolved = new SiteIncidentLog();
        PageResult<SiteShiftHandover> handoverPage = PageResult.of(java.util.List.of(handover), 1, 1, 10);
        PageResult<SiteIncidentLog> incidentPage = PageResult.of(java.util.List.of(incident), 1, 1, 10);
        when(service.handovers(query)).thenReturn(handoverPage);
        when(service.submitHandover(handoverRequest)).thenReturn(handover);
        when(service.acceptHandover(30L)).thenReturn(accepted);
        when(service.incidents(query)).thenReturn(incidentPage);
        when(service.recordIncident(incidentRequest)).thenReturn(incident);
        when(service.resolveIncident(40L, resolveRequest)).thenReturn(resolved);

        assertThat(controller.handovers(query).getData()).isSameAs(handoverPage);
        assertThat(controller.submitHandover(handoverRequest).getData()).isSameAs(handover);
        assertThat(controller.acceptHandover(30L).getData()).isSameAs(accepted);
        assertThat(controller.incidents(query).getData()).isSameAs(incidentPage);
        assertThat(controller.recordIncident(incidentRequest).getData()).isSameAs(incident);
        assertThat(controller.resolveIncident(40L, resolveRequest).getData()).isSameAs(resolved);

        verify(service).handovers(query);
        verify(service).submitHandover(handoverRequest);
        verify(service).acceptHandover(30L);
        verify(service).incidents(query);
        verify(service).recordIncident(incidentRequest);
        verify(service).resolveIncident(40L, resolveRequest);
    }

    private void assertReadEndpoint(String methodName, Class<?>[] parameterTypes, String path) throws Exception {
        Method method = SiteOpsController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('site:ops:manage')");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(AuditLog.class)).isNull();
    }

    private void assertPostEndpoint(String methodName, Class<?>[] parameterTypes, String path,
                                    String auditAction) throws Exception {
        Method method = SiteOpsController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('site:ops:manage')");
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertAuditLog(method, auditAction);
    }

    private void assertPutEndpoint(String methodName, Class<?>[] parameterTypes, String path,
                                   String auditAction) throws Exception {
        Method method = SiteOpsController.class.getMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('site:ops:manage')");
        assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
        assertAuditLog(method, auditAction);
    }

    private void assertAuditLog(Method method, String auditAction) {
        AuditLog auditLog = method.getAnnotation(AuditLog.class);
        assertThat(auditLog).isNotNull();
        assertThat(auditLog.module()).isEqualTo("现场运营");
        assertThat(auditLog.action()).isEqualTo(auditAction);
    }
}
