package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyScaleSweepIntegrationTest {

    @Test
    void multiScaleSweepRunsEndToEnd(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 60, 100);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 1, 2, 42L);
        var report = runner.run(tempDir);

        assertNotNull(report);
        assertEquals(6, report.results().size());

        for (var res : report.results()) {
            assertTrue(res.corpusSize() >= 33);
            assertTrue(res.queryCount() == 18);
            assertTrue(res.totalScanned() > 0);
            assertTrue(res.totalReturned() > 0);
            assertNotNull(res.timing());
            assertNotNull(res.evaluationReport());
            assertTrue(res.timing().sampleCount() > 0);
        }

        var rendered = report.render();
        assertFalse(rendered.isBlank());
    }
}
