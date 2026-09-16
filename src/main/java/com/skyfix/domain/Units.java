package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

import java.util.Locale;

/**
 * Unit conversion at the edges of the system.
 *
 * <p>SKYFIX is SI internally — metres, kilograms, seconds, kelvin, pascals (CLAUDE.md rule 3).
 * Nothing inside {@code core.*} converts units; every conversion a user-facing value needs happens
 * here, once, at ingest or at display.
 *
 * <p>The converters are overloaded rather than named per-unit: {@link #toMetres(double)} takes the
 * imperial default, {@link #toMetres(double, LengthUnit)} takes an explicit unit, and
 * {@link #toMetres(String)} parses a written quantity such as {@code "95000 ft"} straight from a
 * configuration file or command line.
 */
public final class Units {

    /** Standard acceleration of free fall, m/s^2. Defining constant of USSA-1976. */
    public static final double STANDARD_GRAVITY = 9.80665;

    /** Universal gas constant as fixed by USSA-1976, J/(mol K). */
    public static final double UNIVERSAL_GAS_CONSTANT = 8.31432;

    /** Zero degrees Celsius expressed in kelvin. */
    public static final double CELSIUS_ZERO_K = 273.15;

    private static final double METRES_PER_FOOT = 0.3048;          // exact, by definition
    private static final double METRES_PER_NAUTICAL_MILE = 1852.0; // exact, by definition
    private static final double METRES_PER_MILE = 1609.344;        // exact: 5280 ft
    private static final double KILOGRAMS_PER_POUND = 0.45359237;  // exact, by definition
    private static final double PASCALS_PER_HECTOPASCAL = 100.0;
    private static final double PASCALS_PER_MILLIBAR = 100.0;      // 1 mbar == 1 hPa, exact

    private Units() {
    }

    /** Length units accepted at the system edges. */
    public enum LengthUnit {
        /** Metre — the internal unit; conversion is the identity. */
        METRE,
        /** Kilometre. */
        KILOMETRE,
        /** International foot, exactly 0.3048 m. */
        FOOT,
        /** International nautical mile, exactly 1852 m. */
        NAUTICAL_MILE,
        /** International (statute) mile, exactly 1609.344 m. */
        MILE
    }

    /** Mass units accepted at the system edges. */
    public enum MassUnit {
        /** Kilogram — the internal unit. */
        KILOGRAM,
        /** Gram. */
        GRAM,
        /** International avoirdupois pound, exactly 0.45359237 kg. */
        POUND
    }

    /** Pressure units accepted at the system edges. */
    public enum PressureUnit {
        /** Pascal — the internal unit. */
        PASCAL,
        /** Hectopascal, the unit radiosonde files report. */
        HECTOPASCAL,
        /** Millibar, numerically identical to the hectopascal. */
        MILLIBAR
    }

    /** Temperature units accepted at the system edges. */
    public enum TemperatureUnit {
        /** Kelvin — the internal unit. */
        KELVIN,
        /** Degrees Celsius, the unit radiosonde files report. */
        CELSIUS
    }

    /**
     * Converts a length in international feet to metres.
     *
     * <p>Feet are the unqualified overload because aviation altitudes — the only lengths a SKYFIX
     * user is likely to type in a non-SI unit — are quoted in feet.
     *
     * @param feet length in international feet
     * @return the same length in metres
     */
    public static double toMetres(double feet) {
        return feet * METRES_PER_FOOT;
    }

    /**
     * Converts a length in an explicit unit to metres.
     *
     * @param value length in {@code unit}
     * @param unit  the unit {@code value} is expressed in
     * @return the same length in metres
     */
    public static double toMetres(double value, LengthUnit unit) {
        return switch (unit) {
            case METRE -> value;
            case KILOMETRE -> value * 1000.0;
            case FOOT -> value * METRES_PER_FOOT;
            case NAUTICAL_MILE -> value * METRES_PER_NAUTICAL_MILE;
            case MILE -> value * METRES_PER_MILE;
        };
    }

    /**
     * Parses a written length such as {@code "30 km"}, {@code "95000ft"} or {@code "1200"} and
     * converts it to metres. A bare number is taken to be metres already.
     *
     * @param quantity the written quantity; whitespace between number and unit is optional
     * @return the length in metres
     * @throws ValidationException if the number or the unit suffix cannot be understood
     */
    public static double toMetres(String quantity) throws ValidationException {
        Parsed p = parse(quantity, "length");
        LengthUnit unit = switch (p.unit) {
            case "", "m" -> LengthUnit.METRE;
            case "km" -> LengthUnit.KILOMETRE;
            case "ft", "feet" -> LengthUnit.FOOT;
            case "nm", "nmi" -> LengthUnit.NAUTICAL_MILE;
            case "mi", "mile", "miles" -> LengthUnit.MILE;
            default -> throw ValidationException.field(
                    "length", quantity, "has unknown unit \"" + p.unit
                            + "\"; expected one of m, km, ft, nm, mi");
        };
        return toMetres(p.value, unit);
    }

    /**
     * Converts a mass in international pounds to kilograms.
     *
     * @param pounds mass in avoirdupois pounds
     * @return the same mass in kilograms
     */
    public static double toKilograms(double pounds) {
        return pounds * KILOGRAMS_PER_POUND;
    }

    /**
     * Converts a mass in an explicit unit to kilograms.
     *
     * @param value mass in {@code unit}
     * @param unit  the unit {@code value} is expressed in
     * @return the same mass in kilograms
     */
    public static double toKilograms(double value, MassUnit unit) {
        return switch (unit) {
            case KILOGRAM -> value;
            case GRAM -> value / 1000.0;
            case POUND -> value * KILOGRAMS_PER_POUND;
        };
    }

    /**
     * Parses a written mass such as {@code "1200 g"} or {@code "2.5kg"} and converts it to
     * kilograms. A bare number is taken to be kilograms already.
     *
     * @param quantity the written quantity
     * @return the mass in kilograms
     * @throws ValidationException if the number or the unit suffix cannot be understood
     */
    public static double toKilograms(String quantity) throws ValidationException {
        Parsed p = parse(quantity, "mass");
        MassUnit unit = switch (p.unit) {
            case "", "kg" -> MassUnit.KILOGRAM;
            case "g" -> MassUnit.GRAM;
            case "lb", "lbs" -> MassUnit.POUND;
            default -> throw ValidationException.field(
                    "mass", quantity, "has unknown unit \"" + p.unit
                            + "\"; expected one of kg, g, lb");
        };
        return toKilograms(p.value, unit);
    }

    /**
     * Converts a temperature in degrees Celsius to kelvin.
     *
     * @param celsius temperature in degrees Celsius
     * @return the same temperature in kelvin
     */
    public static double toKelvin(double celsius) {
        return celsius + CELSIUS_ZERO_K;
    }

    /**
     * Converts a temperature in an explicit unit to kelvin.
     *
     * @param value temperature in {@code unit}
     * @param unit  the unit {@code value} is expressed in
     * @return the same temperature in kelvin
     */
    public static double toKelvin(double value, TemperatureUnit unit) {
        return switch (unit) {
            case KELVIN -> value;
            case CELSIUS -> value + CELSIUS_ZERO_K;
        };
    }

    /**
     * Converts a pressure in hectopascals — the unit radiosonde files report — to pascals.
     *
     * @param hectopascals pressure in hPa
     * @return the same pressure in Pa
     */
    public static double toPascals(double hectopascals) {
        return hectopascals * PASCALS_PER_HECTOPASCAL;
    }

    /**
     * Converts a pressure in an explicit unit to pascals.
     *
     * @param value pressure in {@code unit}
     * @param unit  the unit {@code value} is expressed in
     * @return the same pressure in pascals
     */
    public static double toPascals(double value, PressureUnit unit) {
        return switch (unit) {
            case PASCAL -> value;
            case HECTOPASCAL -> value * PASCALS_PER_HECTOPASCAL;
            case MILLIBAR -> value * PASCALS_PER_MILLIBAR;
        };
    }

    /**
     * Converts metres back to feet, for display only.
     *
     * @param metres length in metres
     * @return the same length in international feet
     */
    public static double metresToFeet(double metres) {
        return metres / METRES_PER_FOOT;
    }

    /**
     * Converts kelvin back to degrees Celsius, for display only.
     *
     * @param kelvin temperature in kelvin
     * @return the same temperature in degrees Celsius
     */
    public static double kelvinToCelsius(double kelvin) {
        return kelvin - CELSIUS_ZERO_K;
    }

    private record Parsed(double value, String unit) {
    }

    private static Parsed parse(String quantity, String what) throws ValidationException {
        if (quantity == null || quantity.isBlank()) {
            throw ValidationException.field(what, quantity, "must not be blank");
        }
        String s = quantity.trim();
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end))
                || s.charAt(end) == '.' || s.charAt(end) == '-' || s.charAt(end) == '+'
                || s.charAt(end) == 'e' || s.charAt(end) == 'E')) {
            // An 'e' only belongs to the number when an exponent digit or sign follows it.
            if ((s.charAt(end) == 'e' || s.charAt(end) == 'E')
                    && (end + 1 >= s.length()
                        || !(Character.isDigit(s.charAt(end + 1))
                             || s.charAt(end + 1) == '-' || s.charAt(end + 1) == '+'))) {
                break;
            }
            end++;
        }
        try {
            double value = Double.parseDouble(s.substring(0, end));
            return new Parsed(value, s.substring(end).trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw ValidationException.field(what, quantity, "is not a number with an optional unit");
        }
    }
}
