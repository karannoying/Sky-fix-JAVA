# ADR-16 — Chart design: validated colour, equal-scale footprints, no dual axes

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-4.2, ADR-8, PL-1, PL-2, PL-6

## Context

ADR-8 makes exported PNGs the visual deliverable — there is no GUI, so these charts are what a
reader of the report and a recovery lead actually see. That makes their design a real decision
rather than a styling afterthought.

## Decisions

**Colour is validated, not chosen by eye.** The three-series palette used by PL-6 clears the
colour-vision-deficiency separation floor and the normal-vision floor with all pairs in play — the
relevant test for a scatter or multi-line chart, where every pair can appear adjacent. The first
band colour tried for the ensemble context failed the 2:1 contrast floor against the chart surface
(1.74:1) and was replaced with the next step up (2.06:1). "These look different enough" is not a
check; the separations were computed.

**Nested confidence levels take one hue at two steps, not two hues.** The 50% and 95% ellipses are
ordered, not unrelated categories, so they use the blue ramp light-to-dark rather than two
categorical slots. Two unrelated hues would imply two different kinds of thing.

**Thresholds wear muted ink.** PL-6's ±0.1% tolerance lines are chrome, not a fourth measurement,
so they take the muted grey rather than a fourth categorical hue that would read as another series.

**One axis, always.** PL-6 plots temperature, pressure and density residuals together because all
three are the same quantity in the same unit. A second y-scale would manufacture a relationship
the data does not contain.

**Every line is solid.** XChart cycles dash patterns across series by default; dashing adds noise
and competes with the data, so each series sets `SeriesLines.SOLID` explicitly.

**PL-2's axes share a scale, and the frame closes on the footprint.** An ellipse drawn with
different metres-per-pixel on each axis is not the ellipse that was fitted — the chart would show a
shape the data does not have. Two consequences follow, and both were found by rendering the chart
and looking at it:

- Including the launch site in that frame makes the span the *drift distance* rather than the
  footprint. At 143 km downwind the ellipses collapsed to a smear in a mostly empty panel. Launch
  is a different question, answered by the distance and bearing in the title and by the GeoJSON on
  a real map; its marker is drawn only when it genuinely falls inside the frame.
- Under a wind that veers little with altitude the footprint is genuinely a sliver — roughly 180:1
  on the shipped synthetic sounding — so an equal-scale panel is mostly empty. **That is the
  finding, not a fault.** Nearly all the uncertainty is in how long the balloon stays airborne,
  and that lands along one axis. Making it look like a conventional ellipse would require
  distorting the axes, which would hide the result the chart exists to report.

"Equal scale" is exact to within the asymmetry of the plot margins — a couple of percent on a
square canvas, since the left margin carries tick labels and the right does not. The Javadoc says
so rather than claiming more.

**Light surface only.** These PNGs are bound for a printed report, so there is one target surface
and every colour was validated against it. A dark variant would need its own validated steps, and
nothing here would read it.

## What testing a chart can and cannot assert

A test cannot decide whether a chart is well designed. `PlotExporterTest` asserts what is
checkable and what actually goes wrong: the files exist at the documented names, decode at the
expected sizes, finish inside the budget, and — the failure mode worth catching — are not blank.
A chart that renders an empty panel still writes a valid PNG of the right size, so the test counts
distinct colours and checks that each series colour reached the canvas, which catches a series
drawn in the wrong colour or hidden behind another.

Everything else needs a person to look, and the three defects fixed during this work — dashed
lines, the launch-framed footprint, the clipped title — were all found that way, by rendering the
charts and reading them, not by a passing test.
