package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.payment.coupon.CouponService;
import com.ridex.payment.earnings.IncentiveService;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.security.ServicePrincipal;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Everything Phase 15 adds around money, grouped by who uses it. Who may call what is decided in SecurityConfig; the user id always
 * comes from the token, never from the request.
 */
@RestController
public class ExtrasController {

    public record TipRequest(@NotNull BigDecimal amount) {
    }

    public record PayoutRequest(@NotNull BigDecimal amount, @NotBlank String upiId) {
    }

    public record ApplyReferral(@NotBlank String code) {
    }

    public record Decision(String reference, String note) {
    }

    public record Adjustment(@NotNull UUID userId, @NotBlank String kind, @NotNull BigDecimal amount, @NotBlank String reason) {
    }

    private final TipService tips;
    private final ReceiptService receipts;
    private final PayoutService payouts;
    private final ReferralService referrals;
    private final CouponService coupons;
    private final IncentiveService incentives;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    public ExtrasController(TipService tips, ReceiptService receipts, PayoutService payouts, ReferralService referrals, CouponService coupons,
                            IncentiveService incentives, LedgerService ledger, LedgerAccounts accounts) {
        this.tips = tips;
        this.receipts = receipts;
        this.payouts = payouts;
        this.referrals = referrals;
        this.coupons = coupons;
        this.incentives = incentives;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------ rider

    @PostMapping("/api/payments/rides/{rideId}/tip")
    public Map<String, BigDecimal> tip(@AuthenticationPrincipal ServicePrincipal principal, @PathVariable UUID rideId, @Valid @RequestBody TipRequest request) {
        return Map.of("tip", tips.tip(principal.id(), rideId, request.amount()));
    }

    @GetMapping("/api/payments/rides/{rideId}/receipt")
    public ReceiptService.Receipt receipt(@AuthenticationPrincipal ServicePrincipal principal, @PathVariable UUID rideId) {
        return receipts.forRide(principal.id(), principal.isAdmin(), rideId);
    }

    /** Offers a rider can browse (active, in date, not used up, and not already used up by this rider). */
    @GetMapping("/api/coupons/offers")
    public List<CouponService.Offer> offers(@AuthenticationPrincipal ServicePrincipal principal) {
        return coupons.offersFor(principal.id());
    }

    @GetMapping("/api/referrals/me")
    public ReferralService.Summary myReferral(@AuthenticationPrincipal ServicePrincipal principal) {
        return referrals.summary(principal.id(), referrals.codeOf(principal.id()));
    }

    @PostMapping("/api/referrals/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void applyReferral(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody ApplyReferral request) {
        referrals.apply(principal.id(), request.code());
    }

    // ------------------------------------------------------------------ driver

    @GetMapping("/api/driver/incentives")
    public IncentiveService.Progress incentiveProgress(@AuthenticationPrincipal ServicePrincipal principal) {
        return incentives.progress(principal.id());
    }

    @PostMapping("/api/driver/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutService.Payout requestPayout(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody PayoutRequest request) {
        return payouts.request(principal.id(), request.amount(), request.upiId());
    }

    @GetMapping("/api/driver/payouts")
    public List<PayoutService.Payout> myPayouts(@AuthenticationPrincipal ServicePrincipal principal) {
        return payouts.mine(principal.id());
    }

    // ------------------------------------------------------------------ administrator

    @GetMapping("/api/admin/payouts")
    public PageResponse<PayoutService.Payout> payoutList(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return payouts.list(status, page, size);
    }

    @PostMapping("/api/admin/payouts/{id}/paid")
    public PayoutService.Payout paid(@AuthenticationPrincipal ServicePrincipal admin, @PathVariable UUID id, @RequestBody Decision decision) {
        return payouts.markPaid(admin.id(), id, decision.reference());
    }

    @PostMapping("/api/admin/payouts/{id}/reject")
    public PayoutService.Payout reject(@AuthenticationPrincipal ServicePrincipal admin, @PathVariable UUID id, @RequestBody Decision decision) {
        return payouts.reject(admin.id(), id, decision.note());
    }

    /**
     * A manual correction by an administrator (a goodwill credit, fixing a mistake). It is a normal ledger transaction against the
     * ADJUSTMENT account, so it is audited like everything else and cannot make a wallet negative.
     */
    @PostMapping("/api/admin/ledger/adjustments")
    @Transactional
    public Map<String, Object> adjust(@AuthenticationPrincipal ServicePrincipal admin, @Valid @RequestBody Adjustment request) {
        if (request.amount().signum() == 0 || request.amount().scale() > 2 || request.amount().abs().compareTo(new BigDecimal("100000")) > 0) {
            throw new BadRequestException("Enter a non-zero amount up to 100000 with at most 2 decimals");
        }
        UUID account = switch (request.kind()) {
            case "WALLET" -> accounts.walletOf(request.userId());
            case "EARNINGS" -> accounts.earningsOf(request.userId());
            default -> throw new BadRequestException("kind must be WALLET or EARNINGS");
        };
        String reason = request.reason().trim();
        String by = " (admin " + admin.id().toString().substring(0, 8) + ")";
        LedgerService.Posting posting = ledger.post("ADJUSTMENT", request.userId().toString(), "adjust-" + UUID.randomUUID(),
                (reason.length() > 150 ? reason.substring(0, 150) : reason) + by,
                List.of(new LedgerService.Leg(account, request.amount()), new LedgerService.Leg(AccountType.ADJUSTMENT_ID, request.amount().negate())));
        if (!posting.done()) {
            throw new BadRequestException("That would take the balance below zero");
        }
        return Map.of("status", "POSTED", "transactionId", posting.transactionId());
    }

    @PostMapping("/api/admin/coupons/{id}/listed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setListed(@PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        coupons.setListed(id, Boolean.TRUE.equals(body.get("listed")));
    }
}
