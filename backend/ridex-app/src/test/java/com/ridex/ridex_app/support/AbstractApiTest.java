package com.ridex.ridex_app.support;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;

/**
 * Helpers for tests that drive the application through its real HTTP API.
 *
 * Users are inserted directly into the database (already verified, with a real BCrypt hash) and
 * then log in through the real /api/auth/login, so the whole security stack is exercised without
 * having to read verification emails.
 */
public abstract class AbstractApiTest extends AbstractIntegrationTest {

    protected static final String PASSWORD = "Secret123";

    // Unique values per test run, because all test classes share one database
    private static final AtomicInteger COUNTER = new AtomicInteger(1000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ApiClient api;

    /** The port the application listens on in this test (for WebSocket clients). */
    protected int serverPort() {
        return port;
    }

    protected ApiClient api() {
        if (api == null) {
            api = new ApiClient(port);
        }
        return api;
    }

    /** A verified, active account. Returns the user; log in with PASSWORD. */
    protected User createUser(Role role) {
        int n = COUNTER.incrementAndGet();
        User user = new User(role.name().charAt(0) + role.name().substring(1).toLowerCase() + " " + n,
                "user" + n + "@test.ridex", "+9190000" + String.format("%05d", n), passwordEncoder.encode(PASSWORD), role);
        user.activate();
        return userRepository.save(user);
    }

    protected String tokenOf(User user) {
        return api().login(user.getEmail(), PASSWORD);
    }

    protected static String uniqueLicence() {
        return "DL-" + COUNTER.incrementAndGet() + "-" + (System.nanoTime() % 100000);
    }

    protected static String uniqueRegistration() {
        // Matches the Indian format the API requires: two letters, digits, letters, four digits
        return "KA01AB" + String.format("%04d", COUNTER.incrementAndGet() % 10000);
    }

    protected static byte[] pdf() {
        return "%PDF-1.7 test licence".getBytes(StandardCharsets.US_ASCII);
    }

    protected static String json(String template, Object... values) {
        return String.format(template.replace('\'', '"'), values);
    }

    // ------------------------------------------------------------------ scenario helpers

    protected record Person(User user, String token) {
    }

    protected record DriverFixture(User user, String token, String profileId, String vehicleId) {
    }

    protected Person newRider() {
        User user = createUser(Role.RIDER);
        return new Person(user, tokenOf(user));
    }

    protected Person newAdmin() {
        User user = createUser(Role.ADMIN);
        return new Person(user, tokenOf(user));
    }

    /**
     * A driver who has done everything a real one must: created a profile, uploaded a licence, added
     * a vehicle, been approved by an admin, and gone online at the given position. All through the API.
     */
    protected DriverFixture newOnlineDriver(Person admin, com.ridex.ridex_app.driver.VehicleCategory category,
                                            double latitude, double longitude) {
        DriverFixture driver = newApprovedDriver(admin, category);
        ApiClient.Response online = api().post("/api/drivers/me/availability/online", driver.token(),
                json("{'latitude':%s,'longitude':%s}", coord(latitude), coord(longitude)));
        if (online.status() != 200) {
            throw new IllegalStateException("Could not go online: " + online.body());
        }
        return driver;
    }

    protected DriverFixture newApprovedDriver(Person admin, com.ridex.ridex_app.driver.VehicleCategory category) {
        User user = createUser(Role.DRIVER);
        String token = tokenOf(user);

        String profileId = expect(201, api().post("/api/drivers/me", token,
                json("{'licenseNumber':'%s','licenseExpiry':'%s'}", uniqueLicence(), java.time.LocalDate.now().plusYears(2)))).id();
        expect(201, api().upload("/api/drivers/me/documents", token, "DRIVING_LICENSE", "licence.pdf", pdf()));
        int seats = Math.min(category.maxPassengerSeats(), 4);
        String vehicleId = expect(201, api().post("/api/drivers/me/vehicles", token,
                json("{'category':'%s','make':'Maruti','model':'Dzire','color':'White','registrationNumber':'%s','manufactureYear':2021,'seats':%d}",
                        category, uniqueRegistration(), seats))).id();
        expect(200, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null));
        return new DriverFixture(user, token, profileId, vehicleId);
    }

    /** A ride request body: pickup at (lat, lng), destination about 5 km due north. */
    protected static String rideBody(String category, double latitude, double longitude) {
        return json("{'category':'%s','pickup':{'address':'Pickup point','location':{'latitude':%s,'longitude':%s}},"
                + "'dropoff':{'address':'Drop point','location':{'latitude':%s,'longitude':%s}}}",
                category, coord(latitude), coord(longitude), coord(latitude + 0.045), coord(longitude));
    }

    /** Six decimal places, like the API wants (and never 13.016599000000001). */
    protected static String coord(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    protected static ApiClient.Response expect(int status, ApiClient.Response response) {
        if (response.status() != status) {
            throw new AssertionError("Expected HTTP " + status + " but got " + response.status() + ": " + response.body());
        }
        return response;
    }
}
