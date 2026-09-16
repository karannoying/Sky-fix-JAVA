package com.skyfix.io;

import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.domain.TelemetrySample;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.ModelDomainException;
import com.skyfix.domain.error.SkyfixException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a flight-computer telemetry log in CSV (FR-1.2, DS-5).
 *
 * <p>Expected columns, by header name rather than position, so a log with extra channels still
 * parses: {@code epoch_utc, packet_id, lat, lon, alt_gps_m, pressure_pa, temperature_k}. The last
 * three may be blank on any row — a GPS fix drops while the barometer keeps reporting, and not
 * every payload carries a thermometer.
 *
 * <p>What this class is really for is the accounting. A radio link delivers packets late, twice, or
 * not at all, and the tempting thing is to sort, de-duplicate and move on. FR-1.2 forbids that:
 * duplicate packet ids and out-of-order timestamps are <em>counted and surfaced</em>, because a log
 * with three hundred duplicates is telling you something about the radio link that a silently
 * cleaned series would hide.
 *
 * <p>Where a sample has pressure but no GPS altitude, the altitude is derived from the standard
 * atmosphere and flagged {@link TelemetrySample#FLAG_PRESSURE_ALTITUDE}, so a reader can always
 * tell a measured altitude from a computed one.
 */
public final class CsvTelemetryReader {

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();

    /**
     * Parses a telemetry log.
     *
     * @param path the CSV file
     * @return the ordered, de-duplicated series with its anomaly counts
     * @throws SkyfixException if the file cannot be read, or holds no usable samples
     */
    public TelemetrySeries read(Path path) throws SkyfixException {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataFormatException(path.toString(), 0,
                    "telemetry log cannot be read: " + e.getMessage(), e);
        }

        String file = path.getFileName().toString();
        List<String> rejections = new ArrayList<>();
        List<TelemetrySample> accepted = new ArrayList<>();
        Set<Integer> seenPacketIds = new HashSet<>();

        int[] header = null;
        int duplicates = 0;
        int outOfOrder = 0;
        int dropouts = 0;
        Instant previousEpoch = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (header == null) {
                header = mapHeader(trimmed, file, lineNumber);
                continue;
            }

            TelemetrySample sample;
            try {
                sample = parseSample(trimmed, header, file, lineNumber);
            } catch (DataFormatException e) {
                rejections.add(e.userMessage());
                continue;
            }

            // A repeated packet id is the radio delivering the same packet twice. The later copy is
            // discarded -- keeping both would double-weight that instant in the filter -- but the
            // count is what gets reported.
            if (!seenPacketIds.add(sample.packetId())) {
                duplicates++;
                continue;
            }
            if (previousEpoch != null && sample.epochUtc().isBefore(previousEpoch)) {
                outOfOrder++;
                sample = sample.withFlags(TelemetrySample.FLAG_OUT_OF_ORDER);
            }
            previousEpoch = sample.epochUtc();

            if (!sample.hasGpsAltitude()) {
                dropouts++;
                sample = sample.withFlags(TelemetrySample.FLAG_GPS_DROPOUT);
                if (sample.hasPressure()) {
                    try {
                        sample = sample.withDerivedAltitude(
                                ATMOSPHERE.altitudeForPressure(sample.pressurePa()));
                    } catch (ModelDomainException e) {
                        // A pressure the atmosphere cannot place is reported, and the sample is
                        // kept without an altitude rather than given a fabricated one.
                        rejections.add(file + ":" + lineNumber + ": pressure "
                                + sample.pressurePa() + " Pa is outside the model's range, so no "
                                + "pressure altitude was derived");
                    }
                }
            }
            accepted.add(sample);
        }

        if (header == null) {
            throw new DataFormatException(file, 0, "telemetry log has no header row");
        }
        if (accepted.isEmpty()) {
            throw new DataFormatException(file, 0,
                    "telemetry log holds no usable samples (" + rejections.size()
                            + " lines rejected, " + duplicates + " duplicate packet ids)");
        }

        // Sorted by time for replay. The out-of-order count above was taken from the file's own
        // order, before this, so sorting cannot launder it.
        accepted.sort(Comparator.comparing(TelemetrySample::epochUtc)
                .thenComparingInt(TelemetrySample::packetId));

        return new TelemetrySeries(accepted, duplicates, outOfOrder, dropouts, rejections);
    }

    /** Column indices for the fields this reader needs, resolved by header name. */
    private static int[] mapHeader(String line, String file, int lineNumber)
            throws DataFormatException {
        String[] names = line.split(",", -1);
        int[] indices = new int[7];
        java.util.Arrays.fill(indices, -1);
        for (int i = 0; i < names.length; i++) {
            switch (names[i].strip().toLowerCase(java.util.Locale.ROOT)) {
                case "epoch_utc", "time", "utc" -> indices[0] = i;
                case "packet_id", "packet", "seq" -> indices[1] = i;
                case "lat", "latitude" -> indices[2] = i;
                case "lon", "longitude" -> indices[3] = i;
                case "alt_gps_m", "alt", "altitude" -> indices[4] = i;
                case "pressure_pa", "pressure" -> indices[5] = i;
                case "temperature_k", "temperature", "temp" -> indices[6] = i;
                default -> { /* extra channels are ignored, not an error */ }
            }
        }
        for (int required : new int[]{0, 1, 2, 3}) {
            if (indices[required] < 0) {
                throw new DataFormatException(file, lineNumber,
                        "header must name epoch_utc, packet_id, lat and lon; got \"" + line + "\"");
            }
        }
        return indices;
    }

    private static TelemetrySample parseSample(String line, int[] header, String file,
                                               int lineNumber) throws DataFormatException {
        String[] f = line.split(",", -1);
        int widest = 0;
        for (int index : header) {
            widest = Math.max(widest, index);
        }
        if (f.length <= widest) {
            throw new DataFormatException(file, lineNumber,
                    "row has " + f.length + " columns; the header names " + (widest + 1));
        }

        Instant epoch = instant(f[header[0]], file, lineNumber);
        int packetId = integer(f[header[1]], "packet_id", file, lineNumber);
        double lat = number(f[header[2]], "lat", file, lineNumber);
        double lon = number(f[header[3]], "lon", file, lineNumber);

        if (lat < -90 || lat > 90) {
            throw new DataFormatException(file, lineNumber,
                    "lat " + lat + " is outside -90 to 90");
        }
        if (lon < -180 || lon > 180) {
            throw new DataFormatException(file, lineNumber,
                    "lon " + lon + " is outside -180 to 180");
        }

        double altitude = optional(f, header[4], "alt_gps_m", file, lineNumber);
        double pressure = optional(f, header[5], "pressure_pa", file, lineNumber);
        double temperature = optional(f, header[6], "temperature_k", file, lineNumber);

        if (!Double.isNaN(pressure) && pressure <= 0) {
            throw new DataFormatException(file, lineNumber,
                    "pressure_pa " + pressure + " must be positive");
        }
        return new TelemetrySample(epoch, packetId, lat, lon, altitude, pressure, temperature, 0);
    }

    private static double optional(String[] fields, int index, String name, String file,
                                   int lineNumber) throws DataFormatException {
        if (index < 0 || index >= fields.length) {
            return Double.NaN;
        }
        String token = fields[index].strip();
        if (token.isEmpty() || token.equalsIgnoreCase("nan") || token.equals("-")) {
            return Double.NaN;
        }
        return number(token, name, file, lineNumber);
    }

    private static Instant instant(String token, String file, int lineNumber)
            throws DataFormatException {
        String value = token.strip();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            // A log written without the trailing Z is common enough to be worth accepting.
            try {
                return Instant.parse(value + "Z");
            } catch (DateTimeParseException still) {
                throw new DataFormatException(file, lineNumber,
                        "epoch_utc \"" + token + "\" is not an ISO-8601 instant");
            }
        }
    }

    private static int integer(String token, String name, String file, int lineNumber)
            throws DataFormatException {
        try {
            return Integer.parseInt(token.strip());
        } catch (NumberFormatException e) {
            throw new DataFormatException(file, lineNumber,
                    name + " \"" + token + "\" is not an integer");
        }
    }

    private static double number(String token, String name, String file, int lineNumber)
            throws DataFormatException {
        try {
            return Double.parseDouble(token.strip());
        } catch (NumberFormatException e) {
            throw new DataFormatException(file, lineNumber,
                    name + " \"" + token + "\" is not numeric");
        }
    }
}
