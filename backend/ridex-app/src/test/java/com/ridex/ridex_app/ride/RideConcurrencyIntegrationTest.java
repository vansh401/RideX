package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * Race conditions, tested for real. Every test releases several HTTP requests at the same instant
 * (a start gate) and then checks the invariants that must hold no matter who wins:
 *   - one driver is never offered to two rides,
 *   - one rider never has two rides in progress,
 *   - a ride never leaves a state twice.
 * These guarantees come from the database (row locks, partial unique indexes, version numbers),
 * not from hoping requests arrive one at a time.
 */
class RideConcurrencyIntegrationTest extends AbstractApiTest {

    /** Runs the tasks at the same moment on separate threads and returns their results in order. */
    private static <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();          // everyone waits at the start line...
                    return task.call();  // ...and is released together
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneDriverIsNeverOfferedToTwoRidesAtOnce() throws Exception {
        Person admin = newAdmin();
        double lat = 14.0, lng = 76.0;
        DriverFixture only = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        List<Person> riders = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            riders.add(newRider());
        }

        // Eight different riders request a cab at the same instant; there is ONE driver
        List<Response> responses = inParallel(riders.stream()
                .<Callable<Response>>map(rider -> () -> api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)))
                .toList());

        // every request was accepted (a ride was created), none crashed
        assertThat(responses).extracting(Response::status).containsOnly(201);
        // ...but the driver was offered to exactly ONE of them
        List<String> statuses = responses.stream().map(r -> r.<String>json("$.status")).toList();
        assertThat(statuses.stream().filter("DRIVER_ASSIGNED"::equals)).hasSize(1);
        assertThat(statuses.stream().filter("SEARCHING_DRIVER"::equals)).hasSize(7);
        // and the database agrees
        assertThat(jdbc.queryForObject(
                "select count(*) from rides where driver_profile_id = ?::uuid and status = 'DRIVER_ASSIGNED'",
                Integer.class, only.profileId())).isEqualTo(1);

        // clean up so the driver does not linger for other tests
        for (Person rider : riders) {
            api().post("/api/rides/" + api().get("/api/rides/current", rider.token()).id() + "/cancel", rider.token(), null);
        }
        api().post("/api/drivers/me/availability/offline", only.token(), null);
    }

    @Test
    void theNearestFreeDriversAreSpreadAcrossSimultaneousRides() throws Exception {
        Person admin = newAdmin();
        double lat = 15.0, lng = 76.0;
        DriverFixture a = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        DriverFixture b = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.001, lng);
        DriverFixture c = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.002, lng);
        List<Person> riders = List.of(newRider(), newRider(), newRider());

        List<Response> responses = inParallel(riders.stream()
                .<Callable<Response>>map(rider -> () -> api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)))
                .toList());

        // three rides, three drivers: SKIP LOCKED made each ride take a DIFFERENT driver, nobody waited
        assertThat(responses).extracting(Response::status).containsOnly(201);
        assertThat(responses.stream().map(r -> r.<String>json("$.status"))).containsOnly("DRIVER_ASSIGNED");
        assertThat(responses.stream().map(r -> r.<String>json("$.driver.userId")).distinct()).hasSize(3);

        for (Person rider : riders) {
            api().post("/api/rides/" + api().get("/api/rides/current", rider.token()).id() + "/cancel", rider.token(), null);
        }
        for (DriverFixture d : List.of(a, b, c)) {
            api().post("/api/drivers/me/availability/offline", d.token(), null);
        }
    }

    @Test
    void aRiderCannotEndUpWithTwoRidesInProgress() throws Exception {
        Person rider = newRider();
        double lat = 16.0, lng = 76.0;

        // One rider taps "Request" five times at once (a double-click, a retry, a bug in the client)
        List<Response> responses = inParallel(java.util.stream.IntStream.range(0, 5)
                .<Callable<Response>>mapToObj(i -> () -> api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)))
                .toList());

        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 409)).hasSize(4);   // the rest are refused cleanly
        assertThat(responses).extracting(Response::status).doesNotContain(500);
        assertThat(jdbc.queryForObject(
                "select count(*) from rides where rider_id = ?::uuid and status not in ('RIDE_COMPLETED','CANCELLED')",
                Integer.class, rider.user().getId())).isEqualTo(1);

        api().post("/api/rides/" + api().get("/api/rides/current", rider.token()).id() + "/cancel", rider.token(), null);
    }

    @Test
    void acceptingAndCancellingAtTheSameInstantNeverCorruptsTheRide() throws Exception {
        Person admin = newAdmin();
        double lat = 19.0, lng = 76.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        Person rider = newRider();

        for (int round = 1; round <= 6; round++) {
            String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

            // the driver accepts while the rider cancels, released together
            List<Response> outcomes = inParallel(List.of(
                    () -> api().post("/api/rides/" + rideId + "/accept", driver.token(), null),
                    () -> api().post("/api/rides/" + rideId + "/cancel", rider.token(), "{\"reason\":\"race\"}")));

            // nothing crashed: each request either succeeded or was cleanly refused (409)
            assertThat(outcomes).extracting(Response::status).allMatch(code -> code == 200 || code == 409);
            assertThat(outcomes).extracting(Response::status).contains(200);

            // the ride ended in one coherent state...
            String finalStatus = api().get("/api/rides/" + rideId, rider.token()).json("$.status");
            assertThat(finalStatus).isIn("DRIVER_ACCEPTED", "CANCELLED");
            // ...and it left DRIVER_ASSIGNED exactly once (two competing changes cannot both apply)
            assertThat(jdbc.queryForObject(
                    "select count(*) from ride_status_history where ride_id = ?::uuid and from_status = 'DRIVER_ASSIGNED'",
                    Integer.class, rideId)).isEqualTo(1);

            // free the driver and rider for the next round
            if (!"CANCELLED".equals(finalStatus)) {
                expect(200, api().post("/api/rides/" + rideId + "/cancel", rider.token(), null));
            }
        }
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }
}
