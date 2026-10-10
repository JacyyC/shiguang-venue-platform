package com.example.venue.member.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.member.dto.MemberRewardCatalogResponse;
import com.example.venue.member.dto.MemberRewardCompensateRequest;
import com.example.venue.member.dto.MemberRewardEventResponse;
import com.example.venue.member.dto.MemberRewardRedeemRequest;
import com.example.venue.member.dto.MemberRewardRedemptionResponse;
import com.example.venue.member.dto.PublishMemberRewardVersionRequest;
import com.example.venue.member.dto.SaveMemberRewardCatalogItemRequest;
import com.example.venue.member.dto.UpdateMemberRewardCatalogStatusRequest;
import com.example.venue.member.service.MemberRewardService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/member-rewards")
public class MemberRewardController {
    private final MemberRewardService service;

    public MemberRewardController(MemberRewardService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasAuthority('member-reward:me')")
    public Result<List<MemberRewardCatalogResponse>> catalog() {
        return Result.ok(service.catalog());
    }

    @GetMapping("/catalog/{itemId}/preview")
    @PreAuthorize("hasAuthority('member-reward:me')")
    public Result<MemberRewardCatalogResponse> preview(@PathVariable Long itemId) {
        return Result.ok(service.preview(itemId));
    }

    @AuditLog(module = "积分权益", action = "确认兑换")
    @PostMapping("/catalog/{itemId}/redemptions")
    @PreAuthorize("hasAuthority('member-reward:me')")
    public Result<MemberRewardRedemptionResponse> redeem(@PathVariable Long itemId,
                                                         @Valid @RequestBody MemberRewardRedeemRequest request) {
        return Result.ok(service.redeem(itemId, request.catalogVersionId, request.idempotencyKey));
    }

    @GetMapping("/redemptions")
    @PreAuthorize("hasAuthority('member-reward:me')")
    public Result<PageResult<MemberRewardRedemptionResponse>> history(@Valid PageQuery query,
                                                                      @RequestParam(required = false) String status) {
        return Result.ok(service.history(query, status));
    }

    @AuditLog(module = "积分权益", action = "新增兑换目录")
    @PostMapping("/admin/catalog")
    @PreAuthorize("hasAuthority('member-reward:catalog:manage')")
    public Result<MemberRewardCatalogResponse> createCatalogItem(
        @Valid @RequestBody SaveMemberRewardCatalogItemRequest request) {
        return Result.ok(service.createCatalogItem(request));
    }

    @GetMapping("/admin/catalog")
    @PreAuthorize("hasAuthority('member-reward:catalog:manage')")
    public Result<List<MemberRewardCatalogResponse>> listCatalogItems() {
        return Result.ok(service.listCatalogItems());
    }

    @AuditLog(module = "积分权益", action = "修改兑换目录状态")
    @PutMapping("/admin/catalog/{itemId}/status")
    @PreAuthorize("hasAuthority('member-reward:catalog:manage')")
    public Result<MemberRewardCatalogResponse> updateCatalogItemStatus(
        @PathVariable Long itemId, @Valid @RequestBody UpdateMemberRewardCatalogStatusRequest request) {
        return Result.ok(service.setCatalogItemStatus(itemId, request.status));
    }

    @AuditLog(module = "积分权益", action = "发布兑换版本")
    @PostMapping("/admin/catalog/{itemId}/versions")
    @PreAuthorize("hasAuthority('member-reward:catalog:manage')")
    public Result<MemberRewardCatalogResponse> publishVersion(
        @PathVariable Long itemId, @Valid @RequestBody PublishMemberRewardVersionRequest request) {
        return Result.ok(service.publishVersion(itemId, request));
    }

    @GetMapping("/admin/exceptions")
    @PreAuthorize("hasAuthority('member-reward:exception:manage')")
    public Result<PageResult<MemberRewardRedemptionResponse>> exceptions(
        @Valid PageQuery query, @RequestParam(required = false) String status) {
        return Result.ok(service.exceptions(query, status));
    }

    @AuditLog(module = "积分权益", action = "重试兑换异常")
    @PostMapping("/admin/redemptions/{id}/retry")
    @PreAuthorize("hasAuthority('member-reward:exception:manage')")
    public Result<MemberRewardRedemptionResponse> retry(@PathVariable Long id) {
        return Result.ok(service.retry(id));
    }

    @AuditLog(module = "积分权益", action = "补偿兑换异常")
    @PostMapping("/admin/redemptions/{id}/compensate")
    @PreAuthorize("hasAuthority('member-reward:exception:manage')")
    public Result<MemberRewardRedemptionResponse> compensate(
        @PathVariable Long id, @Valid @RequestBody MemberRewardCompensateRequest request) {
        return Result.ok(service.compensate(id, request.reason));
    }

    @GetMapping("/admin/events")
    @PreAuthorize("hasAuthority('member-reward:audit:view')")
    public Result<PageResult<MemberRewardEventResponse>> events(@Valid PageQuery query) {
        return Result.ok(service.events(query));
    }
}
