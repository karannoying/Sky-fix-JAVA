package com.skyfix.core.atmos;

import com.skyfix.domain.error.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wind interpolated linearly in geopotential height from a single radiosonde sounding (FR-2.2,
 * ADR-7).
 *
 * <p>One sounding is assumed to describe the whole flight, in time and along up to ~400 km of
 * drift. That is the model's largest named error source. It is not hidden: the ensemble disperses
 * a {@code wind_scale} multiplier to widen the footprint over it (ADR-7), and the report states it.
 *
 * <p>Behaviour at the edges of the profile, per FR-2.2:
 * <ul>
 *   <li>a query at a node height returns that node's values exactly;</li>
 *   <li>between nodes, {@code u} and {@code v} are linear in geopotential height;</li>
 *   <li>above the top level or below the bottom the nearest level's wind is held, and the returned
 *       sample is flagged {@code extrapolated} so the run summary can count it.</li>
 * </ul>
 *
 * <p>Immutable after construction and queried without locking, so ensemble threads share one
 * instance (ADR-5).
 */
public final class SoundingWindField implements WindField {

    private final double[] heights;
    private final double[] east;
    private final double[] north;
    private final String stationId;

    private SoundingWindField(String stationId, double[] heights, double[] east, double[] north) {
        this.stationId = stationId;
        this.heights = heights;
        this.east = east;
        this.north = north;
    }

    /**
     * Builds a wind field from sounding levels.
     *
     * <p>Levels are sorted by height and duplicates at the same height are rejected: an
     * interpolation table with two values at one height has no defined answer.
     *
     * @param stationId the reporting station, for run records
     * @param levels    the sounding levels; at least two are needed to interpolate
     * @return the wind field
     * @throws ValidationException if fewer than two levels are supplied or two share a height
     */
    public static SoundingWindField of(String stationId, List<SoundingLevel> levels)
            throws ValidationException {
        if (levels == null || levels.size() < 2) {
            throw ValidationException.field("level_count",
                    levels == null ? 0 : levels.size(),
                    "must be at least 2 for a wind profile to be interpolated");
        }
        List<SoundingLevel> sorted = new ArrayList<>(levels);
        sorted.sort(Comparator.comparingDouble(SoundingLevel::heightGeopotentialM));

        int n = sorted.size();
        double[] h = new double[n];
        double[] u = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            SoundingLevel level = sorted.get(i);
            if (i > 0 && level.heightGeopotentialM() == h[i - 1]) {
                throw ValidationException.field("height_gpm", level.heightGeopotentialM(),
                        "appears twice in the sounding; heights must be strictly increasing");
            }
            h[i] = level.heightGeopotentialM();
            u[i] = level.windEastMs();
            v[i] = level.windNorthMs();
        }
        return new SoundingWindField(stationId, h, u, v);
    }

    @Override
    public WindSample at(double timeSeconds, double altitudeM) {
        // The profile is indexed by geopotential height; the balloon's altitude is geometric.
        double height = Ussa1976Atmosphere.toGeopotentialM(altitudeM);

        if (height <= heights[0]) {
            boolean extrapolated = height < heights[0];
            return new WindSample(east[0], north[0], extrapolated);
        }
        int top = heights.length - 1;
        if (height >= heights[top]) {
            boolean extrapolated = height > heights[top];
            return new WindSample(east[top], north[top], extrapolated);
        }

        int i = upperIndexFor(height);
        double span = heights[i] - heights[i - 1];
        double fraction = (height - heights[i - 1]) / span;
        return new WindSample(
                east[i - 1] + fraction * (east[i] - east[i - 1]),
                north[i - 1] + fraction * (north[i] - north[i - 1]),
                false);
    }

    @Override
    public String name() {
        return "SOUNDING";
    }

    /** @return the station this profile came from */
    public String stationId() {
        return stationId;
    }

    /** @return the number of levels in the profile */
    public int levelCount() {
        return heights.length;
    }

    /** @return the lowest geopotential height in the profile, metres */
    public double bottomHeightM() {
        return heights[0];
    }

    /** @return the highest geopotential height in the profile, metres */
    public double topHeightM() {
        return heights[heights.length - 1];
    }

    /** Binary search for the first index whose height strictly exceeds the query. */
    private int upperIndexFor(double height) {
        int low = 1;
        int high = heights.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (heights[mid] <= height) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
