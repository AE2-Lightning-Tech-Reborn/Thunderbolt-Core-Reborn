package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

final class ExtendedCraftingCpuServiceMixinContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                    + "ExtendedCraftingCpuServiceMixin.java");

    @Test
    void exactPreviewsAreRejectedBeforeSimulationFallthrough() throws Exception {
        String source = Files.readString(SOURCE);
        int preview = source.indexOf("ExactPlanReports.isPreview(job)");
        int incomplete = source.indexOf("CraftingSubmitErrorCode.INCOMPLETE_PLAN");
        int simulation = source.indexOf("if (job.simulation())");
        assertTrue(preview >= 0, "submitJob must inspect exact-preview attachments");
        assertTrue(incomplete > preview,
                "exact previews must return INCOMPLETE_PLAN rather than falling through");
        assertTrue(simulation > incomplete,
                "simulation fall-through must not run before the exact-preview reject");
    }

    @Test
    void automaticSelectionDoesNotOverrideExplicitThirdPartyCpu() throws Exception {
        String source = Files.readString(SOURCE);

        int automaticHook = source.indexOf(
                "private void thunderbolt$submitToAutomaticExtendedCpuCluster(");
        int nextHook = source.indexOf("    @Inject(", automaticHook);
        String automaticHookSource = source.substring(automaticHook, nextHook);

        var explicitTargetGuardMatcher = Pattern.compile(
                "if\\s*\\(target != null\\)\\s*\\{\\s*return;\\s*\\}")
                .matcher(automaticHookSource);
        int explicitTargetGuard = explicitTargetGuardMatcher.find()
                ? explicitTargetGuardMatcher.start()
                : -1;
        int extendedCpuLookup = automaticHookSource.indexOf(
                "thunderbolt$findSuitableExtendedCpuCluster(");

        assertTrue(automaticHook >= 0, "automatic extended CPU submission hook must exist");
        assertTrue(explicitTargetGuard >= 0, "explicit third-party CPU targets must bypass automatic selection");
        assertTrue(explicitTargetGuard < extendedCpuLookup,
                "the explicit-target guard must run before selecting an extended CPU");
    }
}
