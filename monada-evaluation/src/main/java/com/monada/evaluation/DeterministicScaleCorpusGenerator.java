package com.monada.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Deterministically generates scaled {@link EvaluationDataset} instances by augmenting
 * a base semantic dataset with reproducible distractor atoms.
 *
 * <p>Distractors include both lexical/confusable atoms (which share technical vocabulary
 * with evaluation queries in distinct domains) and easy orthogonal atoms (from non-technical
 * domains such as botany, culinary arts, astronomy, architecture, and music theory).
 *
 * <p>All generation is strictly deterministic for a given seed and target size, guaranteeing
 * reproducible atom contents, UUIDs, and search ranking across runs.
 */
public final class DeterministicScaleCorpusGenerator {

    public static final long DEFAULT_SEED = 42L;
    private static final double CONFUSABLE_RATIO = 0.20;

    private static final List<String> CONFUSABLE_TEMPLATES = List.of(
            "Vector graphics in %s utilize Cartesian coordinates and Bezier curves for resolution-independent 2D rendering.",
            "In compiler design, graph coloring is used during %s register allocation to minimize spill costs.",
            "Hardware cache coherence protocols like %s maintain consistency across multi-core CPU L1 and L2 caches.",
            "Relational algebra operations such as %s projection and join are fundamental to query optimization.",
            "The memory management unit translates virtual addresses to physical pages using %s lookup tables.",
            "In distributed robotics, consensus algorithms enable %s autonomous swarms to agree on spatial coordinates.",
            "In-memory transaction logging in %s avionics controllers ensures deterministic state recovery after power loss.",
            "An inverted index in %s source code search engines indexes abstract syntax tree tokens for rapid lookup.",
            "Search ranking algorithms for %s online libraries weigh publication recency against citation counts.",
            "Distributed audio stream replication across %s broadcast nodes guarantees zero-latency failover.",
            "Data sharding in %s astronomical sensor arrays partitions observational feeds by celestial hemisphere.",
            "Database normalization up to %s Boyce-Codd normal form eliminates functional dependency anomalies.",
            "Approximate nearest-neighbor algorithms in %s computational chemistry accelerate molecular docking scans.",
            "Append-only write logs in %s flight data recorders preserve timestamped telemetry streams immutably.",
            "CQRS architecture in %s financial exchange matching engines isolates order submission from order book viewing.",
            "Locality-sensitive hashing in %s audio fingerprinting maps similar acoustic waveforms to identical buckets."
    );

    private static final List<String> CONFUSABLE_SUBJECTS = List.of(
            "modern browser engines", "optimizing compilers", "MESI and MOESI",
            "relational database theory", "virtual memory subsystems", "aerial drone",
            "embedded real-time", "static code analysis", "academic digital",
            "telecommunications", "radio telescope", "database schema design",
            "drug discovery", "commercial aviation", "high-frequency trading",
            "digital audio forensics", "embedded microcontrollers", "aerospace telemetry"
    );

    private static final List<String> UNRELATED_TEMPLATES = List.of(
            "Photosynthesis in %s converts sunlight and carbon dioxide into glucose and oxygen through chlorophyll.",
            "The culinary technique of %s involves slowly cooking ingredients in vacuum-sealed pouches at precise temperatures.",
            "In astronomy, %s are celestial bodies with gravitational fields strong enough to prevent light escape.",
            "The architectural style of %s is characterized by pointed arches, ribbed vaults, and flying buttresses.",
            "In classical music, %s is a contrapuntal compositional technique based on the imitation of a principal theme.",
            "Geological plate tectonics explain the formation of %s through continental drift and subduction zones.",
            "The ancient Silk Road facilitated trade and cultural exchange of %s between Asia and the Mediterranean.",
            "In meteorology, %s are rotating storm systems characterized by low atmospheric pressure centers.",
            "The cellular structure of %s includes a rigid cell wall composed primarily of cellulose and hemicellulose.",
            "In literature, %s is a narrative device where events are presented out of chronological order.",
            "Deep-sea hydrothermal vents support unique ecosystems of %s that rely on chemosynthesis rather than sunlight.",
            "The renaissance art movement emphasized %s linear perspective, anatomical precision, and naturalistic lighting."
    );

    private static final List<String> UNRELATED_SUBJECTS = List.of(
            "flowering angiosperms", "sous-vide preparation", "stellar black holes",
            "Gothic cathedrals", "fugal counterpoint", "volcanic mountain ranges",
            "spices, silk, and pottery", "tropical cyclones", "vascular plant tissue",
            "non-linear flashbacks", "extremophile tube worms", "Florentine painting",
            "coral reef polyps", "ancient Roman aqueducts", "stratospheric ozone layers",
            "baroque fugues", "Jurassic fossil beds", "temperate rain forests"
    );

    private final long seed;

    public DeterministicScaleCorpusGenerator() {
        this(DEFAULT_SEED);
    }

    public DeterministicScaleCorpusGenerator(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /**
     * Augments {@code baseDataset} with deterministic distractors to produce a dataset
     * with exactly {@code targetCorpusSize} atoms while preserving all base queries.
     *
     * @param baseDataset      the seed dataset whose atoms and queries are retained
     * @param targetCorpusSize the desired total atom count (must be &gt;= baseDataset.atoms().size())
     * @return an augmented {@link EvaluationDataset}
     */
    public EvaluationDataset generate(EvaluationDataset baseDataset, int targetCorpusSize) {
        Objects.requireNonNull(baseDataset, "baseDataset");
        int baseSize = baseDataset.atoms().size();
        if (targetCorpusSize < baseSize) {
            throw new IllegalArgumentException(
                    "targetCorpusSize must be >= base dataset size (" + baseSize + "), got: " + targetCorpusSize);
        }

        if (targetCorpusSize == baseSize) {
            return baseDataset;
        }

        int distractorsNeeded = targetCorpusSize - baseSize;
        Set<String> existingLabels = new HashSet<>();
        for (DatasetAtom atom : baseDataset.atoms()) {
            existingLabels.add(atom.label());
        }

        Random random = new Random(seed);
        List<DatasetAtom> allAtoms = new ArrayList<>(targetCorpusSize);
        allAtoms.addAll(baseDataset.atoms());

        int confusableGenerated = 0;
        int unrelatedGenerated = 0;

        for (int i = 0; i < distractorsNeeded; i++) {
            boolean makeConfusable = random.nextDouble() < CONFUSABLE_RATIO;

            String label;
            String content;
            if (makeConfusable) {
                confusableGenerated++;
                label = String.format("distractor_confusable_%06d", confusableGenerated);
                String template = CONFUSABLE_TEMPLATES.get(random.nextInt(CONFUSABLE_TEMPLATES.size()));
                String subject = CONFUSABLE_SUBJECTS.get(random.nextInt(CONFUSABLE_SUBJECTS.size()));
                content = String.format(template, subject) + " [id: " + label + "]";
            } else {
                unrelatedGenerated++;
                label = String.format("distractor_unrelated_%06d", unrelatedGenerated);
                String template = UNRELATED_TEMPLATES.get(random.nextInt(UNRELATED_TEMPLATES.size()));
                String subject = UNRELATED_SUBJECTS.get(random.nextInt(UNRELATED_SUBJECTS.size()));
                content = String.format(template, subject) + " [id: " + label + "]";
            }

            if (!existingLabels.add(label)) {
                throw new IllegalStateException("generated duplicate label: " + label);
            }
            allAtoms.add(new DatasetAtom(label, content, List.of()));
        }

        return new EvaluationDataset(Collections.unmodifiableList(allAtoms), baseDataset.queries());
    }
}
