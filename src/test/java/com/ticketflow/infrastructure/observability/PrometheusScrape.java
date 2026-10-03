package com.ticketflow.infrastructure.observability;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/** Test helper: reads sample values out of a Prometheus text exposition. */
public final class PrometheusScrape {

    private final String text;

    public PrometheusScrape(String text) {
        this.text = text;
    }

    /** The one sample of {@code name} whose labels include every {@code label="value"} given, if present. */
    public OptionalDouble value(String name, String... labels) {
        List<String> matches = text.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .filter(line -> Arrays.stream(labels).allMatch(label -> {
                    String[] kv = label.split("=", 2);
                    return line.contains(kv[0] + "=\"" + kv[1] + "\"");
                }))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous sample " + name + Arrays.toString(labels) + ": " + matches);
        }
        if (matches.isEmpty()) {
            return OptionalDouble.empty();
        }
        String line = matches.get(0);
        return OptionalDouble.of(Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)));
    }

    /** Same as {@link #value} but {@code NaN} when the sample is absent. */
    public double valueOrNaN(String name, String... labels) {
        return value(name, labels).orElse(Double.NaN);
    }

    public String text() {
        return text;
    }
}
