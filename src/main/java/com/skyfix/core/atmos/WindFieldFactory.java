package com.skyfix.core.atmos;

import com.skyfix.domain.error.ValidationException;

import java.util.List;
import java.util.Locale;

/**
 * Resolves a wind-field name from configuration to an implementation (BLUEPRINT §8).
 *
 * <p>Like {@code IntegratorFactory}, the single place an unknown name becomes a
 * {@link ValidationException} naming the field and listing what is available.
 */
public final class WindFieldFactory {

    private WindFieldFactory() {
    }

    /**
     * Creates a wind field from a sounding, or a calm or constant field for testing.
     *
     * @param name      {@code SOUNDING}, {@code CONSTANT} or {@code CALM}, case-insensitive
     * @param stationId station identifier, used only by {@code SOUNDING}
     * @param levels    sounding levels, required by {@code SOUNDING} and ignored otherwise
     * @param eastMs    eastward component used by {@code CONSTANT}
     * @param northMs   northward component used by {@code CONSTANT}
     * @return the wind field
     * @throws ValidationException if the name is unknown, or a sounding field is asked for
     *                             without enough levels
     */
    public static WindField create(String name, String stationId, List<SoundingLevel> levels,
                                   double eastMs, double northMs) throws ValidationException {
        if (name == null || name.isBlank()) {
            throw ValidationException.field("wind_field", name, "must be one of " + available());
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "SOUNDING" -> SoundingWindField.of(stationId, levels);
            case "CONSTANT" -> new ConstantWindField(eastMs, northMs);
            case "CALM" -> ConstantWindField.calm();
            default -> throw ValidationException.field("wind_field", name,
                    "is not a known wind field; expected one of " + available());
        };
    }

    /** @return the wind-field names this factory accepts */
    public static List<String> available() {
        return List.of("SOUNDING", "CONSTANT", "CALM");
    }
}
