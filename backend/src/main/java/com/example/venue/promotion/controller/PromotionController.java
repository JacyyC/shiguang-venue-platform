package com.example.venue.promotion.controller;

import com.example.venue.common.AuditLog;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.promotion.dto.CouponTemplateResponse;
import com.example.venue.promotion.dto.PriceRuleResponse;
import com.example.venue.promotion.dto.PromotionCampaignResponse;
import com.example.venue.promotion.dto.PromotionOverviewResponse;
import com.example.venue.promotion.dto.SaveCouponTemplateRequest;
import com.example.venue.promotion.dto.SavePriceRuleRequest;
import com.example.venue.promotion.dto.SavePromotionCampaignRequest;
import com.example.venue.promotion.dto.UserCouponResponse;
import com.example.venue.promotion.service.PromotionAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PromotionController {
    private final PromotionAdminService promotionAdminService;

    public PromotionController(PromotionAdminService promotionAdminService) {
        this.promotionAdminService = promotionAdminService;
    }

    @GetMapping("/api/admin/promotions/campaigns")
    @PreAuthorize("hasAuthority('promotion:manage')")
    public Result<PageResult<PromotionCampaignResponse>> campaigns(@Valid PageQuery query) {
        return Result.ok(promotionAdminService.campaigns(query));
    }

    @AuditLog(module = "营销活动", action = "新增营销活动")
    @PostMapping("/api/admin/promotions/campaigns")
    @PreAuthorize("hasAuthority('promotion:manage')")
    public Result<PromotionCampaignResponse> createCampaign(@Valid @RequestBody SavePromotionCampaignRequest request) {
        return Result.ok(promotionAdminService.saveCampaign(request));
    }

    @AuditLog(module = "营销活动", action = "修改营销活动")
    @PutMapping("/api/admin/promotions/campaigns/{id}")
    @PreAuthorize("hasAuthority('promotion:manage')")
    public Result<PromotionCampaignResponse> updateCampaign(@PathVariable Long id,
                                                            @Valid @RequestBody SavePromotionCampaignRequest request) {
        return Result.ok(promotionAdminService.updateCampaign(id, request));
    }

    @AuditLog(module = "营销活动", action = "修改营销活动状态")
    @PutMapping("/api/admin/promotions/campaigns/{id}/status")
    @PreAuthorize("hasAuthority('promotion:manage')")
    public Result<Void> updateCampaignStatus(@PathVariable Long id, @RequestParam String status) {
        promotionAdminService.updateCampaignStatus(id, status);
        return Result.ok();
    }

    @GetMapping("/api/admin/promotions/coupon-templates")
    @PreAuthorize("hasAuthority('coupon:manage')")
    public Result<PageResult<CouponTemplateResponse>> couponTemplates(
        @Valid PageQuery query, @RequestParam(required = false) String status) {
        return Result.ok(promotionAdminService.couponTemplates(query, status));
    }

    @AuditLog(module = "优惠券模板", action = "新增优惠券模板")
    @PostMapping("/api/admin/promotions/coupon-templates")
    @PreAuthorize("hasAuthority('coupon:manage')")
    public Result<CouponTemplateResponse> createCouponTemplate(@Valid @RequestBody SaveCouponTemplateRequest request) {
        return Result.ok(promotionAdminService.saveCouponTemplate(request));
    }

    @AuditLog(module = "优惠券模板", action = "修改优惠券模板")
    @PutMapping("/api/admin/promotions/coupon-templates/{id}")
    @PreAuthorize("hasAuthority('coupon:manage')")
    public Result<CouponTemplateResponse> updateCouponTemplate(@PathVariable Long id,
                                                               @Valid @RequestBody SaveCouponTemplateRequest request) {
        return Result.ok(promotionAdminService.updateCouponTemplate(id, request));
    }

    @AuditLog(module = "优惠券模板", action = "修改优惠券模板状态")
    @PutMapping("/api/admin/promotions/coupon-templates/{id}/status")
    @PreAuthorize("hasAuthority('coupon:manage')")
    public Result<Void> updateCouponTemplateStatus(@PathVariable Long id, @RequestParam String status) {
        promotionAdminService.updateCouponTemplateStatus(id, status);
        return Result.ok();
    }

    @GetMapping("/api/admin/promotions/coupon-redemptions")
    @PreAuthorize("hasAuthority('coupon:manage')")
    public Result<PageResult<UserCouponResponse>> couponRedemptions(@Valid PageQuery query,
                                                                    @RequestParam(required = false) String status) {
        return Result.ok(promotionAdminService.couponRedemptions(query, status));
    }

    @GetMapping("/api/admin/promotions/price-rules")
    @PreAuthorize("hasAuthority('pricing:manage')")
    public Result<PageResult<PriceRuleResponse>> priceRules(@Valid PageQuery query) {
        return Result.ok(promotionAdminService.priceRules(query));
    }

    @AuditLog(module = "定价规则", action = "新增定价规则")
    @PostMapping("/api/admin/promotions/price-rules")
    @PreAuthorize("hasAuthority('pricing:manage')")
    public Result<PriceRuleResponse> createPriceRule(@Valid @RequestBody SavePriceRuleRequest request) {
        return Result.ok(promotionAdminService.savePriceRule(request));
    }

    @AuditLog(module = "定价规则", action = "修改定价规则")
    @PutMapping("/api/admin/promotions/price-rules/{id}")
    @PreAuthorize("hasAuthority('pricing:manage')")
    public Result<PriceRuleResponse> updatePriceRule(@PathVariable Long id,
                                                     @Valid @RequestBody SavePriceRuleRequest request) {
        return Result.ok(promotionAdminService.updatePriceRule(id, request));
    }

    @AuditLog(module = "定价规则", action = "修改定价规则状态")
    @PutMapping("/api/admin/promotions/price-rules/{id}/status")
    @PreAuthorize("hasAuthority('pricing:manage')")
    public Result<Void> updatePriceRuleStatus(@PathVariable Long id, @RequestParam String status) {
        promotionAdminService.updatePriceRuleStatus(id, status);
        return Result.ok();
    }

    @GetMapping("/api/admin/promotions/overview")
    @PreAuthorize("hasAuthority('promotion:manage')")
    public Result<PromotionOverviewResponse> overview() {
        return Result.ok(promotionAdminService.overview());
    }
}
