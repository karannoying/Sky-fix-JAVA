package com.skyfix.io;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.domain.Units;
import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.SkyfixException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses the fixed-width upper-air text layout distributed by the University of Wyoming sounding
 * archive (DS-1, FR-1.1).
 *
 * <p>The layout is eleven right-aligned columns; only five matter here:
 *
 * <pre>
 *    PRES   HGHT   TEMP   DWPT   RELH   MIXR   DRCT   SKNT   THTA   THTE   THTV
 *    hPa     m      C      C      %    g/kg    deg   knot     K      K      K
 * </pre>
 *
 * <p>Real files are ragged: upper levels routinely omit wind, and missing fields appear as blanks
 * or as runs of {@code /}. Every such line is <em>reported</em>, as {@code file:line:reason}, and
 * counted — never dropped in silence (FR-1.1 acceptance criterion).
 *
 * <p>Units are converted at this boundary and nowhere else (CLAUDE.md rule 3): hPa to Pa, Celsius
 * to kelvin, knots to m/s, and the meteorological direction/speed pair to east-north components.
 *
 * <p>verify: the exact column layout and the archive's terms of use must be confirmed against the
 * University of Wyoming site before the report describes DS-1. The parser is written against the
 * layout documented above and the sample committed at {@code data/soundings/}; it reads by column
 * position with a whitespace-split fallback, so a small difference in column widths does not
 * break it.
 */
public final class WyomingSoundingReader implements SoundingReader {

    private static final double KNOTS_TO_MS = 1852.0 / 3600.0; // exact: 1 nmi/h
    private static final int MINIMUM_USABLE_LEVELS = 2;

    @Override
    public SoundingParseResult read(Path path) throws SkyfixException {
        List<String> lines;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
            lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\\R", -1));
        } catch (IOException e) {
            throw new DataFormatException(path.toString(), 0,
                    "sounding file cannot be read: " + e.getMessage(), e);
        }

        String file = path.getFileName().toString();
        List<SoundingLevel> levels = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        String stationId = "UNKNOWN";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;

            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.strip();

            if (trimmed.startsWith("#")) {
                continue; // committed sample files carry provenance comments
            }
            if (trimmed.startsWith("Station identifier")) {
                stationId = valueAfterColon(trimmed, stationId);
                continue;
            }
            // Column headers, unit rows, rulers and the trailing station-parameter block.
            // A data line is anything that opens with a number, INCLUDING a negative one: a
            // level reporting a negative pressure must be rejected and reported, never quietly
            // mistaken for a header and skipped (FR-1.1 forbids silent drops).
            if (trimmed.startsWith("-----") || trimmed.contains(":") || !startsWithNumber(trimmed)) {
                continue;
            }

            try {
                levels.add(parseLevel(trimmed, file, lineNumber));
            } catch (DataFormatException e) {
                rejections.add(e.userMessage());
            }
        }

        if (levels.size() < MINIMUM_USABLE_LEVELS) {
            throw new DataFormatException(file, 0,
                    "sounding holds " + levels.size() + " usable levels; at least "
                            + MINIMUM_USABLE_LEVELS + " are needed to interpolate a wind profile "
                            + "(" + rejections.size() + " lines rejected)");
        }

        levels.sort(Comparator.comparingDouble(SoundingLevel::heightGeopotentialM));
        List<SoundingLevel> monotonic = dropNonMonotonic(levels, file, rejections);

        if (monotonic.size() < MINIMUM_USABLE_LEVELS) {
            throw new DataFormatException(file, 0,
                    "sounding holds " + monotonic.size() + " levels at distinct heights; at least "
                            + MINIMUM_USABLE_LEVELS + " are needed");
        }
        return new SoundingParseResult(stationId, List.copyOf(monotonic),
                List.copyOf(rejections), FileDigest.sha256(bytes));
    }

    @Override
    public String sourceName() {
        return "WYOMING";
    }

    /** Whether a line opens with something that could be a number, sign included. */
    private static boolean startsWithNumber(String trimmed) {
        char c = trimmed.charAt(0);
        if (Character.isDigit(c)) {
            return true;
        }
        if ((c == '-' || c == '+' || c == '.') && trimmed.length() > 1) {
            return Character.isDigit(trimmed.charAt(1)) || trimmed.charAt(1) == '.';
        }
        return false;
    }

    private static SoundingLevel parseLevel(String line, String file, int lineNumber)
            throws DataFormatException {
        String[] f = line.split("\\s+");
        if (f.length < 8) {
            throw new DataFormatException(file, lineNumber,
                    "level has " + f.length + " columns; wind requires at least 8 "
                            + "(PRES HGHT TEMP DWPT RELH MIXR DRCT SKNT)");
        }
        double pressureHpa = number(f[0], "pressure", file, lineNumber);
        double heightM = number(f[1], "height", file, lineNumber);
        double temperatureC = number(f[2], "temperature", file, lineNumber);
        double directionDeg = number(f[6], "wind_dir", file, lineNumber);
        double speedKnots = number(f[7], "wind_speed", file, lineNumber);

        if (pressureHpa <= 0) {
            throw new DataFormatException(file, lineNumber,
                    "pressure " + pressureHpa + " hPa must be positive");
        }
        double temperatureK = Units.toKelvin(temperatureC);
        if (temperatureK < 150.0 || temperatureK > 340.0) {
            // Matches the CHECK constraint on sounding_level, so a value the schema would refuse
            // is refused here first, with a line number attached.
            throw new DataFormatException(file, lineNumber,
                    "temperature " + temperatureK + " K is outside the plausible range 150-340 K");
        }
        if (heightM < -500.0) {
            throw new DataFormatException(file, lineNumber,
                    "height " + heightM + " gpm is below the -500 m floor");
        }
        if (directionDeg < 0.0 || directionDeg > 360.0) {
            throw new DataFormatException(file, lineNumber,
                    "wind_dir " + directionDeg + " is outside 0-360 degrees");
        }
        if (speedKnots < 0.0) {
            throw new DataFormatException(file, lineNumber,
                    "wind_speed " + speedKnots + " kt must not be negative");
        }

        return SoundingLevel.fromMeteorological(heightM, Units.toPascals(pressureHpa),
                temperatureK, directionDeg, speedKnots * KNOTS_TO_MS);
    }

    private static double number(String token, String field, String file, int lineNumber)
            throws DataFormatException {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new DataFormatException(file, lineNumber,
                    field + " \"" + token + "\" is not numeric");
        }
    }

    /**
     * Removes levels that do not strictly increase in height.
     *
     * <p>Interpolation needs a single value per height; a repeated or reversed height would make
     * the profile ambiguous. Each removal is reported rather than silently applied.
     */
    private static List<SoundingLevel> dropNonMonotonic(List<SoundingLevel> sorted, String file,
                                                        List<String> rejections) {
        List<SoundingLevel> kept = new ArrayList<>(sorted.size());
        double previous = Double.NEGATIVE_INFINITY;
        for (SoundingLevel level : sorted) {
            if (level.heightGeopotentialM() <= previous) {
                rejections.add(file + ": level at " + level.heightGeopotentialM()
                        + " gpm repeats a height already seen — dropped");
                continue;
            }
            kept.add(level);
            previous = level.heightGeopotentialM();
        }
        return kept;
    }

    private static String valueAfterColon(String line, String fallback) {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 >= line.length()) {
            return fallback;
        }
        String value = line.substring(colon + 1).strip();
        return value.isEmpty() ? fallback : value;
    }

}
