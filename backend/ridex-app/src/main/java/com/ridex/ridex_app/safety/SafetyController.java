package com.ridex.ridex_app.safety;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Trip sharing, SOS and in-trip chat. Who may call what is decided in SecurityConfig; the person always comes from the token. */
@RestController
public class SafetyController {

    public record SosRequest(@DecimalMin("-90") @DecimalMax("90") BigDecimal latitude, @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    public record MessageRequest(@NotBlank @Size(max = 500) String body) {
    }

    public record Decision(@Size(max = 300) String note) {
    }

    private final SafetyService safety;
    private final RideChatService chat;

    public SafetyController(SafetyService safety, RideChatService chat) {
        this.safety = safety;
        this.chat = chat;
    }

    private static ActorRole role(AuthenticatedUser user) {
        return ActorRole.valueOf(user.role().name());
    }

    @PostMapping("/api/rides/{rideId}/share")
    public SafetyService.ShareLink share(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID rideId) {
        return safety.shareLink(user.id(), role(user), rideId);
    }

    /** No login: the unguessable token is the key. Never cached (the car moves). */
    @GetMapping("/api/public/trips/{token}")
    public ResponseEntity<SafetyService.TripView> publicTrip(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(safety.publicTrip(token));
    }

    @PostMapping("/api/rides/{rideId}/sos")
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyService.SosView sos(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID rideId, @Valid @RequestBody(required = false) SosRequest request) {
        return safety.raiseSos(user.id(), role(user), rideId, request == null ? null : request.latitude(), request == null ? null : request.longitude());
    }

    @GetMapping("/api/rides/{rideId}/messages")
    public List<RideChatService.Message> messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID rideId) {
        return chat.list(user.id(), role(user), rideId);
    }

    @PostMapping("/api/rides/{rideId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public RideChatService.Message send(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID rideId, @Valid @RequestBody MessageRequest request) {
        return chat.send(user.id(), role(user), rideId, request.body());
    }

    // ------------------------------------------------------------------ administrators (/api/admin/** is ADMIN only)

    @GetMapping("/api/admin/sos")
    public PageResponse<SafetyService.SosView> alerts(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return safety.list(status, page, size);
    }

    @PostMapping("/api/admin/sos/{id}/acknowledge")
    public SafetyService.SosView acknowledge(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody(required = false) Decision decision) {
        return safety.decide(admin.id(), id, "ACKNOWLEDGED", decision == null ? null : decision.note());
    }

    @PostMapping("/api/admin/sos/{id}/resolve")
    public SafetyService.SosView resolve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody(required = false) Decision decision) {
        return safety.decide(admin.id(), id, "RESOLVED", decision == null ? null : decision.note());
    }
}
