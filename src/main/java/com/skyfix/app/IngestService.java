package com.skyfix.app;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.TelemetrySeries;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;
import com.skyfix.io.ConfigLoader;
import com.skyfix.io.CsvTelemetryReader;
import com.skyfix.io.FileDigest;
import com.skyfix.io.SoundingReader;
import com.skyfix.io.WyomingSoundingReader;
import com.skyfix.persistence.BalloonConfigDao;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.FlightLog;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.MissionDao;
import com.skyfix.persistence.SoundingDao;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.StoredSounding;
import com.skyfix.persistence.TelemetryDao;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Imports missions, balloon configurations and soundings (FR-1.1, FR-1.3).
 *
 * <p>Every import is idempotent: a mission is keyed by name, a configuration by its hash
 * (ADR-10), a sounding by (station, epoch, source). Re-running {@code ingest} on unchanged inputs
 * therefore changes nothing rather than accumulating near-duplicates.
 */
public final class IngestService {

    private static final Logger LOG = Logger.getLogger(IngestService.class.getName());

    private final MissionDao missions;
    private final BalloonConfigDao configs;
    private final SoundingDao soundings;
    private final TelemetryDao telemetry;
    private final ConfigLoader loader = new ConfigLoader();

    /**
     * @param database the database to write into
     */
    public IngestService(Database database) {
        this.missions = new MissionDao(database);
        this.configs = new BalloonConfigDao(database);
        this.soundings = new SoundingDao(database);
        this.telemetry = new TelemetryDao(database);
    }

    /**
     * Imports a mission and its balloon configuration.
     *
     * @param missionPath the {@code mission.json} file
     * @param balloonPath the {@code balloon.json} file
     * @return what was stored, and whether each part was new
     * @throws SkyfixException if either file is unparseable or breaks a rule
     */
    public IngestResult ingestMission(Path missionPath, Path balloonPath) throws SkyfixException {
        ConfigLoader.MissionSpec spec = loader.loadMission(missionPath);
        BalloonConfig balloon = loader.loadBalloon(balloonPath);

        Optional<Mission> existing = missions.findByName(spec.name());
        boolean missionIsNew = existing.isEmpty();
        Mission mission = existing.orElseGet(() -> {
            try {
                return missions.save(new Mission(null, spec.name(), spec.launch(),
                        spec.groundElevationM(), spec.launchEpochUtc()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        boolean configIsNew = configs.findByHash(balloon.configHash()).isEmpty();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), balloon);

        LOG.info(() -> "ingested mission \"" + spec.name() + "\" (new=" + missionIsNew
                + ") with config " + balloon.configHash().substring(0, 12)
                + " (new=" + configIsNew + ")");
        return new IngestResult(mission, stored, missionIsNew, configIsNew);
    }

    /**
     * Imports a sounding file (FR-1.1).
     *
     * @param path     the sounding file
     * @param epochUtc the observation time to file it under, ISO-8601 UTC
     * @return the stored sounding and the parse report
     * @throws SkyfixException if the file cannot be parsed or stored
     */
    public SoundingIngestResult ingestSounding(Path path, String epochUtc) throws SkyfixException {
        SoundingReader reader = new WyomingSoundingReader();
        SoundingReader.SoundingParseResult parsed = reader.read(path);

        Optional<StoredSounding> existing = soundings.findByNaturalKey(
                parsed.stationId(), epochUtc, reader.sourceName());
        if (existing.isPresent()) {
            LOG.info(() -> "sounding " + parsed.stationId() + " @ " + epochUtc
                    + " is already imported; skipping");
            return new SoundingIngestResult(existing.get(), parsed.rejections(), false);
        }

        StoredSounding stored = soundings.save(new StoredSounding(null, parsed.stationId(),
                epochUtc, reader.sourceName(), parsed.fileSha256(), parsed.levels()));
        LOG.info(() -> "ingested " + stored.levelCount() + " levels from " + path.getFileName()
                + " (" + parsed.rejectedCount() + " lines rejected)");
        return new SoundingIngestResult(stored, parsed.rejections(), true);
    }

    /**
     * Ingests a telemetry log so a replay can reference it (FR-1.2).
     *
     * <p>Idempotent on the file's own bytes: a log whose SHA-256 is already stored for this mission
     * is returned as it stands rather than duplicated. Replaying the same log twice is a normal
     * thing to do — with a different seed, or after a model change — and each replay should be a
     * new run against the same stored telemetry, not a new copy of the telemetry.
     *
     * @param mission    the mission the log belongs to
     * @param path       the CSV to read
     * @param sourceKind {@link FlightLog#RECORDED} or {@link FlightLog#SYNTHETIC}
     * @return the stored log, the parsed series, and whether this call created the row
     * @throws SkyfixException if the file cannot be read or stored
     */
    public TelemetryIngestResult ingestTelemetry(Mission mission, Path path, String sourceKind)
            throws SkyfixException {
        TelemetrySeries series = new CsvTelemetryReader().read(path);
        String sha = FileDigest.sha256(path);
        String name = path.getFileName().toString();

        Optional<FlightLog> existing = telemetry.findByName(mission.id(), name);
        if (existing.isPresent() && sha.equals(existing.get().fileSha256())) {
            LOG.info(() -> "flight log " + name + " is already ingested; reusing it");
            return new TelemetryIngestResult(existing.get(), series, false);
        }
        if (existing.isPresent()) {
            throw ValidationException.field("log", name,
                    "is already stored for this mission with different contents; rename the file "
                            + "or remove the stored log rather than silently replacing it");
        }

        FlightLog stored = telemetry.save(new FlightLog(null, mission.id(), name, sourceKind, sha,
                series.size(), null), series);
        LOG.info(() -> "ingested " + series.size() + " telemetry samples from " + name);
        return new TelemetryIngestResult(stored, series, true);
    }

    /**
     * What an {@code ingest} of a telemetry log stored.
     *
     * @param log    the flight log row
     * @param series the parsed samples
     * @param isNew  whether this call created the row
     */
    public record TelemetryIngestResult(FlightLog log, TelemetrySeries series, boolean isNew) {
    }

    /**
     * Loads the levels of a stored sounding, for building a wind field.
     *
     * @param soundingId the sounding
     * @return its levels, or an empty list if it is not stored
     * @throws SkyfixException if the query fails
     */
    public List<SoundingLevel> levelsOf(long soundingId) throws SkyfixException {
        return soundings.findById(soundingId).map(StoredSounding::levels).orElse(List.of());
    }

    /**
     * What an {@code ingest} of a mission stored.
     *
     * @param mission      the mission row
     * @param config       the balloon configuration row
     * @param missionIsNew whether the mission was created by this call
     * @param configIsNew  whether the configuration was created by this call
     */
    public record IngestResult(Mission mission, StoredBalloonConfig config, boolean missionIsNew,
                               boolean configIsNew) {
    }

    /**
     * What an {@code ingest} of a sounding stored.
     *
     * @param sounding   the sounding row, with its levels
     * @param rejections one message per rejected line, each naming {@code file:line:reason}
     * @param isNew      whether this call imported it, as opposed to finding it already present
     */
    public record SoundingIngestResult(StoredSounding sounding, List<String> rejections,
                                       boolean isNew) {
    }
}
