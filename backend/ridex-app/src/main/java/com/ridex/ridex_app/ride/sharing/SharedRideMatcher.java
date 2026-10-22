package com.ridex.ridex_app.ride.sharing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ride sharing (pooling): can a second rider join a trip that is already under way, in the same car?
 *
 * THE ROUTE PROBLEM. Rider A is in the car going from A1 to A2. Rider B wants to go from B1 to B2. Taking B means detouring: the car must visit B1,
 * and then A2 and B2 in some order. There are only two sensible orders after A's pickup:
 *      A1 -> B1 -> A2 -> B2     (A is dropped first)
 *      A1 -> B1 -> B2 -> A2     (B is dropped first)
 * Measure both, take the shorter. Then ask the two questions that matter to the people involved:
 *   - How much longer is it for A (who was there first and did not ask for company)?   A's detour factor = (A's distance in the shared route) / (A's direct distance)
 *   - How much longer is it for B (compared with going alone)?                          B's factor
 * and one about the car: is B's pickup close enough to be worth the stop?
 * A match is made only when ALL are within limits: a pickup within 2 km, A delayed by at most 35 %, B by at most 50 %, and a free seat.
 *
 * THIS IS THE DECISION, NOT THE DISPATCH. The matcher is pure arithmetic over four points (straight-line distances, as everywhere else in the
 * project). Wiring it into the live dispatch (holding a request while looking for a partner, re-routing a car mid-trip, splitting payments, what to do
 * when A cancels) is a larger piece of work; see docs/phase-14-review.md for what is missing.
 */
public final class SharedRideMatcher {

    public static final double MAX_PICKUP_GAP_KM = 2.0;
    public static final double MAX_DETOUR_FIRST = 1.35;
    public static final double MAX_DETOUR_SECOND = 1.50;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    public record Point(double latitude, double longitude) {
        double distanceKmTo(Point other) {
            double lat1 = Math.toRadians(latitude);
            double lat2 = Math.toRadians(other.latitude);
            double dLat = lat2 - lat1;
            double dLng = Math.toRadians(other.longitude - longitude);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
        }
    }

    public record Trip(Point from, Point to) {
        double directKm() {
            return from.distanceKmTo(to);
        }
    }

    public enum DropOrder { FIRST_RIDER_DROPPED_FIRST, SECOND_RIDER_DROPPED_FIRST }

    public record Match(boolean compatible, String reason, DropOrder order, double firstRiderDetour, double secondRiderDetour, double pickupGapKm) {
    }

    private SharedRideMatcher() {
    }

    public static Match evaluate(Trip first, Trip second, int freeSeats) {
        double gap = first.from().distanceKmTo(second.from());
        if (freeSeats < 1) {
            return no("The car is full", gap);
        }
        if (first.directKm() < 0.2 || second.directKm() < 0.2) {
            return no("A trip this short cannot be shared", gap);
        }
        if (gap > MAX_PICKUP_GAP_KM) {
            return no("The second pickup is too far away (" + round(gap) + " km)", gap);
        }
        // Order 1: A1 -> B1 -> A2 -> B2.  A rides A1->B1->A2; B rides B1->A2->B2
        double a1 = gap + second.from().distanceKmTo(first.to());
        double b1 = second.from().distanceKmTo(first.to()) + first.to().distanceKmTo(second.to());
        // Order 2: A1 -> B1 -> B2 -> A2.  A rides A1->B1->B2->A2; B rides B1->B2
        double a2 = gap + second.directKm() + second.to().distanceKmTo(first.to());
        double b2 = second.directKm();
        // the order with the shorter whole route (both riders' time counts)
        boolean firstOrder = a1 + b1 <= a2 + b2;
        double firstDetour = (firstOrder ? a1 : a2) / first.directKm();
        double secondDetour = (firstOrder ? b1 : b2) / second.directKm();
        DropOrder order = firstOrder ? DropOrder.FIRST_RIDER_DROPPED_FIRST : DropOrder.SECOND_RIDER_DROPPED_FIRST;
        if (firstDetour > MAX_DETOUR_FIRST) {
            return new Match(false, "The first rider would be delayed too much (" + percent(firstDetour) + " longer)", order, firstDetour, secondDetour, gap);
        }
        if (secondDetour > MAX_DETOUR_SECOND) {
            return new Match(false, "The second rider's trip would be too much longer than going alone (" + percent(secondDetour) + ")", order, firstDetour, secondDetour, gap);
        }
        return new Match(true, "Compatible", order, firstDetour, secondDetour, gap);
    }

    /** What a rider pays for a shared trip: the solo fare minus a discount, because the trip takes longer and the car is shared. */
    public static BigDecimal sharedFare(BigDecimal soloFare, int discountPercent) {
        return soloFare.multiply(BigDecimal.valueOf(100 - discountPercent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static Match no(String reason, double gap) {
        return new Match(false, reason, null, 0, 0, gap);
    }

    private static String round(double km) {
        return BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(double factor) {
        return Math.round((factor - 1) * 100) + " %";
    }
}
