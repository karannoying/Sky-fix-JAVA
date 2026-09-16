package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * An immutable, validated balloon configuration (FR-1.3).
 *
 * <p>Nine correlated parameters with cross-field physics rules, so construction goes through
 * {@link Builder} and every rule is checked once in {@link Builder#build()}. The result is
 * immutable, which is what lets a thousand ensemble threads share one instance safely (ADR-5).
 *
 * <p>Masses are kilograms, lengths metres, areas square metres; drag coefficients are
 * dimensionless.
 *
 * <p><strong>Free lift is the primary inflation parameter, not launch diameter.</strong> The
 * envelope volume at launch follows from the free lift and the launch-site air density
 * (see {@link #gasMassKg}); {@code launchDiameterM} is the manufacturer's nominal inflated
 * diameter, kept for the burst-diameter rule and for the cross-check reported by
 * {@link #inflatedDiameterM}. Free lift is also one of the four parameters the particle filter
 * estimates (FR-3.2), so the dynamics must be driven by it. See ADR-11.
 */
public final class BalloonConfig {

    private final String name;
    private final double payloadMassKg;
    private final double envelopeMassKg;
    private final double launchDiameterM;
    private final double burstDiameterM;
    private final double freeLiftKg;
    private final double ascentCd;
    private final double chuteAreaM2;
    private final double chuteCd;
    private final LiftGas gas;
    private final String configHash;

    private BalloonConfig(Builder b) {
        this.name = b.name;
        this.payloadMassKg = b.payloadMassKg;
        this.envelopeMassKg = b.envelopeMassKg;
        this.launchDiameterM = b.launchDiameterM;
        this.burstDiameterM = b.burstDiameterM;
        this.freeLiftKg = b.freeLiftKg;
        this.ascentCd = b.ascentCd;
        this.chuteAreaM2 = b.chuteAreaM2;
        this.chuteCd = b.chuteCd;
        this.gas = b.gas;
        this.configHash = sha256(canonicalForm());
    }

    /**
     * Starts a new builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder pre-loaded with this configuration's values, for deriving a variant.
     *
     * @return a builder that would rebuild an equal configuration
     */
    public Builder toBuilder() {
        return new Builder()
                .name(name)
                .payloadMassKg(payloadMassKg)
                .envelopeMassKg(envelopeMassKg)
                .launchDiameterM(launchDiameterM)
                .burstDiameterM(burstDiameterM)
                .freeLiftKg(freeLiftKg)
                .ascentCd(ascentCd)
                .chuteAreaM2(chuteAreaM2)
                .chuteCd(chuteCd)
                .gas(gas);
    }

    /** @return the configuration name as written by the user */
    public String name() {
        return name;
    }

    /** @return payload mass in kg — everything below the neck that is recovered */
    public double payloadMassKg() {
        return payloadMassKg;
    }

    /** @return envelope mass in kg, the latex itself */
    public double envelopeMassKg() {
        return envelopeMassKg;
    }

    /** @return the manufacturer's nominal inflated diameter at launch, m */
    public double launchDiameterM() {
        return launchDiameterM;
    }

    /** @return the diameter at which the envelope is expected to burst, m */
    public double burstDiameterM() {
        return burstDiameterM;
    }

    /** @return free lift in kg: buoyancy in excess of the total flying mass at launch */
    public double freeLiftKg() {
        return freeLiftKg;
    }

    /** @return the ascent drag coefficient of the inflated envelope, dimensionless */
    public double ascentCd() {
        return ascentCd;
    }

    /** @return parachute reference area in m^2 */
    public double chuteAreaM2() {
        return chuteAreaM2;
    }

    /** @return parachute drag coefficient, dimensionless */
    public double chuteCd() {
        return chuteCd;
    }

    /** @return the lifting gas */
    public LiftGas gas() {
        return gas;
    }

    /**
     * SHA-256 over the canonical rendering of every field (ADR-10).
     *
     * <p>A run is reproducible only if the exact configuration is pinned, so any edit — including
     * one that only changes a value in its last digit — yields a new hash and therefore a new run
     * identity.
     *
     * @return the 64-character lowercase hex digest
     */
    public String configHash() {
        return configHash;
    }

    /** @return dry mass in kg: payload plus envelope, what descends under the parachute */
    public double dryMassKg() {
        return payloadMassKg + envelopeMassKg;
    }

    /**
     * Envelope volume at launch, in m^3, derived from the free lift.
     *
     * <p>At launch the buoyancy must carry the dry mass, the gas itself and the free lift:
     * {@code rho_air V = m_dry + rho_gas V + freeLift}, so
     * {@code V = (m_dry + freeLift) / (rho_air - rho_gas)}.
     *
     * @param airDensityKgM3 ambient air density at the launch site, kg/m^3
     * @param pressurePa     ambient pressure at the launch site, Pa
     * @param temperatureK   ambient temperature at the launch site, K
     * @return the inflated volume in m^3
     * @throws ValidationException if the gas is not lighter than the surrounding air, which makes
     *                             the configuration unflyable
     */
    public double inflatedVolumeM3(double airDensityKgM3, double pressurePa, double temperatureK)
            throws ValidationException {
        double gasDensity = gas.densityAt(pressurePa, temperatureK);
        double buoyantDensity = airDensityKgM3 - gasDensity;
        if (buoyantDensity <= 0.0) {
            throw ValidationException.field("gas", gas,
                    "is not buoyant at the launch site (air " + airDensityKgM3
                            + " kg/m3, gas " + gasDensity + " kg/m3)");
        }
        return (dryMassKg() + freeLiftKg) / buoyantDensity;
    }

    /**
     * Mass of lifting gas needed to achieve {@link #freeLiftKg} at the launch site.
     *
     * <p>This mass is fixed for the whole flight — the envelope is sealed — and it is what drives
     * the gas-law expansion in {@link #volumeAt}.
     *
     * @param airDensityKgM3 ambient air density at the launch site, kg/m^3
     * @param pressurePa     ambient pressure at the launch site, Pa
     * @param temperatureK   ambient temperature at the launch site, K
     * @return the gas mass in kg
     * @throws ValidationException if the gas is not buoyant at the launch site
     */
    public double gasMassKg(double airDensityKgM3, double pressurePa, double temperatureK)
            throws ValidationException {
        return inflatedVolumeM3(airDensityKgM3, pressurePa, temperatureK)
                * gas.densityAt(pressurePa, temperatureK);
    }

    /**
     * Actual inflated diameter at launch implied by the free lift, in m.
     *
     * <p>Compared against {@link #launchDiameterM} this says whether the requested free lift
     * matches the manufacturer's nominal inflation; the CLI reports both.
     *
     * @param airDensityKgM3 ambient air density at the launch site, kg/m^3
     * @param pressurePa     ambient pressure at the launch site, Pa
     * @param temperatureK   ambient temperature at the launch site, K
     * @return the diameter in m of a sphere of the inflated volume
     * @throws ValidationException if the gas is not buoyant at the launch site
     */
    public double inflatedDiameterM(double airDensityKgM3, double pressurePa, double temperatureK)
            throws ValidationException {
        return diameterOfVolume(inflatedVolumeM3(airDensityKgM3, pressurePa, temperatureK));
    }

    /**
     * Envelope volume at altitude for a fixed gas mass, from the ideal gas law.
     *
     * <p>The unpressurised envelope holds the gas at ambient pressure and temperature, so
     * {@code V = m_gas R_specific T / p}. This is the only place the envelope expands.
     *
     * @param gasMass      the sealed-in gas mass, kg
     * @param pressurePa   ambient pressure at the current altitude, Pa
     * @param temperatureK ambient temperature at the current altitude, K
     * @return the envelope volume in m^3
     */
    public double volumeAt(double gasMass, double pressurePa, double temperatureK) {
        return gasMass * gas.specificGasConstant() * temperatureK / pressurePa;
    }

    /**
     * Diameter of the sphere with a given volume.
     *
     * @param volumeM3 volume in m^3
     * @return the diameter in m
     */
    public static double diameterOfVolume(double volumeM3) {
        return Math.cbrt(6.0 * volumeM3 / Math.PI);
    }

    /**
     * Volume of the sphere with a given diameter.
     *
     * @param diameterM diameter in m
     * @return the volume in m^3
     */
    public static double volumeOfDiameter(double diameterM) {
        return Math.PI * diameterM * diameterM * diameterM / 6.0;
    }

    /**
     * Frontal (cross-sectional) area of the sphere with a given diameter.
     *
     * @param diameterM diameter in m
     * @return the reference area in m^2 used by the drag term
     */
    public static double frontalAreaOfDiameter(double diameterM) {
        return Math.PI * diameterM * diameterM / 4.0;
    }

    private String canonicalForm() {
        // Locale.ROOT so a machine with a comma decimal separator produces the same hash.
        return String.format(Locale.ROOT,
                "name=%s;payload_mass_kg=%.9g;envelope_mass_kg=%.9g;launch_diameter_m=%.9g;"
                        + "burst_diameter_m=%.9g;free_lift_kg=%.9g;ascent_cd=%.9g;"
                        + "chute_area_m2=%.9g;chute_cd=%.9g;gas=%s",
                name, payloadMassKg, envelopeMassKg, launchDiameterM, burstDiameterM,
                freeLiftKg, ascentCd, chuteAreaM2, chuteCd, gas);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every conforming JRE; absence is a broken platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "BalloonConfig[" + name + ", hash=" + configHash.substring(0, 12) + "]";
    }

    /**
     * Collects and validates the nine balloon parameters (FR-1.3).
     *
     * <p>Every rule below has one negative test in {@code BalloonConfigBuilderTest} (T-U-BUILDER,
     * T-E1) and a matching CHECK constraint in {@code V1__init.sql}, so a value the Java layer
     * would reject cannot reach the database by another route either.
     */
    public static final class Builder {

        private static final double CD_MIN = 0.1;
        private static final double CD_MAX = 2.0;

        private String name;
        private double payloadMassKg = Double.NaN;
        private double envelopeMassKg = Double.NaN;
        private double launchDiameterM = Double.NaN;
        private double burstDiameterM = Double.NaN;
        private double freeLiftKg = Double.NaN;
        private double ascentCd = Double.NaN;
        private double chuteAreaM2 = Double.NaN;
        private double chuteCd = Double.NaN;
        private LiftGas gas;

        /** @param v configuration name @return this builder */
        public Builder name(String v) {
            this.name = v;
            return this;
        }

        /** @param v payload mass, kg @return this builder */
        public Builder payloadMassKg(double v) {
            this.payloadMassKg = v;
            return this;
        }

        /** @param v envelope mass, kg @return this builder */
        public Builder envelopeMassKg(double v) {
            this.envelopeMassKg = v;
            return this;
        }

        /** @param v nominal inflated diameter at launch, m @return this builder */
        public Builder launchDiameterM(double v) {
            this.launchDiameterM = v;
            return this;
        }

        /** @param v burst diameter, m @return this builder */
        public Builder burstDiameterM(double v) {
            this.burstDiameterM = v;
            return this;
        }

        /** @param v free lift, kg @return this builder */
        public Builder freeLiftKg(double v) {
            this.freeLiftKg = v;
            return this;
        }

        /** @param v ascent drag coefficient @return this builder */
        public Builder ascentCd(double v) {
            this.ascentCd = v;
            return this;
        }

        /** @param v parachute reference area, m^2 @return this builder */
        public Builder chuteAreaM2(double v) {
            this.chuteAreaM2 = v;
            return this;
        }

        /** @param v parachute drag coefficient @return this builder */
        public Builder chuteCd(double v) {
            this.chuteCd = v;
            return this;
        }

        /** @param v lifting gas @return this builder */
        public Builder gas(LiftGas v) {
            this.gas = v;
            return this;
        }

        /**
         * Validates every field and cross-field rule, then builds the immutable configuration.
         *
         * @return the validated configuration
         * @throws ValidationException naming the first field that breaks a rule
         */
        public BalloonConfig build() throws ValidationException {
            if (name == null || name.isBlank()) {
                throw ValidationException.field("name", name, "must not be blank");
            }
            requirePositive("payload_mass_kg", payloadMassKg);
            requirePositive("envelope_mass_kg", envelopeMassKg);
            requirePositive("launch_diameter_m", launchDiameterM);
            requirePositive("burst_diameter_m", burstDiameterM);
            requirePositive("free_lift_kg", freeLiftKg);
            requirePositive("chute_area_m2", chuteAreaM2);
            requireInRange("ascent_cd", ascentCd, CD_MIN, CD_MAX);
            requireInRange("chute_cd", chuteCd, CD_MIN, CD_MAX);
            if (gas == null) {
                throw ValidationException.field("gas", null,
                        "must be one of HELIUM, HYDROGEN");
            }
            if (burstDiameterM <= launchDiameterM) {
                throw ValidationException.field("burst_diameter_m", burstDiameterM,
                        "must exceed launch_diameter_m (" + launchDiameterM + ")");
            }
            return new BalloonConfig(this);
        }

        private static void requirePositive(String field, double value)
                throws ValidationException {
            if (Double.isNaN(value)) {
                throw ValidationException.field(field, "absent", "is required");
            }
            if (value <= 0.0) {
                throw ValidationException.field(field, value, "must be greater than 0");
            }
        }

        private static void requireInRange(String field, double value, double min, double max)
                throws ValidationException {
            if (Double.isNaN(value)) {
                throw ValidationException.field(field, "absent", "is required");
            }
            if (value < min || value > max) {
                throw ValidationException.field(field, value,
                        "must be between " + min + " and " + max);
            }
        }
    }
}
