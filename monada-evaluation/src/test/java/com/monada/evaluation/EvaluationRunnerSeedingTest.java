package com.monada.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link EvaluationRunner#seedAtoms} duplicate-detection
 * added as part of the Issue #22 review fixes.
 */
class EvaluationRunnerSeedingTest {

    @Test
    void seedingRejectsDuplicateLabel(@TempDir Path memoryPath) {
        var ex = assertThrows(IllegalArgumentException.class, () -> {
            var dataset = new EvaluationDataset(
                    List.of(
                            new DatasetAtom("label_a", "content about indexing and search"),
                            new DatasetAtom("label_a", "content about storage and persistence")
                    ),
                    List.of(
                            new EvaluationQuery("indexing", Set.of("label_a"))
                    )
            );
            new EvaluationRunner().run(dataset, memoryPath);
        }, "the pipeline must reject a dataset that contains duplicate labels");
        assertTrue(ex.getMessage().contains("label_a"),
                "Exception message must identify the duplicate label");
    }

    @Test
    void seedingRejectsTwoAtomsSharingContentDerivedId(@TempDir Path memoryPath) {
        var sharedContent = "identical content shared by two atoms";
        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("label_x", sharedContent),
                        new DatasetAtom("label_y", sharedContent)
                ),
                List.of(
                        new EvaluationQuery("identical content", Set.of("label_x"))
                )
        );

        var ex = assertThrows(IllegalArgumentException.class,
                () -> new EvaluationRunner().run(dataset, memoryPath),
                "seedAtoms must reject two atoms with different labels that produce the same content-derived id");
        assertTrue(ex.getMessage().contains("label_x") || ex.getMessage().contains("label_y"),
                "Exception message must identify at least one of the colliding labels");
    }
}
