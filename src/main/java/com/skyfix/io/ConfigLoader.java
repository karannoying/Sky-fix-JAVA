package com.skyfix.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads {@code mission.json} and {@code balloon.json} into validated domain objects (FR-1.3).
 *
 * <p>Jackson is used for JSON syntax only; every semantic rule is the domain's, so a malformed
 * number is a {@link DataFormatException} naming the file and a rule violation is a
 * {@link ValidationException} naming the field. A missing required key is reported by name rather
 * than defaulting silently, because a silently-defaulted mass is a wrong landing point.
 */
public final class ConfigLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Reads a balloon configuration.
     *
     * @param path the {@code balloon.json} file
     * @return the validated configuration
     * @throws SkyfixException if the file cannot be parsed, or a field breaks a rule
     */
    public BalloonConfig loadBalloon(Path path) throws SkyfixException {
        JsonNode root = read(path);
        try {
            return BalloonConfig.builder()
                    .name(text(root, "name", path))
                    .payloadMassKg(number(root, "payload_mass_kg", path))
                    .envelopeMassKg(number(root, "envelope_mass_kg", path))
                    .launchDiameterM(number(root, "launch_diameter_m", path))
                    .burstDiameterM(number(root, "burst_diameter_m", path))
                    .freeLiftKg(number(root, "free_lift_kg", path))
                    .ascentCd(number(root, "ascent_cd", path))
                    .chuteAreaM2(number(root, "chute_area_m2", path))
                    .chuteCd(number(root, "chute_cd", path))
                    .gas(gas(root, path))
                    .build();
        } catch (ValidationException e) {
            // Re-thrown with the file attached, so the user knows which of the two files to edit.
            throw (ValidationException) e.with("file", path.getFileName().toString());
        }
    }

    /**
     * Reads a mission definition.
     *
     * @param path the {@code mission.json} file
     * @return the mission
     * @throws SkyfixException if the file cannot be parsed, or a field breaks a rule
     */
    public MissionSpec loadMission(Path path) throws SkyfixException {
        JsonNode root = read(path);
        GeoPoint launch = GeoPoint.of(
                number(root, "launch_lat", path),
                number(root, "launch_lon", path),
                number(root, "launch_alt_m", path));
        double groundElevation = root.has("ground_elev_m")
                ? number(root, "ground_elev_m", path)
                : launch.altitudeM();
        return new MissionSpec(
                text(root, "name", path),
                launch,
                groundElevation,
                text(root, "launch_epoch_utc", path));
    }

    /**
     * Reads optional integration settings, falling back to the documented defaults.
     *
     * @param root             the mission node, which may carry a {@code sim} object
     * @param groundElevationM ground elevation to integrate down to, metres
     * @return the validated settings
     * @throws ValidationException if a supplied setting breaks a rule
     */
    public SimSettings loadSimSettings(JsonNode root, double groundElevationM)
            throws ValidationException {
        SimSettings.Builder builder = SimSettings.builder().groundElevationM(groundElevationM);
        JsonNode sim = root == null ? null : root.get("sim");
        if (sim != null) {
            if (sim.has("step_s")) {
                builder.stepSeconds(sim.get("step_s").asDouble());
            }
            if (sim.has("integrator")) {
                builder.integrator(sim.get("integrator").asText());
            }
            if (sim.has("t_end_s")) {
                builder.endSeconds(sim.get("t_end_s").asDouble());
            }
            if (sim.has("state_sample_stride")) {
                builder.stateSampleStride(sim.get("state_sample_stride").asInt());
            }
        }
        return builder.build();
    }

    /**
     * Reads a JSON file into a tree.
     *
     * @param path the file
     * @return the parsed root node
     * @throws DataFormatException if the file is missing or not valid JSON
     */
    public JsonNode read(Path path) throws DataFormatException {
        try {
            return mapper.readTree(Files.readString(path));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            int line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
            throw new DataFormatException(path.toString(), line,
                    "not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new DataFormatException(path.toString(), 0,
                    "cannot be read: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode root, String field, Path path)
            throws ValidationException {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw ValidationException.field(field, "absent",
                    "is required in " + path.getFileName());
        }
        return node.asText();
    }

    private static double number(JsonNode root, String field, Path path)
            throws ValidationException {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw ValidationException.field(field, "absent",
                    "is required in " + path.getFileName());
        }
        if (!node.isNumber()) {
            throw ValidationException.field(field, node.asText(),
                    "must be a number in " + path.getFileName());
        }
        return node.asDouble();
    }

    private static LiftGas gas(JsonNode root, Path path) throws ValidationException {
        String name = text(root, "gas", path);
        try {
            return LiftGas.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ValidationException.field("gas", name, "must be one of HELIUM, HYDROGEN");
        }
    }

    /**
     * A mission as written in {@code mission.json}.
     *
     * @param name             mission name, unique within the database
     * @param launch           launch position; altitude is geometric above MSL, metres
     * @param groundElevationM ground elevation to integrate down to, metres above MSL
     * @param launchEpochUtc   launch time, ISO-8601 UTC
     */
    public record MissionSpec(String name, GeoPoint launch, double groundElevationM,
                              String launchEpochUtc) {
    }
}
