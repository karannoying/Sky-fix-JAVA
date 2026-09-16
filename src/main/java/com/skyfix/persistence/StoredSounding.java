package com.skyfix.persistence;

import com.skyfix.core.atmos.SoundingLevel;

import java.util.List;

/**
 * A sounding and its levels as stored. Row shape of {@code sounding} plus its
 * {@code sounding_level} children.
 *
 * @param id         database key, or {@code null} before insertion
 * @param stationId  reporting station
 * @param epochUtc   observation time, ISO-8601 UTC
 * @param source     one of WYOMING, IGRA, SYNTHETIC — matches the schema's CHECK constraint
 * @param fileSha256 SHA-256 of the source file, so a re-import is recognisable
 * @param levels     the levels, sorted by geopotential height
 */
public record StoredSounding(Long id, String stationId, String epochUtc, String source,
                             String fileSha256, List<SoundingLevel> levels) {

    /**
     * @param newId the assigned key
     * @return the identified sounding
     */
    public StoredSounding withId(long newId) {
        return new StoredSounding(newId, stationId, epochUtc, source, fileSha256, levels);
    }

    /** @return how many levels this sounding holds */
    public int levelCount() {
        return levels.size();
    }
}
