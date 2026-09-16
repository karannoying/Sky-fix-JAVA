package com.skyfix.persistence;

import com.skyfix.domain.BalloonConfig;

/**
 * A balloon configuration as stored, paired with the mission it belongs to.
 * Row shape of {@code balloon_config}.
 *
 * @param id        database key, or {@code null} before insertion
 * @param missionId owning mission
 * @param config    the validated configuration; its {@code configHash} is the unique column
 */
public record StoredBalloonConfig(Long id, long missionId, BalloonConfig config) {

    /**
     * Returns a copy carrying a database key.
     *
     * @param newId the assigned key
     * @return the identified row
     */
    public StoredBalloonConfig withId(long newId) {
        return new StoredBalloonConfig(newId, missionId, config);
    }
}
