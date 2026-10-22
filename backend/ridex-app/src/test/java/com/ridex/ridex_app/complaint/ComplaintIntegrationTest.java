package com.ridex.ridex_app.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** Complaints through the real API: who may file, how an administrator handles them, and how a refund leaves as an event. */
class ComplaintIntegrationTest extends AbstractApiTest {

    private record Trip(String rideId, Person rider, DriverFixture driver, Person admin) {
    }

    /** A complete trip: requested, accepted, arrived, started, completed. */
    private Trip completedTrip(double lat) {
        Person admin = newAdmin();
        Person rider = newRider();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.001, 71.0);
        String id = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, 71.0))).id();
        expect(200, api().post("/api/rides/" + id + "/accept", driver.token(), null));
        expect(200, api().post("/api/rides/" + id + "/arrived", driver.token(), null));
        expect(200, api().post("/api/rides/" + id + "/start", driver.token(), null));
        expect(200, api().post("/api/rides/" + id + "/complete", driver.token(), null));
        return new Trip(id, rider, driver, admin);
    }

    private static String complaint(String rideId, String category) {
        return json("{'rideId':'%s','category':'%s','description':'The fare was higher than the estimate I was shown'}", rideId, category);
    }

    private int eventsOf(String type, String rideId) {
        Integer n = jdbc.queryForObject("select count(*) from outbox_events where event_type = ? and event_key = ?", Integer.class, type, rideId);
        return n == null ? 0 : n;
    }

    @Test
    void aRiderFilesAComplaintAndOnlyOneIsOpenPerRidePerPerson() {
        Trip trip = completedTrip(40.0);

        Response filed = expect(201, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "OVERCHARGED")));
        assertThat((String) filed.json("$.status")).isEqualTo("OPEN");
        assertThat((String) filed.json("$.category")).isEqualTo("OVERCHARGED");
        expect(409, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "ROUTE_ISSUE")));
        assertThat(eventsOf("complaint.filed", trip.rideId())).isEqualTo(1);

        // the driver of the same ride may file their own
        expect(201, api().post("/api/complaints", trip.driver().token(), complaint(trip.rideId(), "RUDE_BEHAVIOUR")));
        assertThat(api().get("/api/complaints", trip.rider().token()).<Integer>json("$.totalElements")).isEqualTo(1);
    }

    @Test
    void onlyThePeopleOnTheRideCanComplainAboutItAndTheDescriptionMustSayEnough() {
        Trip trip = completedTrip(41.0);
        Person stranger = newRider();

        expect(404, api().post("/api/complaints", stranger.token(), complaint(trip.rideId(), "OVERCHARGED")));
        expect(400, api().post("/api/complaints", trip.rider().token(), json("{'rideId':'%s','category':'OTHER','description':'bad'}", trip.rideId())));
        expect(403, api().post("/api/complaints", trip.admin().token(), complaint(trip.rideId(), "OTHER")));
        expect(401, api().post("/api/complaints", null, complaint(trip.rideId(), "OTHER")));
        assertThat(eventsOf("complaint.filed", trip.rideId())).isZero();
    }

    @Test
    void aRideThatNoDriverTookCannotBeComplainedAbout() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", 42.0, 71.0))).id();   // searching, nobody near
        expect(200, api().post("/api/rides/" + id + "/cancel", rider.token(), null));

        expect(409, api().post("/api/complaints", rider.token(), complaint(id, "OTHER")));
    }

    @Test
    void complaintsHaveATimeLimit() {
        Trip trip = completedTrip(43.0);
        jdbc.update("update rides set completed_at = now() - interval '20 days' where id = ?::uuid", trip.rideId());

        expect(409, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "OVERCHARGED")));
    }

    @Test
    void anAdministratorHandlesItWithPublicAndInternalMessagesAndResolvesItWithARefundThatLeavesAsAnEvent() {
        Trip trip = completedTrip(44.0);
        String id = expect(201, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "OVERCHARGED"))).id();
        String admin = trip.admin().token();

        Response queue = expect(200, api().get("/api/admin/complaints?status=OPEN&size=100", admin));
        assertThat(queue.<List<String>>json("$.content[*].id")).contains(id);
        assertThat((String) expect(200, api().post("/api/admin/complaints/" + id + "/assign", admin, null)).json("$.status")).isEqualTo("IN_REVIEW");
        expect(200, api().post("/api/admin/complaints/" + id + "/messages", admin, json("{'message':'Checking the meter data','internal':true}")));
        expect(200, api().post("/api/admin/complaints/" + id + "/messages", admin, json("{'message':'We are looking into it, thank you','internal':false}")));

        // the rider sees the public message and not the internal note
        Response seen = expect(200, api().get("/api/complaints/" + id, trip.rider().token()));
        assertThat(seen.<List<String>>json("$.comments[*].message")).containsExactly("We are looking into it, thank you");
        assertThat(expect(200, api().get("/api/admin/complaints/" + id, admin)).<List<String>>json("$.comments[*].message")).hasSize(2);

        expect(400, api().post("/api/admin/complaints/" + id + "/resolve", admin, json("{'note':'Refund granted','refundAmount':100000.00}")));   // more than the fare
        Response resolved = expect(200, api().post("/api/admin/complaints/" + id + "/resolve", admin, json("{'note':'The fare was wrong: refunded','refundAmount':50.00}")));
        assertThat((String) resolved.json("$.status")).isEqualTo("RESOLVED");
        assertThat(eventsOf("complaint.resolved", trip.rideId())).isEqualTo(1);
        String payload = jdbc.queryForObject("select payload from outbox_events where event_type = 'complaint.resolved' and event_key = ?", String.class, trip.rideId());
        assertThat(payload).contains("\"refundAmount\":50.00").contains("\"userId\":\"" + trip.rider().user().getId() + "\"");

        expect(409, api().post("/api/admin/complaints/" + id + "/resolve", admin, json("{'note':'again'}")));   // already closed
        expect(409, api().post("/api/complaints/" + id + "/messages", trip.rider().token(), json("{'message':'one more thing'}")));
        // closed, so the person may file a new one about the same ride
        expect(201, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "PAYMENT_ISSUE")));
    }

    @Test
    void aRejectedComplaintMustSayWhyAndIsAnnouncedWithoutARefund() {
        Trip trip = completedTrip(45.0);
        String id = expect(201, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "ROUTE_ISSUE"))).id();
        String admin = trip.admin().token();

        expect(400, api().post("/api/admin/complaints/" + id + "/reject", admin, json("{'note':''}")));
        Response rejected = expect(200, api().post("/api/admin/complaints/" + id + "/reject", admin, json("{'note':'The route followed the map'}")));

        assertThat((String) rejected.json("$.status")).isEqualTo("REJECTED");
        assertThat((String) rejected.json("$.resolutionNote")).isEqualTo("The route followed the map");
        String payload = jdbc.queryForObject("select payload from outbox_events where event_type = 'complaint.resolved' and event_key = ?", String.class, trip.rideId());
        assertThat(payload).contains("\"status\":\"REJECTED\"").contains("\"refundAmount\":null");
    }

    @Test
    void onlyARiderCanBeRefundedAndPeopleSeeOnlyTheirOwnComplaints() {
        Trip trip = completedTrip(46.0);
        String driverComplaint = expect(201, api().post("/api/complaints", trip.driver().token(), complaint(trip.rideId(), "OTHER"))).id();
        String riderComplaint = expect(201, api().post("/api/complaints", trip.rider().token(), complaint(trip.rideId(), "OTHER"))).id();

        expect(409, api().post("/api/admin/complaints/" + driverComplaint + "/resolve", trip.admin().token(), json("{'note':'ok','refundAmount':10.00}")));
        expect(404, api().get("/api/complaints/" + riderComplaint, trip.driver().token()));
        expect(404, api().get("/api/complaints/" + driverComplaint, trip.rider().token()));
        expect(403, api().get("/api/admin/complaints", trip.rider().token()));
    }
}
