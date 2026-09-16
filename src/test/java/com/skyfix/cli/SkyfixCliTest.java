package com.skyfix.cli;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-U1 and T-E1 at the command surface — the whole CLI exercised in-process.
 *
 * <p>{@code SkyfixCli.execute} returns an exit code instead of calling {@code System.exit}, so
 * every path here runs without spawning a JVM, including the failure paths that matter most:
 * BLUEPRINT §12 assigns each exception type a distinct exit code, and a script is only usable if
 * those codes are right.
 */
class SkyfixCliTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new SkyfixCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).execute(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path missionFile() throws Exception {
        Path p = tempDir.resolve("mission.json");
        Files.writeString(p, """
                {
                  "name": "CliTest-1",
                  "launch_lat": 23.2599,
                  "launch_lon": 77.4126,
                  "launch_alt_m": 500.0,
                  "ground_elev_m": 500.0,
                  "launch_epoch_utc": "2026-09-14T04:30:00Z",
                  "sim": { "step_s": 0.5, "integrator": "RK4", "state_sample_stride": 40 }
                }
                """);
        return p;
    }

    private Path balloonFile() throws Exception {
        return balloonFile("""
                {
                  "name": "CliTest-1200g",
                  "payload_mass_kg": 1.2,
                  "envelope_mass_kg": 1.2,
                  "launch_diameter_m": 1.8,
                  "burst_diameter_m": 7.0,
                  "free_lift_kg": 1.1,
                  "ascent_cd": 0.45,
                  "chute_area_m2": 1.0,
                  "chute_cd": 1.4,
                  "gas": "HELIUM"
                }
                """);
    }

    private Path balloonFile(String json) throws Exception {
        Path p = tempDir.resolve("balloon.json");
        Files.writeString(p, json);
        return p;
    }

    @Test
    @DisplayName("T-U1: predict runs end to end and writes the documented exports")
    void predictProducesExports() throws Exception {
        int code = run("predict",
                "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--db", tempDir.resolve("cli.db").toString(),
                "--out", tempDir.resolve("out").toString());

        assertThat(code).as("stderr was: %s", stderr()).isZero();
        assertThat(stdout())
                .contains("pre-flight prediction")
                .contains("burst")
                .contains("landing")
                .contains("seed 42");

        Path runDir = tempDir.resolve("out").resolve("run-1");
        assertThat(runDir.resolve("trajectory.csv")).exists();
        assertThat(runDir.resolve("summary.csv")).exists();
        assertThat(runDir.resolve("flight.geojson")).exists();

        // The exports must actually hold a flight, not just exist.
        assertThat(Files.readAllLines(runDir.resolve("trajectory.csv")))
                .hasSizeGreaterThan(100)
                .first().asString().startsWith("t_s,lat_deg,lon_deg");
        assertThat(Files.readString(runDir.resolve("flight.geojson")))
                .contains("\"LineString\"").contains("\"landing\"").contains("\"burst\"");
    }

    @Test
    @DisplayName("T-U1: the same seed and inputs reproduce an identical trajectory export")
    void sameInputsReproduceIdenticalExports() throws Exception {
        Path mission = missionFile();
        Path balloon = balloonFile();
        for (String name : new String[]{"a", "b"}) {
            assertThat(run("predict", "--mission", mission.toString(),
                    "--balloon", balloon.toString(),
                    "--db", tempDir.resolve(name + ".db").toString(),
                    "--out", tempDir.resolve(name).toString())).isZero();
        }
        // Byte-for-byte: this is the property T-R1 will extend across two JVM runs.
        assertThat(Files.readString(tempDir.resolve("a/run-1/trajectory.csv")))
                .isEqualTo(Files.readString(tempDir.resolve("b/run-1/trajectory.csv")));
        assertThat(Files.readString(tempDir.resolve("a/run-1/summary.csv")))
                .isEqualTo(Files.readString(tempDir.resolve("b/run-1/summary.csv")));
    }

    @Test
    @DisplayName("FR-4.4: validate runs the reference suite and exits 0 when everything passes")
    void validateExitsZeroWhenAllCasesPass() {
        assertThat(run("validate")).isZero();
        assertThat(stdout())
                .contains("SKYFIX reference-case validation")
                .contains("T-V1").contains("T-V2").contains("T-V3").contains("T-V4")
                .contains("PASS")
                .doesNotContain("FAIL");
        // The suite must admit what it has not implemented rather than quietly passing.
        assertThat(stdout()).contains("Not yet implemented")
                .contains("T-V5").contains("T-V6").contains("T-V7");
    }

    @Test
    @DisplayName("FR-4.4: validate persists its table when given a database")
    void validatePersistsResults() throws Exception {
        Path db = tempDir.resolve("validate.db");
        assertThat(run("validate", "--db", db.toString())).isZero();
        assertThat(db).exists();
        assertThat(stdout()).contains("validation run 1 stored");

        try (var database = com.skyfix.persistence.Database.openFile(db)) {
            var dao = new com.skyfix.persistence.ValidationDao(database);
            assertThat(dao.findForRun(1L)).hasSizeGreaterThan(80);
            assertThat(dao.countFailed(1L)).isZero();
        }
    }

    @Test
    @DisplayName("T-E1: a config that breaks a rule exits 2 and names the field, with no stack trace")
    void invalidConfigExitsTwoAndNamesTheField() throws Exception {
        Path balloon = balloonFile("""
                {
                  "name": "impossible",
                  "payload_mass_kg": 1.2,
                  "envelope_mass_kg": 1.2,
                  "launch_diameter_m": 1.6,
                  "burst_diameter_m": 1.2,
                  "free_lift_kg": 1.1,
                  "ascent_cd": 0.45,
                  "chute_area_m2": 1.0,
                  "chute_cd": 1.4,
                  "gas": "HELIUM"
                }
                """);
        int code = run("predict", "--mission", missionFile().toString(),
                "--balloon", balloon.toString(),
                "--db", tempDir.resolve("cli.db").toString());

        assertThat(code).as("ValidationException maps to exit 2").isEqualTo(2);
        assertThat(stderr())
                .contains("burst_diameter_m")
                .contains("launch_diameter_m")
                .doesNotContain("\tat ")   // NFR-3: never a stack trace
                .doesNotContain("Exception in thread");
    }

    @Test
    @DisplayName("T-E1: a missing required field is reported by name, not defaulted")
    void missingFieldIsNamed() throws Exception {
        Path balloon = balloonFile("""
                { "name": "incomplete", "payload_mass_kg": 1.2, "gas": "HELIUM" }
                """);
        assertThat(run("predict", "--mission", missionFile().toString(),
                "--balloon", balloon.toString(),
                "--db", tempDir.resolve("cli.db").toString())).isEqualTo(2);
        assertThat(stderr()).contains("envelope_mass_kg").contains("required");
    }

    @Test
    @DisplayName("T-E3: malformed JSON exits 3 and names the file and line")
    void malformedJsonExitsThree() throws Exception {
        Path balloon = balloonFile("{ \"name\": \"broken\", \n \"payload_mass_kg\": }");
        assertThat(run("predict", "--mission", missionFile().toString(),
                "--balloon", balloon.toString(),
                "--db", tempDir.resolve("cli.db").toString())).isEqualTo(3);
        assertThat(stderr()).contains("balloon.json").contains("not valid JSON");
    }

    @Test
    @DisplayName("ADR-13: an unstable step exits 5 and names the largest step that would work")
    void unstableStepExitsFive() throws Exception {
        int code = run("predict", "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--step", "1.0",
                "--db", tempDir.resolve("cli.db").toString(),
                "--out", tempDir.resolve("out").toString());

        assertThat(code).as("ConvergenceException maps to exit 5").isEqualTo(5);
        assertThat(stderr()).contains("stability limit").contains("max_stable_step_s");
    }

    @Test
    @DisplayName("A bad option value exits 2 rather than crashing")
    void unknownIntegratorExitsTwo() throws Exception {
        assertThat(run("predict", "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--integrator", "euler",
                "--db", tempDir.resolve("cli.db").toString())).isEqualTo(2);
        assertThat(stderr()).contains("integrator").contains("RK4");
    }

    @Test
    @DisplayName("Usage: no arguments exits 1 with help; help exits 0")
    void usageAndHelp() {
        assertThat(run()).isEqualTo(SkyfixCli.EXIT_USAGE);
        assertThat(stdout()).contains("usage:").contains("ingest").contains("predict");

        out.reset();
        assertThat(run("help")).isZero();
        assertThat(stdout()).contains("exit codes:");
    }

    @Test
    @DisplayName("An unknown command exits 1 and says so")
    void unknownCommandExitsOne() {
        assertThat(run("fly-to-mars")).isEqualTo(SkyfixCli.EXIT_USAGE);
        assertThat(stderr()).contains("unknown command").contains("fly-to-mars");
    }

    @Test
    @DisplayName("ingest with nothing to ingest is a usage-level validation error")
    void ingestWithNothingToDo() {
        assertThat(run("ingest", "--db", tempDir.resolve("cli.db").toString())).isEqualTo(2);
        assertThat(stderr()).contains("nothing to ingest");
    }

    @Test
    @DisplayName("ingest imports a sounding, reports rejections, and is idempotent")
    void ingestSoundingIsIdempotent() {
        String db = tempDir.resolve("cli.db").toString();
        String sounding = Path.of("data", "soundings", "SYNTHETIC_2026-09-14_00Z.txt").toString();

        assertThat(run("ingest", "--sounding", sounding,
                "--epoch", "2026-09-14T00:00:00Z", "--db", db)).isZero();
        assertThat(stdout()).contains("levels").contains("sounding id: 1");

        out.reset();
        assertThat(run("ingest", "--sounding", sounding,
                "--epoch", "2026-09-14T00:00:00Z", "--db", db)).isZero();
        assertThat(stdout()).contains("already imported");
    }

    @Test
    @DisplayName("predict against an imported sounding drifts downwind of a windless run")
    void predictWithSoundingDrifts() throws Exception {
        String db = tempDir.resolve("cli.db").toString();
        assertThat(run("ingest", "--sounding",
                Path.of("data", "soundings", "SYNTHETIC_2026-09-14_00Z.txt").toString(),
                "--epoch", "2026-09-14T00:00:00Z", "--db", db)).isZero();

        out.reset();
        assertThat(run("predict", "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--sounding-id", "1",
                "--db", db, "--out", tempDir.resolve("windy").toString())).isZero();
        assertThat(stdout()).contains("wind: SOUNDING");

        String summary = Files.readString(tempDir.resolve("windy/run-1/summary.csv"));
        String[] fields = summary.lines().skip(1).findFirst().orElseThrow().split(",");
        double landingLon = Double.parseDouble(fields[4]);
        assertThat(landingLon)
                .as("a westerly jet must carry the payload east of the launch site")
                .isGreaterThan(77.4126 + 0.1);
    }

    @Test
    @DisplayName("An unknown sounding id is rejected by name rather than ignored")
    void unknownSoundingIdIsRejected() throws Exception {
        assertThat(run("predict", "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--sounding-id", "999",
                "--db", tempDir.resolve("cli.db").toString())).isEqualTo(2);
        assertThat(stderr()).contains("sounding_id");
    }

    @Test
    @DisplayName("version reports the schema version and the git SHA")
    void versionCommand() {
        assertThat(run("version")).isZero();
        assertThat(stdout()).contains("SKYFIX").contains("schema version 1").contains("git ");
    }

    @Test
    @DisplayName("Options parse as --key value and --key=value; stray positionals are rejected")
    void optionParsing() throws Exception {
        Map<String, String> parsed = SkyfixCli.parseOptions(
                new String[]{"predict", "--mission", "m.json", "--seed=7", "--verbose"}, 1);
        assertThat(parsed)
                .containsEntry("mission", "m.json")
                .containsEntry("seed", "7")
                .containsEntry("verbose", "true");

        assertThatThrownBy(() -> SkyfixCli.parseOptions(
                new String[]{"predict", "mission.json"}, 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mission.json");
    }

    @Test
    @DisplayName("replay runs end to end, prints bands, and writes PL-3")
    void replayCommandRunsEndToEnd() throws Exception {
        // A short synthetic log, so the CLI surface is exercised without a two-hour flight.
        Path log = tempDir.resolve("cli-flight.csv");
        new com.skyfix.io.SyntheticFlightWriter(
                new com.skyfix.core.atmos.Ussa1976Atmosphere(),
                new com.skyfix.core.atmos.ConstantWindField(12.0, -4.0))
                .write(log,
                        new com.skyfix.io.ConfigLoader().loadBalloon(balloonFile()),
                        com.skyfix.domain.FlightParameters.nominal(
                                new com.skyfix.io.ConfigLoader().loadBalloon(balloonFile())),
                        com.skyfix.domain.SimSettings.builder().groundElevationM(500.0).build(),
                        new com.skyfix.domain.GeoPoint(23.2599, 77.4126, 500.0),
                        java.time.Instant.parse("2026-09-14T04:30:00Z"),
                        com.skyfix.domain.NoiseSpec.standard(), 4242L);

        Path db = tempDir.resolve("replay.db");
        Path outDir = tempDir.resolve("replay-out");
        int code = run("replay",
                "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--log", log.toString(),
                "--every", "400",
                "--filters", "2",
                "--particles", "20",
                "--members", "8",
                "--db", db.toString(),
                "--out", outDir.toString());

        assertThat(code).as("stderr was: %s", stderr()).isZero();
        assertThat(stdout())
                .contains("replay estimate")
                .contains("free lift")
                .contains("parachute Cd")
                .contains("re-predict");
        assertThat(stderr()).doesNotContain("Exception");

        // PL-3 is one panel per estimated parameter (FR-4.2).
        try (var files = Files.walk(outDir)) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith("pl3-")).count())
                    .isEqualTo(4);
        }
    }

    @Test
    @DisplayName("replay without a log names the missing option rather than failing obscurely")
    void replayWithoutALogIsRejected() throws Exception {
        int code = run("replay",
                "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--db", tempDir.resolve("x.db").toString());
        assertThat(code).isNotZero();
        assertThat(stderr()).contains("log");
        assertThat(stderr()).doesNotContain("Exception in thread");
    }

    @Test
    @DisplayName("a particle budget that cannot be split across the filters is refused by name")
    void replayRefusesAnUnsplittableBudget() throws Exception {
        Path log = tempDir.resolve("tiny.csv");
        Files.writeString(log, """
                epoch_utc,packet_id,lat,lon,alt_gps_m,pressure_pa,temperature_k
                2026-09-14T04:30:00Z,1,23.2599,77.4126,500.0,95500.0,288.0
                2026-09-14T04:30:01Z,2,23.2599,77.4126,505.0,95440.0,288.0
                """);
        int code = run("replay",
                "--mission", missionFile().toString(),
                "--balloon", balloonFile().toString(),
                "--log", log.toString(),
                "--filters", "16",
                "--particles", "8",
                "--db", tempDir.resolve("y.db").toString());
        assertThat(code).isNotZero();
        assertThat(stderr()).contains("particles");
        assertThat(stderr()).doesNotContain("Exception in thread");
    }
}
