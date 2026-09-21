package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CraftConfirmPlanningCompositionContractTest {
    @Test
    void calculationTrackingComposesWithOtherPlannerHooks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "CraftConfirmMenuMixin.java"));

        int hook = source.indexOf("private Future<ICraftingPlan> thunderbolt$trackCalculation(");
        int nextHook = source.indexOf("    @Inject(", hook);
        String hookSource = source.substring(hook, nextHook);

        assertTrue(source.lastIndexOf("@WrapOperation(", hook) >= 0,
                "the calculation hook must compose with other planner wrappers");
        assertTrue(hookSource.contains("original.call("),
                "tracking must invoke the next operation in the MixinExtras chain");
        assertFalse(hookSource.contains("service.beginCraftingCalculation("),
                "tracking must not bypass another planner by calling the service directly");
        assertTrue(source.contains("if (com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(result)) ci.cancel();"),
                "startJob must refuse exact previews instead of auto-starting them");
    }
}
