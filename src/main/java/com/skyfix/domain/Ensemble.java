package com.skyfix.domain;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of a Monte Carlo ensemble: every member's dispersed parameters and where it landed
 * (FR-2.4). Row shape of {@code ensemble_member}.
 *
 * <p>Members that failed to fly are kept as entries with no landing point rather than being
 * dropped, so the count of failures is visible: BLUEPRINT §12 tolerates a single bad member but
 * wants an error raised if more than 1% fail, and a silently shorter list would hide that.
 */
public record Ensemble(List<Member> members, long seed, long wallClockMs) {

    /**
     * @param members the members, in member-index order
     * @param seed the run seed the design was drawn from
     * @param wallClockMs how long the ensemble took
     */
    public Ensemble {
        members = List.copyOf(members);
    }

    /**
     * One ensemble member.
     *
     * @param index       the member index, stable for a given seed and member count
     * @param parameters  the dispersed parameters this member flew
     * @param landing     where it landed, or empty if the member failed
     * @param burstAltM   the altitude it burst at, or NaN if it failed before burst
     * @param failure     why it failed, or empty if it succeeded
     */
    public record Member(int index, FlightParameters parameters, Optional<GeoPoint> landing,
                         double burstAltM, Optional<String> failure) {

        /**
         * A member that flew.
         *
         * @param index      the member index
         * @param parameters the parameters it flew
         * @param landing    where it landed
         * @param burstAltM  the altitude it burst at
         * @return the member
         */
        public static Member landed(int index, FlightParameters parameters, GeoPoint landing,
                                    double burstAltM) {
            return new Member(index, parameters, Optional.of(landing), burstAltM,
                    Optional.empty());
        }

        /**
         * A member that failed.
         *
         * @param index      the member index
         * @param parameters the parameters it was given
         * @param reason     why it failed, for the run log
         * @return the member
         */
        public static Member failed(int index, FlightParameters parameters, String reason) {
            return new Member(index, parameters, Optional.empty(), Double.NaN,
                    Optional.of(reason));
        }

        /** @return whether this member produced a landing point */
        public boolean succeeded() {
            return landing.isPresent();
        }
    }

    /** @return the landing points of the members that flew, in member-index order */
    public List<GeoPoint> landingPoints() {
        return members.stream().filter(Member::succeeded)
                .map(m -> m.landing().orElseThrow()).toList();
    }

    /** @return how many members produced a landing point */
    public int successCount() {
        return (int) members.stream().filter(Member::succeeded).count();
    }

    /** @return how many members failed */
    public int failureCount() {
        return members.size() - successCount();
    }

    /** @return the fraction of members that failed, between 0 and 1 */
    public double failureRate() {
        return members.isEmpty() ? 0.0 : (double) failureCount() / members.size();
    }

    /**
     * The mean burst altitude across the members that flew.
     *
     * @return the mean in metres, or NaN if no member burst
     */
    public double meanBurstAltitudeM() {
        return members.stream().filter(Member::succeeded)
                .mapToDouble(Member::burstAltM).filter(a -> !Double.isNaN(a))
                .average().orElse(Double.NaN);
    }
}
