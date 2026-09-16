package com.skyfix.core.atmos;

/**
 * A wind that is the same everywhere and at all times.
 *
 * <p>This is the analytic oracle the flight tests lean on: under a constant wind, horizontal drift
 * is exactly {@code speed x time}, so a simulated trajectory can be checked against arithmetic
 * instead of against another simulation (T-V4, T-E4).
 */
public final class ConstantWindField implements WindField {

    private final WindSample wind;

    /**
     * Creates a uniform wind.
     *
     * @param eastMs  eastward component, m/s
     * @param northMs northward component, m/s
     */
    public ConstantWindField(double eastMs, double northMs) {
        this.wind = new WindSample(eastMs, northMs, false);
    }

    /** @return a field with no wind at all */
    public static ConstantWindField calm() {
        return new ConstantWindField(0.0, 0.0);
    }

    @Override
    public WindSample at(double timeSeconds, double altitudeM) {
        return wind;
    }

    @Override
    public String name() {
        return "CONSTANT";
    }
}
