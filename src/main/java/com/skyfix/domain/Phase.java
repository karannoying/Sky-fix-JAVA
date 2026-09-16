package com.skyfix.domain;

/**
 * The stage of flight a state record belongs to. Mirrors the {@code phase} CHECK constraint on
 * {@code run_state} in V1__init.sql.
 */
public enum Phase {
    /** Under buoyancy, envelope expanding as ambient pressure falls. */
    ASCENT,
    /** The single step at which the envelope reached its burst diameter. */
    BURST,
    /** Under parachute, after burst. */
    DESCENT,
    /** Ground contact reached; the integration has stopped. */
    LANDED
}
