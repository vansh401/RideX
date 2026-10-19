package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.DriverProfileRepository;
import com.ridex.ridex_app.driver.Vehicle;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.driver.VehicleRepository;
import com.ridex.ridex_app.pricing.FareCalculator;
import com.ridex.ridex_app.pricing.FareEstimate;
import com.ridex.ridex_app.rider.RiderProfileRepository;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

import jakarta.persistence.EntityManagerFactory;

/**
 * The data side of rides: pagination, the N+1 problem measured with real SQL statement counts, and
 * the database constraints attacked directly (bypassing all application code) to show the rules
 * hold even if the code above them had a bug.
 */
class RideDataIntegrationTest extends AbstractApiTest {

    @Autowired private TransactionTemplate tx;
    @Autowired private RideRepository rideRepository;
    @Autowired private DriverProfileRepository driverProfileRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RiderProfileRepository riderProfileRepository;
    @Autowired private FareCalculator fareCalculator;
    @Autowired private RideService rideService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static final LatLng A = new LatLng(new BigDecimal("24.000000"), new BigDecimal("76.000000"));
    private static final LatLng B = new LatLng(new BigDecimal("24.045000"), new BigDecimal("76.000000"));

    private FareEstimate fare() {
        return fareCalculator.calculate(VehicleCategory.CAB, new BigDecimal("5.00"), 15);
    }

    /** A finished ride, written straight to the database through the entity (no HTTP). */
    private UUID completedRide(UUID riderId, DriverFixture driver, Instant requestedAt) {
        return tx.execute(status -> {
            User rider = userRepository.findById(riderId).orElseThrow();
            DriverProfile profile = driverProfileRepository.findById(UUID.fromString(driver.profileId())).orElseThrow();
            Vehicle vehicle = vehicleRepository.findById(UUID.fromString(driver.vehicleId())).orElseThrow();
            Ride ride = Ride.request(rider, VehicleCategory.CAB, "A", A, "B", B, fare(), requestedAt);
            ride.offerTo(profile, vehicle, requestedAt.plusSeconds(30), requestedAt);
            ride.accept(driver.user().getId(), requestedAt.plusSeconds(1));
            ride.arrive(driver.user().getId(), requestedAt.plusSeconds(2));
            ride.start(driver.user().getId(), requestedAt.plusSeconds(3));
            ride.complete(driver.user().getId(), requestedAt.plusSeconds(4));
            return rideRepository.save(ride).getId();
        });
    }

    // ------------------------------------------------------------------ pagination

    @Test
    void historyIsPagedNewestFirstWithNoGapsOrRepeats() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        Person rider = newRider();
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS);
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 24; i >= 0; i--) {   // created newest first: 24 minutes after base, then 23, ... 0
            newestFirst.add(completedRide(rider.user().getId(), driver, base.plus(i, ChronoUnit.MINUTES)));
        }

        Response first = expect(200, api().get("/api/rides?page=0&size=10", rider.token()));
        assertThat(first.<Integer>json("$.totalElements")).isEqualTo(25);
        assertThat(first.<Integer>json("$.totalPages")).isEqualTo(3);
        assertThat(first.<Integer>json("$.size")).isEqualTo(10);

        List<String> seen = new ArrayList<>(first.json("$.content[*].id"));
        seen.addAll(expect(200, api().get("/api/rides?page=1&size=10", rider.token())).json("$.content[*].id"));
        Response last = expect(200, api().get("/api/rides?page=2&size=10", rider.token()));
        assertThat(last.<List<Object>>json("$.content")).hasSize(5);
        seen.addAll(last.json("$.content[*].id"));

        // all 25, each exactly once, newest first
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(newestFirst.stream().map(UUID::toString).toList());

        // size is capped, negative pages are treated as the first page
        assertThat(api().get("/api/rides?size=100000", rider.token()).<Integer>json("$.size")).isEqualTo(100);
        assertThat(api().get("/api/rides?page=-4&size=10", rider.token()).<Integer>json("$.page")).isZero();
        // a page past the end is simply empty
        assertThat(api().get("/api/rides?page=9&size=10", rider.token()).<List<Object>>json("$.content")).isEmpty();
        // the driver sees the same 25 rides from their side
        assertThat(api().get("/api/rides?size=100", driver.token()).<Integer>json("$.totalElements")).isEqualTo(25);
    }

    // ------------------------------------------------------------------ the N+1 problem, measured

    @Test
    void aPageOfRidesCostsAFixedNumberOfQueriesNoMatterHowManyPeopleAreOnIt() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        List<UUID> rideIds = new ArrayList<>();
        for (int i = 0; i < 15; i++) {   // fifteen DIFFERENT riders, so every row needs different data
            rideIds.add(completedRide(createUser(Role.RIDER).getId(), driver, Instant.now().minusSeconds(60L * (i + 1))));
        }

        // The page as the application builds it (admin ride list, 100 rows of many riders)
        Statistics stats = statistics();
        stats.clear();
        var page = rideService.historyForAdmin(RideStatus.RIDE_COMPLETED, 0, 100);
        long optimised = stats.getPrepareStatementCount();
        assertThat(page.content().size()).isGreaterThanOrEqualTo(15);
        assertThat(optimised).as("SQL statements for a page of rides").isLessThanOrEqualTo(3);   // page + count

        // The same data the naive way: load the rides, then touch their lazy associations
        stats.clear();
        long touched = tx.execute(status -> {
            List<Ride> all = rideRepository.findAll();
            for (Ride ride : all) {
                ride.getRider().getFullName();                  // one query per different rider
                if (ride.getDriver() != null) {                 // (rides still searching have no driver yet)
                    ride.getDriver().getUser().getFullName();   // driver, then the driver's user
                    ride.getVehicle().getMake();                // vehicle
                }
            }
            return (long) all.size();
        });
        long naive = stats.getPrepareStatementCount();
        // one query to list the rides, plus at least one more for each of the 15 different riders
        assertThat(naive).as("SQL statements the naive way").isGreaterThanOrEqualTo(1 + 15);
        assertThat(touched).isGreaterThanOrEqualTo(15);

        // the whole point of the entity graph, in numbers
        assertThat(naive).isGreaterThan(optimised * 5);
        assertThat(rideIds).hasSize(15);
    }

    @Test
    void aRidersPageUsesAFixedNumberOfQueries() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        Person rider = newRider();
        for (int i = 0; i < 20; i++) {
            completedRide(rider.user().getId(), driver, Instant.now().minusSeconds(60L * (i + 1)));
        }

        Statistics stats = statistics();
        stats.clear();
        var page = rideService.history(rider.user().getId(), ActorRole.RIDER, null, 0, 20);

        assertThat(page.content()).hasSize(20);
        // 1 rides+people join, 1 count, 1 "which did I rate" lookup
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void loadingARiderProfileWithItsPlacesIsASingleQuery() {
        Person rider = newRider();
        tx.executeWithoutResult(status -> riderProfileRepository.save(new com.ridex.ridex_app.rider.RiderProfile(
                userRepository.findById(rider.user().getId()).orElseThrow())));
        expect(201, api().post("/api/riders/me/places", rider.token(),
                "{\"label\":\"Home\",\"address\":\"1 A St\",\"latitude\":24.0,\"longitude\":76.0}"));
        expect(201, api().post("/api/riders/me/places", rider.token(),
                "{\"label\":\"Work\",\"address\":\"2 B St\",\"latitude\":24.1,\"longitude\":76.1}"));

        Statistics stats = statistics();
        stats.clear();
        tx.executeWithoutResult(status -> {
            var profile = riderProfileRepository.findByUserId(rider.user().getId()).orElseThrow();
            assertThat(profile.getSavedPlaces()).hasSize(2);   // lazy collection, already fetched by the entity graph
        });

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the database as the last line of defence

    @Test
    void theDatabaseItselfRefusesTwoRidesInProgressForOneRider() {
        Person rider = newRider();
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(rider.user().getId()).orElseThrow();
            rideRepository.saveAndFlush(Ride.request(user, VehicleCategory.CAB, "A", A, "B", B, fare(), Instant.now()));
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            User user = userRepository.findById(rider.user().getId()).orElseThrow();
            rideRepository.saveAndFlush(Ride.request(user, VehicleCategory.CAB, "A", A, "B", B, fare(), Instant.now()));
        })).isInstanceOf(DataIntegrityViolationException.class);

        // finished rides do not count: the same rider can ride again once the first is over
        rideRepository.findAll().stream().filter(r -> r.getRider().getId().equals(rider.user().getId())).findFirst();
        jdbc.update("update rides set status = 'CANCELLED' where rider_id = ?::uuid", rider.user().getId());
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(rider.user().getId()).orElseThrow();
            rideRepository.saveAndFlush(Ride.request(user, VehicleCategory.CAB, "A", A, "B", B, fare(), Instant.now()));
        });
        jdbc.update("update rides set status = 'CANCELLED' where rider_id = ?::uuid", rider.user().getId());
    }

    @Test
    void theDatabaseItselfRefusesTwoRidesAtOnceForOneDriver() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        Person first = newRider();
        Person second = newRider();

        tx.executeWithoutResult(status -> offerToDriver(first, driver));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> offerToDriver(second, driver)))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update rides set status = 'CANCELLED' where driver_profile_id = ?::uuid", driver.profileId());
    }

    private void offerToDriver(Person rider, DriverFixture driver) {
        User user = userRepository.findById(rider.user().getId()).orElseThrow();
        DriverProfile profile = driverProfileRepository.findById(UUID.fromString(driver.profileId())).orElseThrow();
        Vehicle vehicle = vehicleRepository.findById(UUID.fromString(driver.vehicleId())).orElseThrow();
        Ride ride = Ride.request(user, VehicleCategory.CAB, "A", A, "B", B, fare(), Instant.now());
        ride.offerTo(profile, vehicle, Instant.now().plusSeconds(30), Instant.now());
        rideRepository.saveAndFlush(ride);
    }

    @Test
    void aDriverCanHaveOnlyOneActiveVehicleAndPlatesAreUnique() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);   // already has one active vehicle

        assertThatThrownBy(() -> insertVehicle(driver.profileId(), uniqueRegistration(), true))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertVehicle(driver.profileId(), uniqueRegistration(), false);   // inactive ones are fine
        String plate = uniqueRegistration();
        insertVehicle(driver.profileId(), plate, false);
        assertThatThrownBy(() -> insertVehicle(driver.profileId(), plate, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertVehicle(String profileId, String plate, boolean active) {
        jdbc.update("insert into vehicles (id, driver_profile_id, category, make, model, color, registration_number, "
                + "manufacture_year, seats, active) values (?, ?::uuid, 'CAB', 'M', 'D', 'W', ?, 2021, 4, ?)",
                UUID.randomUUID(), profileId, plate, active);
    }

    @Test
    void ratingRulesAreEnforcedByTheDatabase() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        Person rider = newRider();
        UUID rideId = completedRide(rider.user().getId(), driver, Instant.now().minusSeconds(100));

        insertRating(rideId, rider.user().getId(), driver.user().getId(), "RIDER", 5);

        // the same person cannot rate the same ride twice, however the request arrives
        assertThatThrownBy(() -> insertRating(rideId, rider.user().getId(), driver.user().getId(), "RIDER", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        // stars are 1 to 5
        assertThatThrownBy(() -> insertRating(rideId, driver.user().getId(), rider.user().getId(), "DRIVER", 6))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRating(rideId, driver.user().getId(), rider.user().getId(), "DRIVER", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        // nobody rates themselves
        assertThatThrownBy(() -> insertRating(rideId, rider.user().getId(), rider.user().getId(), "RIDER", 5))
                .isInstanceOf(DataIntegrityViolationException.class);
        // the other person still can
        insertRating(rideId, driver.user().getId(), rider.user().getId(), "DRIVER", 4);
    }

    private void insertRating(UUID rideId, UUID rater, UUID ratee, String role, int stars) {
        jdbc.update("insert into ratings (id, ride_id, rater_id, ratee_id, rater_role, stars) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), rideId, rater, ratee, role, stars);
    }

    @Test
    void aRideWithADriverMustAlsoRecordTheVehicleAndAmountsCannotBeNegative() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        UUID rideId = completedRide(newRider().user().getId(), driver, Instant.now().minusSeconds(50));

        assertThatThrownBy(() -> jdbc.update("update rides set vehicle_id = null where id = ?", rideId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rides set total_fare = -1 where id = ?", rideId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rides set status = 'TELEPORTING' where id = ?", rideId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registeringARiderCreatesTheirProfileButRegisteringADriverDoesNot() {
        String suffix = String.valueOf(System.nanoTime() % 1_000_000);
        String riderEmail = "newrider" + suffix + "@test.ridex";
        String driverEmail = "newdriver" + suffix + "@test.ridex";
        String body = "{'fullName':'Test Person','email':'%s','phone':'%s','password':'Secret123','role':'%s'}";

        expect(201, api().post("/api/auth/register", null, json(body, riderEmail, "+9188" + suffix + "1", "RIDER")));
        expect(201, api().post("/api/auth/register", null, json(body, driverEmail, "+9188" + suffix + "2", "DRIVER")));

        UUID riderId = userRepository.findByEmail(riderEmail).orElseThrow().getId();
        UUID driverId = userRepository.findByEmail(driverEmail).orElseThrow().getId();
        assertThat(riderProfileRepository.existsByUserId(riderId)).isTrue();
        assertThat(riderProfileRepository.existsByUserId(driverId)).isFalse();
        // and an admin can never be registered
        expect(400, api().post("/api/auth/register", null, json(body, "x" + suffix + "@test.ridex", "+9188" + suffix + "3", "ADMIN")));
        assertThat(new HashSet<>(List.of(riderId, driverId))).hasSize(2);
    }
}
