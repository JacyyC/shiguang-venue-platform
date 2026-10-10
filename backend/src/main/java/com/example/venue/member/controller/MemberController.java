package com.example.venue.member.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.member.dto.MemberAccountResponse;
import com.example.venue.member.dto.MemberAccountSummary;
import com.example.venue.member.dto.MemberLevelResponse;
import com.example.venue.member.dto.MemberOverviewResponse;
import com.example.venue.member.dto.SaveMemberLevelRequest;
import com.example.venue.member.dto.UpdateMemberLevelStatusRequest;
import com.example.venue.member.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MemberController {
    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/api/admin/members/levels")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<List<MemberLevelResponse>> levels() {
        return Result.ok(memberService.levels());
    }

    @AuditLog(module = "会员管理", action = "新增会员等级")
    @PostMapping("/api/admin/members/levels")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<MemberLevelResponse> createLevel(@Valid @RequestBody SaveMemberLevelRequest request) {
        return Result.ok(memberService.saveLevel(request));
    }

    @AuditLog(module = "会员管理", action = "修改会员等级")
    @PutMapping("/api/admin/members/levels/{id}")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<MemberLevelResponse> updateLevel(@PathVariable Long id, @Valid @RequestBody SaveMemberLevelRequest request) {
        return Result.ok(memberService.updateLevel(id, request));
    }

    @AuditLog(module = "会员管理", action = "修改会员等级状态")
    @PutMapping("/api/admin/members/levels/{id}/status")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<Void> updateLevelStatus(@PathVariable Long id, @Valid @RequestBody UpdateMemberLevelStatusRequest request) {
        memberService.updateLevelStatus(id, request.status);
        return Result.ok();
    }

    @GetMapping("/api/admin/members/accounts")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<PageResult<MemberAccountSummary>> accounts(@Valid PageQuery query,
                                                             @RequestParam(required = false) String keyword) {
        return Result.ok(memberService.accounts(query, keyword));
    }

    @GetMapping("/api/admin/members/accounts/{userId}")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<MemberAccountResponse> account(@PathVariable Long userId) {
        return Result.ok(memberService.account(userId));
    }

    @GetMapping("/api/admin/members/overview")
    @PreAuthorize("hasAuthority('member:manage')")
    public Result<MemberOverviewResponse> overview() {
        return Result.ok(memberService.overview());
    }

    @GetMapping("/api/members/me")
    @PreAuthorize("hasAuthority('member:me')")
    public Result<MemberAccountResponse> me() {
        return Result.ok(memberService.currentAccount());
    }
}
