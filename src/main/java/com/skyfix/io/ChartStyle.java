package com.skyfix.io;

import org.knowm.xchart.style.AxesChartStyler;
import org.knowm.xchart.style.Styler;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;

/**
 * The palette and chrome every SKYFIX chart shares (FR-4.2).
 *
 * <p>One place for the colours so the plots read as one set rather than as four charts that
 * happen to sit in the same directory. The values are a validated categorical palette: the three
 * series hues clear the colour-vision-deficiency separation and normal-vision floors on this
 * surface with all pairs in play, and the two-step blue ramp used for nested confidence ellipses
 * is monotone in lightness with its light end clear of the surface.
 *
 * <p>Chrome is deliberately recessive — hairline solid gridlines, no plot border, muted axis ink —
 * so the data is the loudest thing in the frame.
 *
 * <p><strong>Light only, by design.</strong> These are PNGs bound for a printed report (ADR-8), so
 * there is one target surface rather than a light and a dark variant. Every colour below was
 * validated against that surface.
 */
final class ChartStyle {

    /** Chart surface the palette was validated against. */
    static final Color SURFACE = new Color(0xFC, 0xFC, 0xFB);
    /** Primary ink, for titles. */
    static final Color INK = new Color(0x0B, 0x0B, 0x0B);
    /** Secondary ink, for axis titles. */
    static final Color INK_SECONDARY = new Color(0x52, 0x51, 0x4E);
    /** Muted ink, for tick labels and context marks. */
    static final Color MUTED = new Color(0x89, 0x87, 0x81);
    /** Hairline gridline. */
    static final Color GRID = new Color(0xE1, 0xE0, 0xD9);
    /** Axis rule. */
    static final Color AXIS = new Color(0xC3, 0xC2, 0xB7);

    /** Categorical slot 1 — blue. */
    static final Color SERIES_1 = new Color(0x2A, 0x78, 0xD6);
    /** Categorical slot 2 — orange. */
    static final Color SERIES_2 = new Color(0xEB, 0x68, 0x34);
    /** Categorical slot 3 — aqua. */
    static final Color SERIES_3 = new Color(0x1B, 0xAF, 0x7A);

    /**
     * The lighter step of the blue ramp, for the outer of two nested ellipses and for ensemble
     * context. Step 250: the lightest step that still clears the surface at 2:1.
     */
    static final Color BLUE_LIGHT = new Color(0x86, 0xB6, 0xEF);

    /** Line width for a primary data series, in pixels. */
    static final float PRIMARY_LINE = 2.0f;
    /** Line width for context or threshold marks, in pixels. */
    static final float HAIRLINE = 1.0f;

    private static final String FONT_FAMILY = Font.SANS_SERIF;

    private ChartStyle() {
    }

    /**
     * Applies the shared chrome to a chart's styler.
     *
     * @param styler the styler to configure
     */
    static void apply(AxesChartStyler styler) {
        styler.setChartBackgroundColor(SURFACE);
        styler.setPlotBackgroundColor(SURFACE);
        styler.setLegendBackgroundColor(SURFACE);
        styler.setChartFontColor(INK);
        styler.setPlotBorderVisible(false);
        styler.setChartTitleBoxVisible(false);
        styler.setAntiAlias(true);

        styler.setChartTitleFont(new Font(FONT_FAMILY, Font.BOLD, 16));
        styler.setAxisTitleFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        styler.setAxisTickLabelsFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        styler.setLegendFont(new Font(FONT_FAMILY, Font.PLAIN, 12));

        // Solid hairlines, never dashed: dashing adds noise and competes with the data.
        styler.setPlotGridLinesColor(GRID);
        styler.setPlotGridLinesStroke(new BasicStroke(0.6f));
        styler.setAxisTickMarksColor(AXIS);
        styler.setChartFontColor(INK_SECONDARY);

        styler.setLegendPosition(Styler.LegendPosition.InsideNE);
        styler.setLegendBorderColor(AXIS);
    }
}
