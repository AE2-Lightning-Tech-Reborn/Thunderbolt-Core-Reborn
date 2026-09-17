package com.moakiee.thunderbolt.compat.gtl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat.Handover;

class GtlCompatTest {
    @AfterEach
    void clearModeOverride() {
        System.clearProperty(GtlCompat.MODE_PROPERTY);
        GtlCompat.resetPresenceCache();
    }

    @Test
    void blankOrUnknownModeFallsBackToAuto() {
        assertEquals(Handover.AUTO, GtlCompat.parseMode(null));
        assertEquals(Handover.AUTO, GtlCompat.parseMode(""));
        assertEquals(Handover.AUTO, GtlCompat.parseMode("   "));
        assertEquals(Handover.AUTO, GtlCompat.parseMode("sometimes"));
    }

    @Test
    void recognizesDocumentedModeTokens() {
        assertEquals(Handover.ALWAYS, GtlCompat.parseMode("always"));
        assertEquals(Handover.ALWAYS, GtlCompat.parseMode(" ALWAYS "));
        assertEquals(Handover.ALWAYS, GtlCompat.parseMode("on"));
        assertEquals(Handover.ALWAYS, GtlCompat.parseMode("true"));
        assertEquals(Handover.NEVER, GtlCompat.parseMode("never"));
        assertEquals(Handover.NEVER, GtlCompat.parseMode("off"));
        assertEquals(Handover.NEVER, GtlCompat.parseMode("false"));
    }

    @Test
    void modeComesFromTheSystemProperty() {
        assertEquals(Handover.AUTO, GtlCompat.handoverMode());
        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        assertEquals(Handover.NEVER, GtlCompat.handoverMode());
    }

    @Test
    void autoStandsDownOnlyWhenGtlIsPresent() {
        assertTrue(GtlCompat.standDown(Handover.AUTO, true));
        assertFalse(GtlCompat.standDown(Handover.AUTO, false));
    }

    @Test
    void forcedModesIgnoreGtlPresence() {
        assertTrue(GtlCompat.standDown(Handover.ALWAYS, false));
        assertFalse(GtlCompat.standDown(Handover.NEVER, true));
    }

    @Test
    void predicateOverloadReadsTheConfiguredModeAndTheGtlModId() {
        System.setProperty(GtlCompat.MODE_PROPERTY, "auto");
        assertTrue(GtlCompat.standDown(GtlCompat.GTL_MOD_ID::equals));
        assertFalse(GtlCompat.standDown(ignored -> false));

        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        assertFalse(GtlCompat.standDown(GtlCompat.GTL_MOD_ID::equals));

        System.setProperty(GtlCompat.MODE_PROPERTY, "always");
        assertTrue(GtlCompat.standDown(ignored -> false));
    }

    @Test
    void inconclusivePresenceProbeIsNotCached() {
        assertFalse(GtlCompat.rememberPresence(null));
        assertTrue(GtlCompat.rememberPresence(true));
        assertTrue(GtlCompat.rememberPresence(false));
    }

    @Test
    void definiteAbsenceIsCached() {
        assertFalse(GtlCompat.rememberPresence(false));
        assertFalse(GtlCompat.rememberPresence(true));
    }

    @Test
    void loadingListHitIsEnough() {
        assertEquals(Boolean.TRUE, GtlCompat.resolvePresence(true, null));
        assertEquals(Boolean.TRUE, GtlCompat.resolvePresence(true, false));
    }

    @Test
    void loadingListMissFallsThroughToModList() {
        assertEquals(Boolean.TRUE, GtlCompat.resolvePresence(false, true));
        assertEquals(Boolean.FALSE, GtlCompat.resolvePresence(false, false));
        assertEquals(null, GtlCompat.resolvePresence(false, null));
        assertEquals(null, GtlCompat.resolvePresence(null, null));
    }
}
