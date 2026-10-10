package com.example.venue.promotion.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.promotion.dto.CouponTemplateResponse;
import com.example.venue.promotion.dto.UserCouponResponse;
import com.example.venue.promotion.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CouponController {
    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/api/coupons/claimable")
    @PreAuthorize("hasAuthority('coupon:me')")
    public Result<List<CouponTemplateResponse>> claimable(@RequestParam(required = false) Long venueId,
                                                          @RequestParam(required = false) Long spaceId) {
        return Result.ok(couponService.claimable(venueId, spaceId));
    }

    @GetMapping("/api/coupons/my")
    @PreAuthorize("hasAuthority('coupon:me')")
    public Result<PageResult<UserCouponResponse>> myCoupons(@Valid PageQuery query,
                                                            @RequestParam(required = false) String status) {
        return Result.ok(couponService.myCoupons(query, status));
    }

    @AuditLog(module = "优惠券", action = "领取优惠券")
    @PostMapping("/api/coupons/{templateId}/claim")
    @PreAuthorize("hasAuthority('coupon:me')")
    public Result<UserCouponResponse> claim(@PathVariable Long templateId) {
        return Result.ok(couponService.claim(templateId));
    }
}
