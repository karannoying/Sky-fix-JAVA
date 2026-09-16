package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-2.3 negative cases: {@code dt <= 0} and {@code tEnd <= t0} name the offending field. */
class SimSettingsTest {

    @Test
    @DisplayName("Defaults build, and describe themselves")
    void defaultsBuild() throws Exception {
        SimSettings s = SimSettings.builder().build();
        assertThat(s.integrator()).isEqualTo("RK4");
        // 0.25 s, not 1 s: RK4 leaves its stability region partway down a parachute descent at a
        // 1 s step, so the default carries about a threefold margin (ADR-13).
        assertThat(s.stepSeconds()).isEqualTo(0.25);
        assertThat(s.stateSampleStride()).isGreaterThanOrEqualTo(1);
        assertThat(s.toString()).contains("RK4").contains("dt=0.25");
    }

    @Test
    @DisplayName("FR-2.3: a non-positive step is rejected, naming step_s")
    void nonPositiveStepRejected() {
        assertThatThrownBy(() -> SimSettings.builder().stepSeconds(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("step_s");
        assertThatThrownBy(() -> SimSettings.builder().stepSeconds(-1.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("step_s");
    }

    @Test
    @DisplayName("FR-2.3: a non-positive end time is rejected, naming t_end_s")
    void nonPositiveEndTimeRejected() {
        assertThatThrownBy(() -> SimSettings.builder().endSeconds(0.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("t_end_s");
        assertThatThrownBy(() -> SimSettings.builder().stepSeconds(10.0).endSeconds(5.0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("step_s");
    }

    @Test
    @DisplayName("A derived copy is revalidated, so it can never be less valid than its source")
    void derivedCopiesAreRevalidated() throws Exception {
        SimSettings s = SimSettings.builder().build();
        assertThat(s.withStepSeconds(0.5).stepSeconds()).isEqualTo(0.5);
        assertThat(s.withIntegrator("RKF45").integrator()).isEqualTo("RKF45");
        assertThatThrownBy(() -> s.withStepSeconds(-1.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("step_s");
        assertThatThrownBy(() -> s.withIntegrator(" "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("integrator");
    }

    @Test
    @DisplayName("A blank integrator name and a zero stride are rejected")
    void otherRulesRejected() {
        assertThatThrownBy(() -> SimSettings.builder().integrator(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("integrator");
        assertThatThrownBy(() -> SimSettings.builder().stateSampleStride(0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("state_sample_stride");
    }
}
