package com.ridex.platform.geo;

/**
 * The map is cut into square-ish cells (a grid, 0.02 degrees of latitude and longitude, about 2.2 km on a side). Surge pricing is decided
 * per cell: demand and supply are counted inside it, and a ride is priced with the multiplier of the cell its pickup point is in.
 *
 * Two services agree on this grid (the core writes the multipliers, the pricing service reads them), so the rule lives here in the shared
 * platform library, like the Kafka topic names: it is part of the contract between them.
 *
 * A grid is the simplest possible zoning. Real systems use hexagons (H3) or geohashes, which avoid the "one block across a cell border is
 * a different price" cliff and have neighbours at equal distances; the idea is the same.
 */
public final class SurgeZones {

    /** Size of a cell in degrees. */
    public static final double CELL_DEGREES = 0.02;
    /** The key under which a cell's multiplier is stored in Redis: "surge:c640_3880". */
    public static final String KEY_PREFIX = "surge:";

    private SurgeZones() {
    }

    public static String cellOf(double latitude, double longitude) {
        return "c" + index(latitude) + "_" + index(longitude);
    }

    public static String keyOf(String cell) {
        return KEY_PREFIX + cell;
    }

    /** The middle of a cell (used to look for drivers around it). */
    public static double[] centerOf(String cell) {
        String[] parts = cell.substring(1).split("_");
        return new double[] {(Long.parseLong(parts[0]) + 0.5) * CELL_DEGREES, (Long.parseLong(parts[1]) + 0.5) * CELL_DEGREES};
    }

    /** Radius, in km, of a circle that covers a whole cell (half the diagonal at the equator, rounded up). */
    public static double coverRadiusKm() {
        return 1.6;
    }

    private static long index(double degrees) {
        return (long) Math.floor(degrees / CELL_DEGREES);
    }
}
