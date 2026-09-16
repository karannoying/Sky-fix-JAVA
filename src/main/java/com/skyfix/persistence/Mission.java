package com.skyfix.persistence;

import com.skyfix.domain.GeoPoint;

/**
 * A stored mission — where and when a flight is released from. Row shape of {@code mission}.
 *
 * @param id             database key, or {@code null} before the row is inserted
 * @param name           unique mission name
 * @param launch         launch position; altitude is geometric above MSL, metres
 * @param groundElevationM ground elevation at the landing area, metres above MSL
 * @param launchEpochUtc launch time, ISO-8601 UTC
 */
public record Mission(Long id, String name, GeoPoint launch, double groundElevationM,
                      String launchEpochUtc) {

    /**
     * Returns a copy carrying a database key.
     *
     * @param newId the assigned key
     * @return the identified mission
     */
    public Mission withId(long newId) {
        return new Mission(newId, name, launch, groundElevationM, launchEpochUtc);
    }
}
