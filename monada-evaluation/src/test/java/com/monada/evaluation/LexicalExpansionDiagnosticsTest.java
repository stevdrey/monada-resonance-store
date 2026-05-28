package com.monada.evaluation;

import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.SimpleFrequencyEncoder;
import org.junit.jupiter.api.Test;

public class LexicalExpansionDiagnosticsTest {

    @Test
    void runDiagnostics() {
        System.out.println("=== RUNNING LEXICAL EXPANSION DIAGNOSTICS ===");

        // Let's initialize LexicalEnrichmentPipeline
        var pipeline = new LexicalEnrichmentPipeline();
        var encoder = new SimpleFrequencyEncoder(128);

        // Analyze tokens of query
        var query = "measuring directional alignment between vectors";
        var normQuery = pipeline.normalize(query);
        System.out.println("Query original: " + query);
        System.out.println("Query normalized: " + normQuery.normalized());
        System.out.println("Query expansions: " + normQuery.expansions());

        // Target content
        var targetContent = "Cosine similarity measures the cosine of the angle between two vectors, indicating their directional alignment.";
        var targetNorm = pipeline.normalize(targetContent);
        System.out.println("\nTarget original: " + targetContent);
        System.out.println("Target normalized: " + targetNorm.normalized());
        System.out.println("Target expansions: " + targetNorm.expansions());

        // Competitor content (ka_caching)
        var competitorContent = "Caching stores frequently accessed data in a fast layer to reduce latency and backend load. temporary lookup fast repeated access cache layer";
        var competitorNorm = pipeline.normalize(competitorContent);
        System.out.println("\nCompetitor original: " + competitorContent);
        System.out.println("Competitor normalized: " + competitorNorm.normalized());
        System.out.println("Competitor expansions: " + competitorNorm.expansions());

        // RAW profile simulation (no-op normalization)
        var qRawVec = encoder.encode(query);
        var tRawVec = encoder.encode(targetContent);
        var cRawVec = encoder.encode(competitorContent);
        double targetRawScore = cosine(qRawVec.values(), tRawVec.values());
        double competitorRawScore = cosine(qRawVec.values(), cRawVec.values());
        System.out.printf("\nRAW Profile -> Target Cosine: %.6f, Competitor Cosine: %.6f (Diff: %.6f)\n\n",
                targetRawScore, competitorRawScore, targetRawScore - competitorRawScore);

        // Let's test with different expansion weights and weighted aliases
        double[] weights = {1.0, 0.5, 0.25, 0.1, 0.05, 0.01};
        for (double w : weights) {
            var options = new LexicalExpansionOptions(1.0, w);
            var qVec = encoder.encode(normQuery.toWeightedText(options));
            var tVec = encoder.encode(targetNorm.toWeightedText(options));

            // Custom competitor vector with weighted aliases
            // Original tokens (weight 1.0) + Alias tokens (weight w) + Expansion tokens (weight w)
            float[] cVecValues = new float[128];
            String[] origTokens = "caching stores frequently accessed data fast layer reduce latency backend load".split(" ");
            for (String token : origTokens) {
                addToken(cVecValues, encoder, token, 1.0);
            }
            String[] aliasTokens = "temporary lookup fast repeated access cache layer".split(" ");
            for (String token : aliasTokens) {
                addToken(cVecValues, encoder, token, w);
            }
            String[] expansionTokens = "caching redis cache".split(" ");
            for (String token : expansionTokens) {
                addToken(cVecValues, encoder, token, w);
            }
            normalize(cVecValues);

            double targetScore = cosine(qVec.values(), tVec.values());
            double competitorScore = cosine(qVec.values(), cVecValues);

            System.out.printf("Weight: %.3f (Weighted Aliases) -> Target Cosine: %.6f, Competitor Cosine: %.6f (Diff: %.6f)\n",
                    w, targetScore, competitorScore, targetScore - competitorScore);
        }
        // Let's run a bucket-collision check
        System.out.println("\n=== HASHING COLLISION ANALYSIS ===");
        printTokenDetails(encoder, "vector");
        printTokenDetails(encoder, "vectors");
        printTokenDetails(encoder, "directional");
        printTokenDetails(encoder, "alignment");
        printTokenDetails(encoder, "measuring");
        printTokenDetails(encoder, "between");

        System.out.println("\n--- Query Vector Bucket Assignments ---");
        for (String qToken : "measuring directional alignment between vector".split(" ")) {
            printTokenDetails(encoder, qToken);
        }

        System.out.println("\n--- Competitor Token Bucket Assignments ---");
        for (String cToken : competitorNorm.normalized().split(" ")) {
            printTokenDetails(encoder, cToken);
        }
    }

    private static void printTokenDetails(SimpleFrequencyEncoder encoder, String token) {
        try {
            var method = SimpleFrequencyEncoder.class.getDeclaredMethod("digest", String.class);
            method.setAccessible(true);
            byte[] digest = (byte[]) method.invoke(encoder, token);

            var toIntMethod = SimpleFrequencyEncoder.class.getDeclaredMethod("toInt", byte[].class, int.class);
            toIntMethod.setAccessible(true);

            int bucket = Math.floorMod((int) toIntMethod.invoke(encoder, digest, 0), 128);
            float sign = Math.floorMod((int) toIntMethod.invoke(encoder, digest, 4), 2) == 0 ? 1.0f : -1.0f;

            System.out.printf("Token: %-15s -> Bucket: %d, Sign: %s\n", token, bucket, sign > 0 ? "+" : "-");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void addToken(float[] vector, SimpleFrequencyEncoder encoder, String token, double weight) {
        try {
            var method = SimpleFrequencyEncoder.class.getDeclaredMethod("digest", String.class);
            method.setAccessible(true);
            byte[] digest = (byte[]) method.invoke(encoder, token);

            var toIntMethod = SimpleFrequencyEncoder.class.getDeclaredMethod("toInt", byte[].class, int.class);
            toIntMethod.setAccessible(true);

            int bucket = Math.floorMod((int) toIntMethod.invoke(encoder, digest, 0), 128);
            float sign = Math.floorMod((int) toIntMethod.invoke(encoder, digest, 4), 2) == 0 ? 1.0f : -1.0f;

            vector[bucket] += (float) (sign * weight);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void normalize(float[] vector) {
        double magnitude = 0.0;
        for (float value : vector) {
            magnitude += value * value;
        }
        if (magnitude == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
        }
        return dot;
    }
}
