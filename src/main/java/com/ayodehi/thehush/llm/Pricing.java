package com.ayodehi.thehush.llm;

/**
 * Rough dollar cost of a call from its token counts. Prices are USD per million tokens. Built-in rates
 * are the list prices for the Opus, Sonnet, and Haiku tiers as this mod knows them; they go stale, so
 * llm.inputPricePerMTok and llm.outputPricePerMTok in the config override them when set above zero.
 * Cache writes are charged at 1.25x the input rate and cache reads at 0.1x, as the API does.
 */
public final class Pricing {
    public static final double CACHE_WRITE_FACTOR = 1.25;
    public static final double CACHE_READ_FACTOR = 0.1;

    private Pricing() {}

    /** [input, output] USD per million tokens for a model id, from its tier name. Unknown tiers use the Sonnet rate. */
    public static double[] builtIn(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.startsWith("ollama/") || m.startsWith("openai/")) return new double[] {0.0, 0.0}; // local or unknown: only the config can say
        if (m.contains("opus") || m.contains("fable") || m.contains("mythos")) return new double[] {15.0, 75.0};
        if (m.contains("haiku")) return new double[] {1.0, 5.0};
        return new double[] {3.0, 15.0};
    }

    /** Cost in USD. Overrides at or below zero fall back to the built-in table. */
    public static double cost(String model, long input, long output, long cacheRead, long cacheWrite,
                              double inputOverride, double outputOverride) {
        double[] rates = builtIn(model);
        String m = model == null ? "" : model.toLowerCase();
        if (m.startsWith("ollama/")) return 0.0; // local: never priced
        double in = inputOverride > 0 ? inputOverride : rates[0];
        double out = outputOverride > 0 ? outputOverride : rates[1];
        return (input * in + cacheWrite * in * CACHE_WRITE_FACTOR + cacheRead * in * CACHE_READ_FACTOR + output * out) / 1_000_000.0;
    }

    public static String dollars(double usd) {
        if (usd < 0.01) return String.format("$%.4f", usd);
        return String.format("$%.2f", usd);
    }
}
