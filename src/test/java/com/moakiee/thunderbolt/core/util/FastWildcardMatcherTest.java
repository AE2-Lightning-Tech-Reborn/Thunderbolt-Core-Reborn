package com.moakiee.thunderbolt.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class FastWildcardMatcherTest {
    @Test
    void exactAndNamespaceRulesUseExpectedSemantics() {
        var matcher = FastWildcardMatcher.compile(List.of(
                "ae2:pattern_provider",
                "mekanism:*"));

        assertTrue(matcher.matches("ae2:pattern_provider"));
        assertTrue(matcher.matches("mekanism:chemical_dissolution_chamber"));
        assertFalse(matcher.matches("ae2:molecular_assembler"));
        assertFalse(matcher.matches("mekanism_extras:advanced_solar_generator"));
    }

    @Test
    void generalGlobsSupportStarsAndSingleCharacterWildcards() {
        var matcher = FastWildcardMatcher.compile(List.of(
                "*:overloaded_*_provider",
                "industrialforegoing:machine_?",
                "sophisticated*:*"));

        assertTrue(matcher.matches("ae2lt:overloaded_pattern_provider"));
        assertTrue(matcher.matches("industrialforegoing:machine_a"));
        assertTrue(matcher.matches("sophisticatedstorage:barrel"));
        assertTrue(matcher.matches("sophisticatedbackpacks:backpack"));
        assertTrue(matcher.matches("sophisticatedstoragecreateintegration:controller"));
        assertFalse(matcher.matches("industrialforegoing:machine_ab"));
    }

    @Test
    void patternsAreNormalizedAndMalformedEntriesAreRejected() {
        var matcher = FastWildcardMatcher.compile(List.of(
                "  AE2:Pattern_*  ",
                "missing_namespace",
                "bad space:*"));

        assertTrue(matcher.matches("ae2:pattern_provider"));
        assertFalse(matcher.matches("missing_namespace"));
        assertTrue(FastWildcardMatcher.isValidPattern("modid:path/*"));
        assertFalse(FastWildcardMatcher.isValidPattern("modid:"));
        assertFalse(FastWildcardMatcher.isValidPattern("bad/namespace:*"));
    }
}
