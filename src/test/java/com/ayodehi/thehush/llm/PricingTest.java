package com.ayodehi.thehush.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingTest {
    @Test
    void cacheReadsAreATenthAndWritesAQuarterMore() {
        // Sonnet tier: $3 in, $15 out per million.
        double plain = Pricing.cost("claude-sonnet-5", 1_000_000, 0, 0, 0, 0, 0);
        double read = Pricing.cost("claude-sonnet-5", 0, 0, 1_000_000, 0, 0, 0);
        double write = Pricing.cost("claude-sonnet-5", 0, 0, 0, 1_000_000, 0, 0);
        double out = Pricing.cost("claude-sonnet-5", 0, 1_000_000, 0, 0, 0, 0);
        assertEquals(3.0, plain, 1e-9);
        assertEquals(0.3, read, 1e-9);
        assertEquals(3.75, write, 1e-9);
        assertEquals(15.0, out, 1e-9);
    }

    @Test
    void overridesReplaceTheTableOnlyWhenPositive() {
        assertEquals(2.0, Pricing.cost("claude-opus-5", 1_000_000, 0, 0, 0, 2.0, 0), 1e-9);
        assertEquals(75.0, Pricing.cost("claude-opus-5", 0, 1_000_000, 0, 0, 2.0, 0), 1e-9);
        assertEquals(15.0, Pricing.cost("claude-opus-5", 1_000_000, 0, 0, 0, -1, -1), 1e-9);
    }

    @Test
    void dollarsShowSmallAmountsWithMorePrecision() {
        assertEquals("$0.0042", Pricing.dollars(0.0042));
        assertEquals("$1.23", Pricing.dollars(1.234));
    }
}
