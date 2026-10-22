package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.support.AbstractIntegrationTest;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/**
 * "Who may call what", written as a table. Every protected endpoint is called with no login,
 * and as a rider, a driver and an admin, and the status code is checked.
 *
 * The users exist only inside their tokens (not in the database). That is deliberate: a 404 or 400
 * from the controller (rather than 401/403) proves the request got PAST the security rules and
 * reached the controller, which is exactly what "allowed" means here.
 *
 * Reading the codes:  401 not logged in | 403 logged in, wrong role | 404/400/204 allowed, and the
 * controller then answered about the (non-existent) data or the (empty) body.
 */
class PermissionMatrixIntegrationTest extends AbstractIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtService jwtService;

    private final HttpClient client = HttpClient.newHttpClient();

    private String tokenFor(Role role) {
        User user = new User("Test Person", "test@example.com", "+910000000000", "hash", role);
        return jwtService.createAccessToken(user).value();
    }

    private int call(String method, String path, String role) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, "GET".equals(method) || "DELETE".equals(method)
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"));
        if (!"NONE".equals(role)) {
            builder.header("Authorization", "Bearer " + tokenFor(Role.valueOf(role)));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    // method, path, role, expected status
    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            // ---- admin area: only ADMIN ----
            "GET,/api/admin/stats,NONE,401",
            "GET,/api/admin/stats,RIDER,403",
            "GET,/api/admin/stats,DRIVER,403",
            "GET,/api/admin/stats,ADMIN,200",
            "GET,/api/admin/users,RIDER,403",
            "GET,/api/admin/users,ADMIN,200",
            "GET,/api/admin/rides,DRIVER,403",
            "GET,/api/admin/rides,ADMIN,200",
            "GET,/api/admin/drivers,RIDER,403",
            "GET,/api/admin/drivers,ADMIN,200",
            "GET,/api/admin/vehicles,DRIVER,403",
            "GET,/api/admin/vehicles,ADMIN,200",
            "POST,/api/admin/users/00000000-0000-0000-0000-000000000001/suspend,RIDER,403",
            "POST,/api/admin/users/00000000-0000-0000-0000-000000000001/suspend,ADMIN,400",
            "POST,/api/admin/drivers/00000000-0000-0000-0000-000000000001/approve,DRIVER,403",
            "POST,/api/admin/drivers/00000000-0000-0000-0000-000000000001/approve,ADMIN,404",

            // ---- rider area: only RIDER ----
            "GET,/api/riders/me,NONE,401",
            "GET,/api/riders/me,DRIVER,403",
            "GET,/api/riders/me,ADMIN,403",
            "GET,/api/riders/me,RIDER,404",
            "POST,/api/rides,DRIVER,403",
            "POST,/api/rides,ADMIN,403",
            "POST,/api/rides,RIDER,400",

            // ---- driver area: only DRIVER ----
            "GET,/api/drivers/me,NONE,401",
            "GET,/api/drivers/me,RIDER,403",
            "GET,/api/drivers/me,ADMIN,403",
            "GET,/api/drivers/me,DRIVER,404",
            "GET,/api/drivers/me/vehicles,RIDER,403",
            "GET,/api/drivers/me/vehicles,DRIVER,404",
            "GET,/api/drivers/me/documents,RIDER,403",
            "GET,/api/drivers/me/documents,DRIVER,404",
            "GET,/api/drivers/me/availability,RIDER,403",
            "GET,/api/drivers/me/availability,DRIVER,404",
            "POST,/api/drivers/me/availability/online,RIDER,403",
            "POST,/api/drivers/me/availability/online,DRIVER,400",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/accept,RIDER,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/accept,ADMIN,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/complete,RIDER,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/stops/next,RIDER,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/stops/next,ADMIN,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/stops/next,NONE,401",

            // ---- Phase 14: complaints (riders and drivers file them, administrators handle them) ----
            "GET,/api/complaints,NONE,401",
            "GET,/api/complaints,ADMIN,403",
            "GET,/api/complaints,RIDER,200",
            "GET,/api/complaints,DRIVER,200",
            "POST,/api/complaints,ADMIN,403",
            "GET,/api/admin/complaints,NONE,401",
            "GET,/api/admin/complaints,RIDER,403",
            "GET,/api/admin/complaints,DRIVER,403",
            "GET,/api/admin/complaints,ADMIN,200",
            "POST,/api/admin/complaints/00000000-0000-0000-0000-000000000001/resolve,RIDER,403",

            // ---- shared rides endpoints ----
            "GET,/api/rides,NONE,401",
            "GET,/api/rides,ADMIN,403",
            "GET,/api/rides/current,NONE,401",
            "GET,/api/rides/current,ADMIN,403",
            "GET,/api/rides/current,RIDER,204",
            "GET,/api/rides/current,DRIVER,404",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001,NONE,401",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001,RIDER,404",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001,ADMIN,404",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/cancel,ADMIN,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/ratings,ADMIN,403",
            "POST,/api/rides/00000000-0000-0000-0000-000000000001/ratings,RIDER,400",

            // ---- live driver position: the ride's rider, driver or an admin (404 hides rides that are not yours) ----
            "GET,/api/rides/00000000-0000-0000-0000-000000000001/driver-location,NONE,401",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001/driver-location,RIDER,404",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001/driver-location,DRIVER,404",
            "GET,/api/rides/00000000-0000-0000-0000-000000000001/driver-location,ADMIN,404",

            // ---- the core's outbox: admins only. (Notifications, payments, fares and the event admin pages moved to other
            //      services; their permission tests live with them.) ----
            "GET,/api/admin/outbox,NONE,401",
            "GET,/api/admin/outbox,RIDER,403",
            "GET,/api/admin/outbox,DRIVER,403",
            "GET,/api/admin/outbox,ADMIN,200",

            // ---- the public key other services use to verify our tokens: public by design ----
            "GET,/.well-known/jwks.json,NONE,200",

            // ---- Kubernetes probes: public, and they show no details ----
            "GET,/actuator/health/liveness,NONE,200",
            "GET,/actuator/health/readiness,NONE,200",
            "GET,/actuator/prometheus,NONE,200",
            "GET,/actuator/env,ADMIN,404",
            "GET,/actuator/env,NONE,401",

            // ---- paths that moved to other services are not served by the core any more ----
            "GET,/api/notifications,ADMIN,404",
            "GET,/api/payments/rides/00000000-0000-0000-0000-000000000001,ADMIN,404",
            "POST,/api/fares/estimate,RIDER,404",
            "GET,/api/admin/events/lag,ADMIN,404",
    })
    void eachRoleSeesExactlyWhatItShould(String method, String path, String role, int expected) throws Exception {
        assertThat(call(method, path, role)).isEqualTo(expected);
    }

    @Test
    void idsInUrlsMustBeRealUuids() throws Exception {
        // A malformed id is a 400 from validation, not a 500 and not a leak
        assertThat(call("GET", "/api/rides/not-a-uuid", "RIDER")).isEqualTo(400);
        assertThat(call("GET", "/api/rides/" + UUID.randomUUID(), "RIDER")).isEqualTo(404);
    }
}
