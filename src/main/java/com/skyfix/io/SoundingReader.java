package com.skyfix.io;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.domain.error.SkyfixException;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads a radiosonde sounding from a file into SI levels (FR-1.1).
 *
 * <p>Implementations report every rejected line as {@code file:line:reason} rather than dropping
 * it silently, and return the surviving levels sorted by height.
 */
public interface SoundingReader {

    /**
     * Parses a sounding file.
     *
     * @param path the file to read
     * @return the parse result: accepted levels plus a report of what was rejected and why
     * @throws SkyfixException if the file cannot be read, or holds too few usable levels
     */
    SoundingParseResult read(Path path) throws SkyfixException;

    /** @return the source label stored with the sounding, matching the schema's CHECK constraint */
    String sourceName();

    /**
     * The outcome of parsing a sounding.
     *
     * @param stationId  the reporting station, as far as the file identifies it
     * @param levels     the accepted levels, sorted by geopotential height
     * @param rejections one message per rejected line, each naming {@code file:line:reason}
     * @param fileSha256 SHA-256 of the file's bytes, so a re-import can be recognised (FR-1.1)
     */
    record SoundingParseResult(String stationId, List<SoundingLevel> levels,
                               List<String> rejections, String fileSha256) {

        /** @return how many lines were rejected */
        public int rejectedCount() {
            return rejections.size();
        }

        /** @return how many levels were accepted */
        public int levelCount() {
            return levels.size();
        }
    }
}
