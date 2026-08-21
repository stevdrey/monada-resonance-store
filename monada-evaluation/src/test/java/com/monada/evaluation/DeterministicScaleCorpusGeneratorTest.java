package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicScaleCorpusGeneratorTest {

    @Test
    void generationIsDeterministicAcrossInstances() {
        var base = ExpandedTechnologyDataset.get();
        var gen1 = new DeterministicScaleCorpusGenerator(123L);
        var gen2 = new DeterministicScaleCorpusGenerator(123L);

        var dataset1 = gen1.generate(base, 100);
        var dataset2 = gen2.generate(base, 100);

        assertEquals(100, dataset1.atoms().size());
        assertEquals(100, dataset2.atoms().size());
        assertEquals(dataset1.atoms().size(), dataset2.atoms().size());

        for (int i = 0; i < 100; i++) {
            var a1 = dataset1.atoms().get(i);
            var a2 = dataset2.atoms().get(i);
            assertEquals(a1.label(), a2.label());
            assertEquals(a1.content(), a2.content());
        }
    }

    @Test
    void differentSeedsProduceDifferentDistractors() {
        var base = ExpandedTechnologyDataset.get();
        var gen1 = new DeterministicScaleCorpusGenerator(100L);
        var gen2 = new DeterministicScaleCorpusGenerator(200L);

        var dataset1 = gen1.generate(base, 50);
        var dataset2 = gen2.generate(base, 50);

        // Distractor contents (index >= base size) should differ
        int baseSize = base.atoms().size();
        assertNotEquals(dataset1.atoms().get(baseSize).content(),
                dataset2.atoms().get(baseSize).content());
    }

    @Test
    void exactTargetCorpusSizeRespected() {
        var base = ExpandedTechnologyDataset.get();
        var gen = new DeterministicScaleCorpusGenerator();

        assertEquals(base.atoms().size(), gen.generate(base, base.atoms().size()).atoms().size());
        assertEquals(50, gen.generate(base, 50).atoms().size());
        assertEquals(100, gen.generate(base, 100).atoms().size());
        assertEquals(250, gen.generate(base, 250).atoms().size());
    }

    @Test
    void baseAtomsAndQueriesPreserved() {
        var base = ExpandedTechnologyDataset.get();
        var gen = new DeterministicScaleCorpusGenerator();
        var scaled = gen.generate(base, 80);

        assertEquals(base.queries(), scaled.queries());
        for (int i = 0; i < base.atoms().size(); i++) {
            assertEquals(base.atoms().get(i), scaled.atoms().get(i));
        }
    }

    @Test
    void noLabelCollisionsWithBaseDataset() {
        var base = ExpandedTechnologyDataset.get();
        var gen = new DeterministicScaleCorpusGenerator();
        var scaled = gen.generate(base, 120);

        Set<String> labels = new HashSet<>();
        for (var atom : scaled.atoms()) {
            assertTrue(labels.add(atom.label()), "Duplicate label found: " + atom.label());
        }
    }

    @Test
    void confusableAndUnrelatedDistractorsPresent() {
        var base = ExpandedTechnologyDataset.get();
        var gen = new DeterministicScaleCorpusGenerator();
        var scaled = gen.generate(base, 100);

        long confusable = scaled.atoms().stream()
                .filter(a -> a.label().startsWith("distractor_confusable_"))
                .count();
        long unrelated = scaled.atoms().stream()
                .filter(a -> a.label().startsWith("distractor_unrelated_"))
                .count();

        assertTrue(confusable > 0, "Must contain confusable distractors");
        assertTrue(unrelated > 0, "Must contain unrelated distractors");
        assertEquals(100 - base.atoms().size(), confusable + unrelated);
    }

    @Test
    void rejectsTargetSizeLessThanBase() {
        var base = ExpandedTechnologyDataset.get();
        var gen = new DeterministicScaleCorpusGenerator();
        assertThrows(IllegalArgumentException.class, () -> gen.generate(base, base.atoms().size() - 1));
    }
}
