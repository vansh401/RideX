package com.ridex.payment.wallet;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;
import com.ridex.platform.web.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The rider's wallet. Who may call is decided in SecurityConfig (riders only). */
@RestController
public class WalletController {

    public record TopUpRequest(@NotNull BigDecimal amount) {
    }

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/api/wallet")
    public WalletService.Wallet wallet(@AuthenticationPrincipal ServicePrincipal principal) {
        return wallets.wallet(principal.id());
    }

    /** The Idempotency-Key header makes pressing the button twice harmless. */
    @PostMapping("/api/wallet/topups")
    public WalletService.Wallet topUp(@AuthenticationPrincipal ServicePrincipal principal, @Valid @RequestBody TopUpRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return wallets.topUp(principal.id(), request.amount(), idempotencyKey);
    }

    @GetMapping("/api/wallet/transactions")
    public PageResponse<WalletService.Entry> statement(@AuthenticationPrincipal ServicePrincipal principal,
                                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return wallets.statement(principal.id(), page, size);
    }
}
