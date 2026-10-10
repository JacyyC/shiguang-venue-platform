package com.example.venue.siteops.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.siteops.dto.SiteCancelRequest;
import com.example.venue.siteops.dto.SiteHandoverRequest;
import com.example.venue.siteops.dto.SiteIncidentResolveRequest;
import com.example.venue.siteops.dto.SiteIncidentRequest;
import com.example.venue.siteops.dto.SiteOpsQuery;
import com.example.venue.siteops.dto.SiteShiftRequest;
import com.example.venue.siteops.dto.SiteTaskCompleteRequest;
import com.example.venue.siteops.dto.SiteTaskRequest;
import jakarta.validation.Valid;
import com.example.venue.siteops.entity.SiteIncidentLog;
import com.example.venue.siteops.entity.SiteShift;
import com.example.venue.siteops.entity.SiteShiftHandover;
import com.example.venue.siteops.entity.SiteTask;
import com.example.venue.siteops.service.SiteOpsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/site")
public class SiteOpsController {
    private final SiteOpsService service;

    public SiteOpsController(SiteOpsService service) {
        this.service = service;
    }

    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<PageResult<SiteShift>> shifts(@Valid SiteOpsQuery query) {
        return Result.ok(service.shifts(query));
    }

    @AuditLog(module = "现场运营", action = "创建现场班次")
    @PostMapping("/shifts")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShift> createShift(@RequestBody SiteShiftRequest request) {
        return Result.ok(service.createShift(request));
    }

    @AuditLog(module = "现场运营", action = "更新现场班次")
    @PutMapping("/shifts/{id}")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShift> updateShift(@PathVariable Long id, @RequestBody SiteShiftRequest request) {
        return Result.ok(service.updateShift(id, request));
    }

    @AuditLog(module = "现场运营", action = "现场班次签到")
    @PostMapping("/shifts/{id}/clock-in")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShift> clockIn(@PathVariable Long id) {
        return Result.ok(service.clockIn(id));
    }

    @AuditLog(module = "现场运营", action = "现场班次签退")
    @PostMapping("/shifts/{id}/clock-out")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShift> clockOut(@PathVariable Long id) {
        return Result.ok(service.clockOut(id));
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<PageResult<SiteTask>> tasks(@Valid SiteOpsQuery query) {
        return Result.ok(service.tasks(query));
    }

    @AuditLog(module = "现场运营", action = "创建现场任务")
    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteTask> createTask(@RequestBody SiteTaskRequest request) {
        return Result.ok(service.createTask(request));
    }

    @AuditLog(module = "现场运营", action = "更新现场任务")
    @PutMapping("/tasks/{id}")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteTask> updateTask(@PathVariable Long id, @RequestBody SiteTaskRequest request) {
        return Result.ok(service.updateTask(id, request));
    }

    @AuditLog(module = "现场运营", action = "开始现场任务")
    @PostMapping("/tasks/{id}/start")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteTask> startTask(@PathVariable Long id) {
        return Result.ok(service.startTask(id));
    }

    @AuditLog(module = "现场运营", action = "完成现场任务")
    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteTask> completeTask(@PathVariable Long id, @RequestBody SiteTaskCompleteRequest request) {
        return Result.ok(service.completeTask(id, request));
    }

    @AuditLog(module = "现场运营", action = "取消现场任务")
    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteTask> cancelTask(@PathVariable Long id, @RequestBody SiteCancelRequest request) {
        return Result.ok(service.cancelTask(id, request));
    }

    @GetMapping("/handovers")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<PageResult<SiteShiftHandover>> handovers(@Valid SiteOpsQuery query) {
        return Result.ok(service.handovers(query));
    }

    @AuditLog(module = "现场运营", action = "提交交接班")
    @PostMapping("/handovers")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShiftHandover> submitHandover(@RequestBody SiteHandoverRequest request) {
        return Result.ok(service.submitHandover(request));
    }

    @AuditLog(module = "现场运营", action = "接收交接班")
    @PostMapping("/handovers/{id}/accept")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteShiftHandover> acceptHandover(@PathVariable Long id) {
        return Result.ok(service.acceptHandover(id));
    }

    @GetMapping("/incidents")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<PageResult<SiteIncidentLog>> incidents(@Valid SiteOpsQuery query) {
        return Result.ok(service.incidents(query));
    }

    @AuditLog(module = "现场运营", action = "记录现场异常")
    @PostMapping("/incidents")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteIncidentLog> recordIncident(@RequestBody SiteIncidentRequest request) {
        return Result.ok(service.recordIncident(request));
    }

    @AuditLog(module = "现场运营", action = "解决现场异常")
    @PutMapping("/incidents/{id}/resolve")
    @PreAuthorize("hasAuthority('site:ops:manage')")
    public Result<SiteIncidentLog> resolveIncident(@PathVariable Long id,
                                                   @RequestBody SiteIncidentResolveRequest request) {
        return Result.ok(service.resolveIncident(id, request));
    }
}
