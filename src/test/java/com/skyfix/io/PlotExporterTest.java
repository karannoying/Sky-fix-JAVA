package com.skyfix.io;

import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.flight.EllipseFitter;
import com.skyfix.core.flight.EnsembleRunner;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.DispersionSpec;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.LiftGas;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-4.2 — the exported charts.
 *
 * <p>A plot test cannot assert that a chart is <em>well designed</em>, so it asserts the things
 * that are checkable and that actually go wrong: the files exist at the documented names, they
 * decode as images of the expected size, they are not blank, and the whole export finishes inside
 * the stated budget. A chart that silently renders an empty panel is the failure mode worth
 * catching, and pixel variety catches it.
 */
class PlotExporterTest {

    private static final Ussa1976Atmosphere ATMOSPHERE = new Ussa1976Atmosphere();
    private static final GeoPoint LAUNCH = new GeoPoint(23.2599, 77.4126, 500.0);

    static BalloonConfig config() throws Exception {
        return BalloonConfig.builder()
                .name("HabSat-1200g").payloadMassKg(1.2).envelopeMassKg(1.2)
                .launchDiameterM(1.8).burstDiameterM(7.0).freeLiftKg(1.1)
                .ascentCd(0.45).chuteAreaM2(1.0).chuteCd(1.4).gas(LiftGas.HELIUM)
                .build();
    }

    private record Fixture(StateHistory nominal, Map<Integer, StateHistory> members,
                           Ensemble ensemble, List<LandingEllipse> ellipses) {
    }

    private static Fixture shared;

    /**
     * One ensemble, computed once and shared.
     *
     * <p>Each test used to build its own, which meant flying about 270 members to render a
     * handful of PNGs — twenty-two seconds for a class whose subject is chart rendering, not
     * simulation. The charts are indifferent to which ensemble they draw.
     */
    private static synchronized Fixture shared() throws Exception {
        if (shared == null) {
            shared = fixture(30);
        }
        return shared;
    }

    private static Fixture fixture(int members) throws Exception {
        BalloonConfig config = config();
        SimSettings settings = SimSettings.builder()
                .groundElevationM(LAUNCH.altitudeM()).stateSampleStride(40).build();
        ConstantWindField wind = new ConstantWindField(12.0, -4.0);

        EnsembleRunner.Result result = new EnsembleRunner(ATMOSPHERE, wind)
                .run(config, DispersionSpec.preflightDefault(config), settings, LAUNCH,
                        members, 42L, 8);
        StateHistory nominal = new FlightSimulator(ATMOSPHERE, wind)
                .run(config, FlightParameters.nominal(config), settings, LAUNCH);

        List<LandingEllipse> ellipses = new ArrayList<>();
        for (double confidence : new double[]{0.50, 0.95}) {
            ellipses.add(EllipseFitter.fit(result.ensemble().landingPoints(), confidence,
                    LAUNCH.altitudeM()));
        }
        return new Fixture(nominal, result.histories(), result.ensemble(), ellipses);
    }

    @Test
    @DisplayName("FR-4.2: the ensemble plots are written at deterministic names and are non-empty")
    void ensemblePlotsAreWritten(@TempDir Path dir) throws Exception {
        Fixture f = shared();
        List<Path> written = PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(),
                f.ensemble(), f.ellipses(), LAUNCH);

        assertThat(written).hasSize(2);
        assertThat(written.get(0).getFileName().toString()).isEqualTo("pl1-altitude.png");
        assertThat(written.get(1).getFileName().toString()).isEqualTo("pl2-footprint.png");

        for (Path plot : written) {
            assertThat(plot).exists();
            assertThat(Files.size(plot)).as("%s must not be an empty file", plot).isGreaterThan(5_000);
        }
    }

    @Test
    @DisplayName("FR-4.2: each plot decodes as an image of the documented size")
    void plotsDecodeAtTheExpectedSize(@TempDir Path dir) throws Exception {
        Fixture f = shared();
        List<Path> written = PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(),
                f.ensemble(), f.ellipses(), LAUNCH);

        BufferedImage pl1 = ImageIO.read(written.get(0).toFile());
        assertThat(pl1.getWidth()).isEqualTo(PlotExporter.WIDTH);
        assertThat(pl1.getHeight()).isEqualTo(PlotExporter.HEIGHT);

        BufferedImage pl2 = ImageIO.read(written.get(1).toFile());
        assertThat(pl2.getWidth()).as("the footprint is square so the axes can share a scale")
                .isEqualTo(PlotExporter.SQUARE);
        assertThat(pl2.getHeight()).isEqualTo(PlotExporter.SQUARE);
    }

    @Test
    @DisplayName("FR-4.2: a plot that rendered nothing would be caught — the panels have content")
    void plotsActuallyContainInk(@TempDir Path dir) throws Exception {
        // The failure this guards against is a chart that writes a valid PNG of an empty panel:
        // right size, right name, no data. Counting distinct colours catches it, because a blank
        // chart is a handful of colours (surface, grid, axis text) while a drawn one is hundreds
        // once anti-aliasing is in play.
        Fixture f = shared();
        List<Path> written = PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(),
                f.ensemble(), f.ellipses(), LAUNCH);

        for (Path plot : written) {
            BufferedImage image = ImageIO.read(plot.toFile());
            java.util.Set<Integer> colours = new java.util.HashSet<>();
            for (int x = 0; x < image.getWidth(); x += 2) {
                for (int y = 0; y < image.getHeight(); y += 2) {
                    colours.add(image.getRGB(x, y));
                }
            }
            assertThat(colours.size()).as("%s looks blank", plot.getFileName())
                    .isGreaterThan(50);
        }
    }

    @Test
    @DisplayName("FR-4.2: the series colours actually reach the canvas")
    void seriesColoursArePresent(@TempDir Path dir) throws Exception {
        // Asserting the palette reached the image, not merely that it was configured: a series
        // drawn in the wrong colour, or hidden behind another, is invisible to every other check
        // here.
        Fixture f = shared();
        List<Path> written = PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(),
                f.ensemble(), f.ellipses(), LAUNCH);

        BufferedImage pl1 = ImageIO.read(written.get(0).toFile());
        assertThat(containsNear(pl1, ChartStyle.SERIES_1.getRGB()))
                .as("PL-1 must draw the nominal flight in the primary series colour").isTrue();

        BufferedImage pl2 = ImageIO.read(written.get(1).toFile());
        assertThat(containsNear(pl2, ChartStyle.SERIES_1.getRGB()))
                .as("PL-2 must draw the inner ellipse in the darker blue step").isTrue();
        assertThat(containsNear(pl2, ChartStyle.BLUE_LIGHT.getRGB()))
                .as("PL-2 must draw the outer ellipse in the lighter blue step").isTrue();
    }

    @Test
    @DisplayName("FR-4.2: PL-6 renders the atmosphere residual from the reference table")
    void residualPlotIsWritten(@TempDir Path dir) throws Exception {
        List<double[]> reference = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("data", "reference", "ussa1976.csv"))) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("altitude_m")) {
                continue;
            }
            String[] fields = t.split(",");
            reference.add(new double[]{Double.parseDouble(fields[0]),
                    Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3])});
        }
        assertThat(reference).hasSize(25);

        Path plot = PlotExporter.exportAtmosphereResidual(dir, ATMOSPHERE, reference, 0.001);
        assertThat(plot.getFileName().toString()).isEqualTo("pl6-atmosphere-residual.png");
        assertThat(Files.size(plot)).isGreaterThan(5_000);

        BufferedImage image = ImageIO.read(plot.toFile());
        assertThat(image.getWidth()).isEqualTo(PlotExporter.WIDTH);
        for (java.awt.Color colour : new java.awt.Color[]{
                ChartStyle.SERIES_1, ChartStyle.SERIES_2, ChartStyle.SERIES_3}) {
            assertThat(containsNear(image, colour.getRGB()))
                    .as("PL-6 must draw all three residual series").isTrue();
        }
    }

    @Test
    @DisplayName("FR-4.2: the plots are written well inside the 5 s budget")
    void plotsAreWrittenInsideTheBudget(@TempDir Path dir) throws Exception {
        // Rendering cost is dominated by the chart, not the member count: the scatter is one
        // series however many points it holds.
        Fixture f = shared();
        long start = System.nanoTime();
        PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(), f.ensemble(),
                f.ellipses(), LAUNCH);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.printf("FR-4.2: plots rendered in %d ms (budget 5,000 ms)%n", elapsedMs);
        assertThat(elapsedMs).isLessThan(5_000L);
    }

    @Test
    @DisplayName("Re-exporting the same run overwrites rather than accumulating files")
    void exportIsIdempotent(@TempDir Path dir) throws Exception {
        Fixture f = shared();
        PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(), f.ensemble(),
                f.ellipses(), LAUNCH);
        PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(), f.ensemble(),
                f.ellipses(), LAUNCH);

        try (var files = Files.list(dir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".png")).count())
                    .as("deterministic filenames mean a re-run replaces, never accumulates")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("An ensemble with no fitted ellipses still produces a footprint chart")
    void footprintSurvivesWithoutEllipses(@TempDir Path dir) throws Exception {
        Fixture f = shared();
        List<Path> written = PlotExporter.exportEnsemblePlots(dir, f.nominal(), f.members(),
                f.ensemble(), List.of(), LAUNCH);
        assertThat(written).hasSize(2);
        assertThat(Files.size(written.get(1))).isGreaterThan(2_000);
    }

    /** Whether any pixel is within a small distance of the given colour. */
    private static boolean containsNear(BufferedImage image, int rgb) {
        int target = rgb & 0xFFFFFF;
        int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int pixel = image.getRGB(x, y) & 0xFFFFFF;
                int dr = ((pixel >> 16) & 0xFF) - tr;
                int dg = ((pixel >> 8) & 0xFF) - tg;
                int db = (pixel & 0xFF) - tb;
                if (dr * dr + dg * dg + db * db < 300) {
                    return true;
                }
            }
        }
        return false;
    }
}
