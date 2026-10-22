package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.User;

/**
 * Whole journeys through the real HTTP API against a real PostgreSQL: nothing is mocked.
 * Each test uses its own stretch of map (a different latitude) so that drivers created by one test
 * are never "nearby" in another, even though they share one database.
 */
class RideJourneyIntegrationTest extends AbstractApiTest {

    @Autowired
    private RideDispatcher dispatcher;

    private static BigDecimal money(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    // ------------------------------------------------------------------------------------------
    @Test
    void fromADriverApplyingToBothPeopleRatingEachOther() {
        Person admin = newAdmin();
        Person rider = newRider();
        double lat = 11.0, lng = 76.0;

        // ===== 1. A driver applies =====
        User driverUser = createUser(com.ridex.ridex_app.user.Role.DRIVER);
        String driver = tokenOf(driverUser);
        String profileId = expect(201, api().post("/api/drivers/me", driver,
                json("{'licenseNumber':'%s','licenseExpiry':'%s'}", uniqueLicence(), LocalDate.now().plusYears(2)))).id();
        assertThat(api().get("/api/drivers/me", driver).<String>json("$.approvalStatus")).isEqualTo("PENDING_APPROVAL");

        // not approved yet, so no going online, and an admin cannot approve without a licence document
        expect(409, api().post("/api/drivers/me/availability/online", driver,
                json("{'latitude':%s,'longitude':%s}", coord(lat), coord(lng))));
        Response noDocument = expect(409, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null));
        assertThat(noDocument.<String>json("$.message")).contains("driving licence");

        // ===== 2. Documents and a vehicle =====
        expect(201, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "licence.pdf", pdf()));
        expect(201, api().post("/api/drivers/me/vehicles", driver,
                json("{'category':'CAB','make':'Maruti','model':'Dzire','color':'White','registrationNumber':'%s','manufactureYear':2021,'seats':4}",
                        uniqueRegistration())));

        // ===== 3. The admin reviews the application =====
        Response queue = expect(200, api().get("/api/admin/drivers?status=PENDING_APPROVAL&size=100", admin.token()));
        assertThat(queue.<List<String>>json("$.content[*].id")).contains(profileId);

        Response detail = expect(200, api().get("/api/admin/drivers/" + profileId, admin.token()));
        assertThat(detail.<List<Object>>json("$.documents")).hasSize(1);
        assertThat(detail.<List<Object>>json("$.vehicles")).hasSize(1);
        assertThat(detail.<String>json("$.email")).isEqualTo(driverUser.getEmail());

        Response approved = expect(200, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null));
        assertThat(approved.<String>json("$.approvalStatus")).isEqualTo("APPROVED");
        assertThat(approved.<String>json("$.reviewedBy")).isEqualTo(admin.user().getId().toString());
        // the approval is final: it cannot be approved twice, and the driver can no longer edit the licence
        expect(409, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null));
        expect(409, api().put("/api/drivers/me", driver,
                json("{'licenseNumber':'%s','licenseExpiry':'%s'}", uniqueLicence(), LocalDate.now().plusYears(3))));

        // ===== 4. The driver goes online near the rider =====
        expect(200, api().post("/api/drivers/me/availability/online", driver,
                json("{'latitude':%s,'longitude':%s}", coord(lat), coord(lng))));

        // ===== 5. The rider books (the price comes from the pricing service; the quote itself is tested there) =====
        Response requested = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)));
        String rideId = requested.id();
        // an available driver was found immediately, and the price is exactly what was quoted
        assertThat(requested.<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(money(requested.json("$.fare.total"))).isPositive();
        assertThat(requested.<String>json("$.fare.currency")).isEqualTo("INR");
        assertThat(requested.<String>json("$.driver.fullName")).isEqualTo(driverUser.getFullName());
        assertThat(requested.<String>json("$.driver.phone")).isNull();   // phones stay hidden until accepted

        // a rider can only have one ride at a time
        expect(409, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)));
        assertThat(api().get("/api/rides/current", rider.token()).<String>json("$.id")).isEqualTo(rideId);

        // ===== 6. The driver sees the offer and accepts =====
        Response offer = expect(200, api().get("/api/rides/current", driver));
        assertThat(offer.<String>json("$.id")).isEqualTo(rideId);
        assertThat(offer.<String>json("$.offerExpiresAt")).isNotNull();

        // nobody can rate a ride that has not finished
        expect(409, api().post("/api/rides/" + rideId + "/ratings", rider.token(), "{\"stars\":5}"));
        // another rider cannot even see this ride
        expect(404, api().get("/api/rides/" + rideId, newRider().token()));

        Response accepted = expect(200, api().post("/api/rides/" + rideId + "/accept", driver, null));
        assertThat(accepted.<String>json("$.status")).isEqualTo("DRIVER_ACCEPTED");
        assertThat(accepted.<String>json("$.rider.phone")).isEqualTo(rider.user().getPhone());
        assertThat(api().get("/api/rides/" + rideId, rider.token()).<String>json("$.driver.phone"))
                .isEqualTo(driverUser.getPhone());

        // ===== 7. The trip =====
        // steps cannot be skipped
        expect(409, api().post("/api/rides/" + rideId + "/start", driver, null));
        expect(409, api().post("/api/rides/" + rideId + "/complete", driver, null));
        expect(200, api().post("/api/rides/" + rideId + "/arrived", driver, null));
        expect(200, api().post("/api/rides/" + rideId + "/start", driver, null));
        // a trip in progress cannot be cancelled
        expect(409, api().post("/api/rides/" + rideId + "/cancel", rider.token(), "{\"reason\":\"too late\"}"));
        Response completed = expect(200, api().post("/api/rides/" + rideId + "/complete", driver, null));
        assertThat(completed.<String>json("$.status")).isEqualTo("RIDE_COMPLETED");
        assertThat(completed.<String>json("$.completedAt")).isNotNull();

        // ===== 8. The audit trail =====
        Response timeline = expect(200, api().get("/api/rides/" + rideId + "/timeline", rider.token()));
        assertThat(timeline.<List<String>>json("$[*].to")).containsExactly(
                "REQUESTED", "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "DRIVER_ACCEPTED",
                "DRIVER_ARRIVED", "RIDE_STARTED", "RIDE_COMPLETED");
        assertThat(timeline.<List<String>>json("$[*].actorRole")).containsExactly(
                "RIDER", "SYSTEM", "SYSTEM", "DRIVER", "DRIVER", "DRIVER", "DRIVER");

        // ===== 9. Ratings =====
        Response riderRating = expect(201, api().post("/api/rides/" + rideId + "/ratings", rider.token(),
                "{\"stars\":5,\"comment\":\"Smooth ride\"}"));
        assertThat(riderRating.<String>json("$.raterRole")).isEqualTo("RIDER");
        expect(409, api().post("/api/rides/" + rideId + "/ratings", rider.token(), "{\"stars\":1}"));   // once only
        expect(400, api().post("/api/rides/" + rideId + "/ratings", driver, "{\"stars\":6}"));          // 1 to 5
        expect(201, api().post("/api/rides/" + rideId + "/ratings", driver, "{\"stars\":4}"));

        // the driver's running average now includes the 5 stars
        Response again = expect(200, api().get("/api/rides/" + rideId, rider.token()));
        assertThat(money(again.json("$.driver.ratingAverage"))).isEqualByComparingTo("5.00");
        assertThat(again.<Integer>json("$.driver.ratingCount")).isEqualTo(1);
        assertThat(again.<Integer>json("$.myRating")).isEqualTo(5);

        // ===== 10. History =====
        Response riderHistory = expect(200, api().get("/api/rides?size=10", rider.token()));
        assertThat(riderHistory.<Integer>json("$.totalElements")).isEqualTo(1);
        assertThat(riderHistory.<String>json("$.content[0].status")).isEqualTo("RIDE_COMPLETED");
        assertThat(riderHistory.<Integer>json("$.content[0].myRating")).isEqualTo(5);
        assertThat(api().get("/api/rides", driver).<Integer>json("$.totalElements")).isEqualTo(1);
        assertThat(api().get("/api/rides?status=CANCELLED", rider.token()).<Integer>json("$.totalElements")).isZero();

        // ===== 11. The admin sees it too =====
        Response stats = expect(200, api().get("/api/admin/stats", admin.token()));
        assertThat(stats.<Integer>json("$.ridesByStatus.RIDE_COMPLETED")).isGreaterThanOrEqualTo(1);
        assertThat(money(stats.json("$.completedRevenue"))).isPositive();
        assertThat(api().get("/api/admin/rides?status=RIDE_COMPLETED&size=100", admin.token())
                .<List<String>>json("$.content[*].id")).contains(rideId);

        // the driver is free again and can take a new ride
        Response next = expect(201, api().post("/api/rides", newRider().token(), rideBody("CAB", lat, lng)));
        assertThat(next.<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");
        // a driver cannot "cancel" an offer, only decline it
        expect(409, api().post("/api/rides/" + next.id() + "/cancel", driver, null));
        expect(204, api().post("/api/rides/" + next.id() + "/reject", driver, null));
        api().post("/api/drivers/me/availability/offline", driver, null);
    }

    // ------------------------------------------------------------------------------------------
    @Test
    void aDeclinedOfferGoesToTheNextNearestDriverAndNeverBack() {
        Person admin = newAdmin();
        double lat = 12.0, lng = 76.0;
        // pickup is at lat + 0.001: the first driver is about 110 m away, the second about 1 km
        DriverFixture near = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        DriverFixture far = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.010, lng);
        Person rider = newRider();

        Response ride = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat + 0.001, lng)));
        String rideId = ride.id();
        assertThat(ride.<String>json("$.driver.userId")).isEqualTo(near.user().getId().toString());
        // the other driver has nothing offered to them yet (204 = no current ride)
        expect(204, api().get("/api/rides/current", far.token()));

        // the nearest driver declines: the offer moves on to the other driver
        expect(204, api().post("/api/rides/" + rideId + "/reject", near.token(), null));
        Response second = expect(200, api().get("/api/rides/" + rideId, rider.token()));
        assertThat(second.<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(second.<String>json("$.driver.userId")).isEqualTo(far.user().getId().toString());
        // the first driver no longer sees it (404, as if it never existed) and cannot accept it
        expect(404, api().get("/api/rides/" + rideId, near.token()));
        expect(404, api().post("/api/rides/" + rideId + "/accept", near.token(), null));

        // the second driver declines too: nobody is left, so the ride keeps searching
        expect(204, api().post("/api/rides/" + rideId + "/reject", far.token(), null));
        Response searching = expect(200, api().get("/api/rides/" + rideId, rider.token()));
        assertThat(searching.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");
        assertThat(searching.<Object>json("$.driver")).isNull();

        // the dispatch job does not re-offer the ride to drivers who already declined it
        dispatcher.runCycle();
        assertThat(api().get("/api/rides/" + rideId, rider.token()).<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");

        // the rider gives up
        Response cancelled = expect(200, api().post("/api/rides/" + rideId + "/cancel", rider.token(), "{\"reason\":\"Taking the bus\"}"));
        assertThat(cancelled.<String>json("$.status")).isEqualTo("CANCELLED");
        assertThat(cancelled.<String>json("$.cancelledBy")).isEqualTo("RIDER");

        // the audit trail tells the whole story
        List<String> notes = api().get("/api/rides/" + rideId + "/timeline", rider.token()).json("$[*].note");
        assertThat(notes).contains("Driver declined", "Taking the bus");

        // a NEW ride has no rejections, so the nearest driver gets it again
        Response fresh = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat + 0.001, lng)));
        assertThat(fresh.<String>json("$.driver.userId")).isEqualTo(near.user().getId().toString());
        expect(200, api().post("/api/rides/" + fresh.id() + "/cancel", rider.token(), null));
        api().post("/api/drivers/me/availability/offline", near.token(), null);
        api().post("/api/drivers/me/availability/offline", far.token(), null);
    }

    // ------------------------------------------------------------------------------------------
    @Test
    void anUnansweredOfferTimesOutAndTheSearchEventuallyGivesUp() {
        Person admin = newAdmin();
        double lat = 13.0, lng = 76.0;
        DriverFixture sleepy = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        Person rider = newRider();

        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        assertThat(api().get("/api/rides/" + rideId, rider.token()).<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");

        // pretend the 30-second deadline has passed, then run the dispatch job
        jdbc.update("update rides set offer_expires_at = now() - interval '1 minute' where id = ?::uuid", rideId);
        dispatcher.runCycle();

        Response afterTimeout = expect(200, api().get("/api/rides/" + rideId, rider.token()));
        assertThat(afterTimeout.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");   // nobody else around
        assertThat(jdbc.queryForObject("select count(*) from ride_rejections where ride_id = ?::uuid and reason = 'TIMEOUT'",
                Integer.class, rideId)).isEqualTo(1);
        // the slow driver can no longer accept the ride they ignored
        expect(404, api().post("/api/rides/" + rideId + "/accept", sleepy.token(), null));

        // a new driver comes online; the next cycle offers them the waiting ride
        DriverFixture fresh = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.002, lng);
        dispatcher.runCycle();
        assertThat(api().get("/api/rides/" + rideId, rider.token()).<String>json("$.driver.userId"))
                .isEqualTo(fresh.user().getId().toString());

        // an offer that already expired cannot be accepted, even before the job notices
        jdbc.update("update rides set offer_expires_at = now() - interval '1 minute' where id = ?::uuid", rideId);
        Response late = expect(409, api().post("/api/rides/" + rideId + "/accept", fresh.token(), null));
        assertThat(late.<String>json("$.message")).contains("expired");

        // and a search that has gone on too long is cancelled by the system
        dispatcher.runCycle();   // the late offer is reassigned/returned to searching
        jdbc.update("update rides set requested_at = now() - interval '10 minutes', search_started_at = now() - interval '10 minutes' where id = ?::uuid", rideId);
        dispatcher.runCycle();
        Response gaveUp = expect(200, api().get("/api/rides/" + rideId, rider.token()));
        assertThat(gaveUp.<String>json("$.status")).isEqualTo("CANCELLED");
        assertThat(gaveUp.<String>json("$.cancelledBy")).isEqualTo("SYSTEM");
        assertThat(gaveUp.<String>json("$.cancelReason")).contains("No driver");

        api().post("/api/drivers/me/availability/offline", sleepy.token(), null);
        api().post("/api/drivers/me/availability/offline", fresh.token(), null);
    }

    // ------------------------------------------------------------------------------------------
    @Test
    void onlyDriversOfTheRequestedCategoryAreOfferedTheRide() {
        Person admin = newAdmin();
        double lat = 17.0, lng = 76.0;
        DriverFixture bike = newOnlineDriver(admin, VehicleCategory.BIKE, lat, lng);
        Person rider = newRider();

        Response cab = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)));
        assertThat(cab.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");   // the only driver rides a bike
        expect(200, api().post("/api/rides/" + cab.id() + "/cancel", rider.token(), null));

        Response bikeRide = expect(201, api().post("/api/rides", rider.token(), rideBody("BIKE", lat, lng)));
        assertThat(bikeRide.<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");
        expect(200, api().post("/api/rides/" + bikeRide.id() + "/cancel", rider.token(), null));
        api().post("/api/drivers/me/availability/offline", bike.token(), null);
    }

    @Test
    void aDriverWhoIsTooFarAwayOrOfflineIsNotOffered() {
        Person admin = newAdmin();
        double lat = 18.0, lng = 76.0;
        DriverFixture far = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.20, lng);     // about 22 km away
        Person rider = newRider();
        assertThat(expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)))
                .<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");
        String rideId = api().get("/api/rides/current", rider.token()).id();
        expect(200, api().post("/api/rides/" + rideId + "/cancel", rider.token(), null));

        // a driver who went offline is not offered rides either, however close
        DriverFixture offline = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        expect(200, api().post("/api/drivers/me/availability/offline", offline.token(), null));
        assertThat(expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)))
                .<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");
        expect(200, api().post("/api/rides/" + api().get("/api/rides/current", rider.token()).id() + "/cancel", rider.token(), null));
        api().post("/api/drivers/me/availability/offline", far.token(), null);
    }
}
