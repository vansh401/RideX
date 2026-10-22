package com.ridex.payment.earnings;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;

/** A driver sees ONLY their own earnings: the id comes from the verified token, never from the URL. */
@RestController
public class EarningsController {

    private final EarningsService earnings;

    public EarningsController(EarningsService earnings) {
        this.earnings = earnings;
    }

    @GetMapping("/api/driver/earnings")
    public EarningsService.Summary summary(@AuthenticationPrincipal ServicePrincipal principal) {
        return earnings.summary(principal.id());
    }
}
