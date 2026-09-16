package com.skyfix.core.flight;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.ConfigLoader;
import com.skyfix.io.PlotExporter;
import com.skyfix.io.WyomingSoundingReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-V7: is the ellipse fitter calibrated against a <em>real</em> ensemble? (FR-2.4)
 *
 * <p>T-U-ELLIPSE already showed the fitter is calibrated against a synthetic Gaussian cloud:
 * 49.9/90.0/95.1% contained at 50/90/95% nominal. That test assumes exactly what the fitter
 * assumes. This one does not — a real landing cloud is the output of a nonlinear flight model over
 * a dispersed parameter set advected through a sheared wind, and there is no reason for it to be
 * bivariate normal. Burst altitude alone enters the landing position through a long nonlinear
 * chain.
 *
 * <p>The headline number: the 95% ellipse contains 94.6% of the cloud it was fitted to and 94.0%
 * of an independent one, so the fitter is calibrated where it matters. The 50% ellipse over-covers
 * at about 56%, for a reason the measurement itself reveals and which is worth the report's space.
 *
 * <p>Two measurements, because they answer different questions. <strong>In-sample</strong> fits the
 * ellipse to a cloud and counts that cloud — it asks whether the chi-square scaling suits the
 * cloud's actual shape. <strong>Out-of-sample</strong> fits on one ensemble and counts an
 * independent one — it asks whether a footprint published today would contain tomorrow's landing,
 * which is the question a recovery team is really asking.
 *
 * <p>Tagged {@code perf}: two thousand flights.
 */
@Tag("perf")
class EllipseCalibrationTest {

    /** T-V7's acceptance window for the 95% ellipse. */
    private static final double LOW = 0.90;
    private static final double HIGH = 0.98;

    /** Members per ensemble. Enough that a 95% containment is resolved to about half a percent. */
    private static final int MEMBERS = 1_000;

    private static final double[] LEVELS = {0.50, 0.90, 0.95};

    @Test
    @DisplayName("T-V7: the 95% ellipse contains 90-98% of a real ensemble, in and out of sample")
    void ellipsesAreCalibratedAgainstRealEnsembles(@TempDir Path dir) throws Exception {
        ConfigLoader loader = new ConfigLoader();
        BalloonConfig config = loader.loadBalloon(Path.of("data/missions/balloon.json"));
        ConfigLoader.MissionSpec mission = loader.loadMission(Path.of("data/missions/mission.json"));
        SimSettings settings = loader.loadSimSettings(
                loader.read(Path.of("data/missions/mission.json")), mission.groundElevationM());

        // A sounding-interpolated field rather than a constant one, on the expectation that shear
        // would turn the cloud through the vertical and make a rounder, harder shape.
        //
        // MEASURED: it does not. The fitted 95% ellipse comes out 76.4 x 0.5 km -- an aspect ratio
        // near 150 -- because this sounding's wind direction barely turns with altitude, so almost
        // all the landing uncertainty is along-wind: it is uncertainty about how long the flight
        // lasts, not about where it goes. The footprint is effectively one-dimensional, and that is
        // a property of the physics rather than of the fitter. It is also why the 50% level
        // over-covers below: a chi-square scaling for two degrees of freedom is generous when the
        // cloud has closer to one.
        var parsed = new WyomingSoundingReader()
                .read(Path.of("data/soundings/SYNTHETIC_2026-09-14_00Z.txt"));
        WindField wind = SoundingWindField.of(parsed.stationId(), parsed.levels());

        EnsembleRunner runner = new EnsembleRunner(new Ussa1976Atmosphere(), wind);
        DispersionSpec spec = DispersionSpec.preflightDefault(config);

        Ensemble first = runner.run(config, spec, settings, mission.launch(), MEMBERS, 20260914L, 0)
                .ensemble();
        Ensemble second = runner.run(config, spec, settings, mission.launch(), MEMBERS, 77_777L, 0)
                .ensemble();

        double[] inSample = new double[LEVELS.length];
        double[] outOfSample = new double[LEVELS.length];

        System.out.printf(Locale.ROOT, "%n%-10s %14s %16s %14s %9s%n",
                "nominal", "in-sample", "out-of-sample", "axes (km)", "aspect");
        System.out.println("-".repeat(70));
        for (int i = 0; i < LEVELS.length; i++) {
            LandingEllipse ellipse = EllipseFitter.fit(first.landingPoints(), LEVELS[i],
                    mission.groundElevationM());
            inSample[i] = containedFraction(ellipse, first.landingPoints());
            outOfSample[i] = containedFraction(ellipse, second.landingPoints());
            System.out.printf(Locale.ROOT, "%9.0f%% %13.1f%% %15.1f%% %8.1f x %.1f %8.0f%n",
                    LEVELS[i] * 100, inSample[i] * 100, outOfSample[i] * 100,
                    ellipse.semiMajorM() / 1000.0, ellipse.semiMinorM() / 1000.0,
                    ellipse.aspectRatio());
        }

        Path plot = PlotExporter.exportEllipseCalibration(dir, LEVELS, outOfSample);
        assertThat(plot).exists();

        int at95 = LEVELS.length - 1;
        assertThat(inSample[at95])
                .as("in-sample containment of the 95%% ellipse over %d real landings", MEMBERS)
                .isBetween(LOW, HIGH);
        assertThat(outOfSample[at95])
                .as("out-of-sample containment of the 95%% ellipse against an independent ensemble")
                .isBetween(LOW, HIGH);

        // The lower levels are not gated by T-V7, but a fitter calibrated at 95% and badly wrong
        // at 50% would be right by accident rather than by construction. The 50% level is measured
        // at about 56%, over-covering for the reason given above -- the cloud is closer to
        // one-dimensional than two, so a two-degree-of-freedom scaling is generous there. It
        // converges towards nominal as the confidence rises, which is the shape that argument
        // predicts.
        assertThat(outOfSample[0])
                .as("out-of-sample containment of the 50%% ellipse")
                .isBetween(0.45, 0.62);
        assertThat(Math.abs(outOfSample[at95] - 0.95))
                .as("the fitter is closer to nominal at 95%% than at 50%%")
                .isLessThan(Math.abs(outOfSample[0] - 0.50));
    }

    @Test
    @DisplayName("a constant-wind ensemble is a harder shape, and the fitter says so honestly")
    void constantWindProducesASmearTheFitterStillCovers() throws SkyfixException {
        ConfigLoader loader = new ConfigLoader();
        BalloonConfig config = loader.loadBalloon(Path.of("data/missions/balloon.json"));
        ConfigLoader.MissionSpec mission = loader.loadMission(Path.of("data/missions/mission.json"));
        SimSettings settings = loader.loadSimSettings(
                loader.read(Path.of("data/missions/mission.json")), mission.groundElevationM());

        EnsembleRunner runner = new EnsembleRunner(new Ussa1976Atmosphere(),
                new ConstantWindField(12.0, -4.0));
        Ensemble ensemble = runner.run(config, DispersionSpec.preflightDefault(config), settings,
                mission.launch(), 400, 20260914L, 0).ensemble();

        LandingEllipse ellipse = EllipseFitter.fit(ensemble.landingPoints(), 0.95,
                mission.groundElevationM());
        double contained = containedFraction(ellipse, ensemble.landingPoints());
        System.out.printf(Locale.ROOT,
                "%nconstant wind: 95%% ellipse contains %.1f%%, aspect ratio %.1f%n",
                contained * 100, ellipse.aspectRatio());

        // Under a wind that barely turns with altitude the cloud collapses onto a line, which is
        // the case a bivariate normal handles least well. The containment must still hold.
        assertThat(contained).isBetween(LOW, HIGH);
        assertThat(ellipse.aspectRatio())
                .as("a constant wind makes a strongly elongated footprint")
                .isGreaterThan(2.0);
    }

    private static double containedFraction(LandingEllipse ellipse, List<GeoPoint> points) {
        long inside = points.stream().filter(ellipse::contains).count();
        return (double) inside / points.size();
    }
}
