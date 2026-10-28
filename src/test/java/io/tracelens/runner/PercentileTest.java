package io.tracelens.runner;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PercentileTest {
    @Test
    void usesNearestRankAndHandlesEmptySamples() {
        var values = ExperimentRunner.percentiles(List.of(50L, 10L, 40L, 20L, 30L));
        assertThat(values).containsEntry("sampleCount", 5).containsEntry("p50", 30L)
                .containsEntry("p95", 50L).containsEntry("p99", 50L);
        assertThat(ExperimentRunner.percentiles(List.of())).containsEntry("sampleCount", 0)
                .containsEntry("p99", null);
    }
}
