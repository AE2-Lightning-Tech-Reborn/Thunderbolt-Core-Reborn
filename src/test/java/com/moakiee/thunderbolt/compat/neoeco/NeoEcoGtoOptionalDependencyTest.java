package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The regular test runtime intentionally contains no NeoECO dependency. */
class NeoEcoGtoOptionalDependencyTest {
    @Test
    void missingNeoEcoDoesNotLinkOptionalClassesOrInstallAnAdapter() {
        assertNull(getClass().getClassLoader().getResource(
                "cn/dancingsnow/neoecoae/api/me/ECOFastPathFacade.class"));
        assertNull(NeoEcoGtoBatchAdapter.createAdapter());
    }
}
