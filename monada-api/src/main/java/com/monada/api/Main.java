package com.monada.api;

import com.monada.encoder.SimpleFrequencyEncoder;

public class Main {

    public static void main(String[] args) {

        var encoder = new SimpleFrequencyEncoder(8);

        var vector = encoder.encode("graph document database");

        System.out.println("Vector size: " + vector.values().length);
    }
}