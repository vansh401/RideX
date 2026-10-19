package com.ridex.ridex_app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class AdminApiIntegrationTest extends AbstractApiTest {

    private String loginBody(User user) {
        return json("{'email':'%s','password':'%s'}", user.getEmail(), PASSWORD);
    }

    @Test
    void usersCanBeSearchedFilteredAndPaged() {
        Person admin = newAdmin();
        User ada = userRepository.save(activeNamed("Zxqwv Findable", Role.RIDER));
        activeNamed("Someone Else", Role.RIDER);
        userRepository.save(activeNamed("Zxqwv Findable Driver", Role.DRIVER));

        // search by part of the name, case-insensitively
        Response byName = expect(200, api().get("/api/admin/users?q=zXQwV", admin.token()));
        assertThat(byName.<Integer>json("$.totalElements")).isEqualTo(2);
        // search by email
        Response byEmail = expect(200, api().get("/api/admin/users?q=" + ada.getEmail(), admin.token()));
        assertThat(byEmail.<List<String>>json("$.content[*].email")).containsExactly(ada.getEmail());
        // filter by role
        Response riders = expect(200, api().get("/api/admin/users?q=zxqwv&role=DRIVER", admin.token()));
        assertThat(riders.<Integer>json("$.totalElements")).isEqualTo(1);
        // wildcard characters typed by the admin are searched literally, not as patterns
        assertThat(expect(200, api().get("/api/admin/users?q=%25", admin.token())).<Integer>json("$.totalElements")).isZero();
        // paging
        Response page = expect(200, api().get("/api/admin/users?size=1&page=0", admin.token()));
        assertThat(page.<List<Object>>json("$.content")).hasSize(1);
        assertThat(page.<Integer>json("$.size")).isEqualTo(1);
        // the response never contains password hashes
        assertThat(byName.body()).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    private User activeNamed(String name, Role role) {
        User user = createUser(role);
        // rename through SQL: the User entity has no setter for names (by design)
        jdbc.update("update users set full_name = ? where id = ?::uuid", name, user.getId());
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Test
    void suspendingAUserLocksThemOutEverywhere() {
        Person admin = newAdmin();
        User rider = createUser(Role.RIDER);
        String riderToken = tokenOf(rider);
        String refreshToken = api().post("/api/auth/login", null, loginBody(rider)).json("$.refreshToken");
        // before: everything works
        expect(200, api().get("/api/users/me", riderToken));

        // a reason is required
        expect(400, api().post("/api/admin/users/" + rider.getId() + "/suspend", admin.token(), "{}"));
        Response suspended = expect(200, api().post("/api/admin/users/" + rider.getId() + "/suspend", admin.token(),
                "{\"reason\":\"Reported for abuse\"}"));
        assertThat(suspended.<String>json("$.status")).isEqualTo("SUSPENDED");

        // after: cannot log in, cannot refresh, and the token they already hold is dead immediately
        Response login = api().post("/api/auth/login", null, loginBody(rider));
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.<String>json("$.message")).contains("suspended");
        expect(401, api().post("/api/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}"));
        // Since Phase 4 the token itself is cancelled in Redis, so the answer is 401 (not authenticated),
        // not 403 (authenticated but not allowed). Before, the old token still got through the filter until it expired.
        expect(401, api().get("/api/users/me", riderToken));
        expect(401, api().post("/api/rides", riderToken, rideBody("CAB", 22.0, 76.0)));

        // reinstating restores access
        expect(200, api().post("/api/admin/users/" + rider.getId() + "/reinstate", admin.token(), null));
        assertThat(api().post("/api/auth/login", null, loginBody(rider)).status()).isEqualTo(200);
        // reinstating someone who is not suspended is refused
        expect(409, api().post("/api/admin/users/" + rider.getId() + "/reinstate", admin.token(), null));
    }

    @Test
    void aSuspendedDriverIsTakenOfflineAndNoLongerOfferedRides() {
        Person admin = newAdmin();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, 23.0, 76.0);
        Person rider = newRider();

        expect(200, api().post("/api/admin/users/" + driver.user().getId() + "/suspend", admin.token(), "{\"reason\":\"Unsafe\"}"));

        assertThat(api().get("/api/admin/drivers/" + driver.profileId(), admin.token()).<String>json("$.email"))
                .isEqualTo(driver.user().getEmail());
        Response ride = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", 23.0, 76.0)));
        assertThat(ride.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");   // nobody to offer it to
        expect(200, api().post("/api/rides/" + ride.id() + "/cancel", rider.token(), null));
    }

    @Test
    void adminSafetyRules() {
        Person admin = newAdmin();
        Person otherAdmin = newAdmin();
        User pending = new User("Pending Person", "pending" + System.nanoTime() + "@test.ridex", "+9190001" + (System.nanoTime() % 100000),
                passwordEncoder.encode(PASSWORD), Role.RIDER);
        userRepository.save(pending);

        expect(409, api().post("/api/admin/users/" + admin.user().getId() + "/suspend", admin.token(), "{\"reason\":\"x\"}"));   // not yourself
        expect(409, api().post("/api/admin/users/" + otherAdmin.user().getId() + "/suspend", admin.token(), "{\"reason\":\"x\"}")); // not another admin
        expect(409, api().post("/api/admin/users/" + pending.getId() + "/suspend", admin.token(), "{\"reason\":\"x\"}"));   // only ACTIVE accounts
        // reinstating must never be a way around email verification
        expect(409, api().post("/api/admin/users/" + pending.getId() + "/reinstate", admin.token(), null));
        expect(404, api().post("/api/admin/users/" + java.util.UUID.randomUUID() + "/suspend", admin.token(), "{\"reason\":\"x\"}"));
    }

    @Test
    void overviewVehiclesAndRidesListsWork() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.AUTO);

        Response stats = expect(200, api().get("/api/admin/stats", admin.token()));
        assertThat(stats.<Integer>json("$.usersByRole.ADMIN")).isGreaterThanOrEqualTo(1);
        assertThat(stats.<Integer>json("$.usersByRole.DRIVER")).isGreaterThanOrEqualTo(1);
        assertThat(stats.<String>json("$.currency")).isEqualTo("INR");
        assertThat(stats.<Object>json("$.ridesByStatus.CANCELLED")).isNotNull();   // every status is present, even at 0

        Response autos = expect(200, api().get("/api/admin/vehicles?category=AUTO&size=100", admin.token()));
        assertThat(autos.<List<String>>json("$.content[*].id")).contains(driver.vehicleId());
        assertThat(autos.<List<String>>json("$.content[*].category")).containsOnly("AUTO");
        assertThat(autos.<List<String>>json("$.content[?(@.id == '" + driver.vehicleId() + "')].driverName"))
                .containsExactly(driver.user().getFullName());

        expect(200, api().get("/api/admin/rides?size=5", admin.token()));
        expect(400, api().get("/api/admin/rides?status=NOT_A_STATUS", admin.token()));
    }
}
