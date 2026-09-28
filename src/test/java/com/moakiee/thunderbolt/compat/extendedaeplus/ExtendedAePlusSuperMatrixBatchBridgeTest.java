package com.moakiee.thunderbolt.compat.extendedaeplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;

class ExtendedAePlusSuperMatrixBatchBridgeTest extends com.moakiee.thunderbolt.test.MinecraftComponentsTestBase {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void realScaledPatternPreservesNestedCopiesAndPropagatesSubmissionFailure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ExtendedAePlusSuperMatrixBatchBridge.isAvailable(),
                "Optional EAEP artifact not supplied");
        var pattern = (appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class },
                (p, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new appeng.api.crafting.IPatternDetails.IInput[0];
                    case "getOutputs" -> List.of(new appeng.api.stacks.GenericStack(AEItemKey.of(Items.SAND), 2));
                    default -> null;
                });
        var scaledType = Class.forName("com.extendedae_plus.api.crafting.ScaledMolecularAssemblerPattern");
        var nested = (appeng.api.crafting.IPatternDetails) scaledType.getConstructor(
                appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class, long.class).newInstance(pattern, 3L);
        var source = new KeyCounter(); source.add(AEItemKey.of(Items.STONE), 3);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = (appeng.api.networking.crafting.ICraftingProvider) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {appeng.api.networking.crafting.ICraftingProvider.class},
                (p, method, args) -> {
                    if (!method.getName().equals("pushPattern")) return false;
                    org.junit.jupiter.api.Assertions.assertEquals(12L, scaledType.getMethod("getMultiplier").invoke(args[0]));
                    var owned = (KeyCounter[]) args[1];
                    org.junit.jupiter.api.Assertions.assertEquals(12, owned[0].get(AEItemKey.of(Items.STONE)));
                    owned[0].reset();
                    if (calls.incrementAndGet() == 2) throw new IllegalStateException("uncertain submit");
                    return true;
                });
        org.junit.jupiter.api.Assertions.assertEquals(0, ExtendedAePlusSuperMatrixBatchBridge.pushBatch(provider, nested, new KeyCounter[] {source}, 4));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ExtendedAePlusSuperMatrixBatchBridge.pushBatch(provider, nested, new KeyCounter[] {source}, 4));
        org.junit.jupiter.api.Assertions.assertEquals(3, source.get(AEItemKey.of(Items.STONE)));
        org.junit.jupiter.api.Assertions.assertEquals(2, calls.get());
    }

    @Test
    void scalesAnOwnedCopyWithoutMutatingTheBorrowedTemplate() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var source = new KeyCounter();
        source.add(key, 3L);

        var scaled = ExtendedAePlusSuperMatrixBatchBridge.scaleTemplate(
                new KeyCounter[] { source },
                4L);

        assertEquals(3L, source.get(key));
        assertEquals(12L, scaled[0].get(key));
    }

    @Test
    void rejectsScalingThatWouldOverflowAKeyCounter() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var source = new KeyCounter();
        source.add(key, Long.MAX_VALUE);

        assertNull(ExtendedAePlusSuperMatrixBatchBridge.scaleTemplate(
                new KeyCounter[] { source },
                2L));
        assertEquals(Long.MAX_VALUE, source.get(key));
    }
}
