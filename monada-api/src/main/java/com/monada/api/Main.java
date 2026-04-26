package com.monada.api;

public class Main {

    public static void main(String[] args) {
        var memory = MonadaMemory.open("./monada-memory");

        memory.remember("OrientDB is a multi-model database that combines graph and document models.");
        memory.remember("Redis is an in-memory data structure store often used as a cache.");

        var recall = memory.resonate("database with graph and document model")
                .topK(5)
                .threshold(0.1)
                .execute();

        for (var result : recall.results()) {
            System.out.println(result.score());
            System.out.println(result.atom().content());
        }
    }
}