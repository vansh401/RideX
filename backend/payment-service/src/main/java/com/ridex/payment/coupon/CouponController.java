package com.ridex.payment.coupon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
public class CouponController {

    public record NewCouponRequest(@NotBlank String code, String description, @NotBlank String discountType, @NotNull BigDecimal value,
                                   BigDecimal maxDiscount, BigDecimal minFare, @NotNull Instant validFrom, @NotNull Instant validUntil,
                                   Integer maxTotalUses, Integer perUserLimit) {
    }

    public record ActiveRequest(boolean active) {
    }

    private final CouponService coupons;

    public CouponController(CouponService coupons) {
        this.coupons = coupons;
    }

    /** For riders: "what would this code give me on this fare?" Nothing is used up. */
    @GetMapping("/api/coupons/{code}/preview")
    public CouponService.Evaluation preview(@AuthenticationPrincipal ServicePrincipal principal, @PathVariable String code, @RequestParam BigDecimal fare) {
        return coupons.preview(code, principal.id(), fare);
    }

    @PostMapping("/api/admin/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponService.Coupon create(@Valid @RequestBody NewCouponRequest r) {
        return coupons.create(new CouponService.NewCoupon(r.code(), r.description(), r.discountType(), r.value(), r.maxDiscount(), r.minFare(),
                r.validFrom(), r.validUntil(), r.maxTotalUses(), r.perUserLimit()));
    }

    @GetMapping("/api/admin/coupons")
    public List<CouponService.Coupon> list() {
        return coupons.list();
    }

    @PatchMapping("/api/admin/coupons/{id}/active")
    public void setActive(@PathVariable UUID id, @RequestBody ActiveRequest request) {
        coupons.setActive(id, request.active());
    }
}
