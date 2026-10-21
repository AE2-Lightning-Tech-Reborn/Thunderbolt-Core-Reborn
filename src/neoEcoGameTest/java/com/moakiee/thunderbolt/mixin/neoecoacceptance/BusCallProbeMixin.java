package com.moakiee.thunderbolt.mixin.neoecoacceptance;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.gametest.NeoEcoAcceptanceTests;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the real native call without replacing or cancelling machine behavior. */
@Mixin(targets = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity", remap = false)
public abstract class BusCallProbeMixin {
    @Inject(method = "pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;Ljava/util/UUID;)Z",
            at = @At("HEAD"), remap = false)
    private void recordJob(IPatternDetails pattern, KeyCounter[] inputs, UUID job,
            CallbackInfoReturnable<Boolean> result) {
        NeoEcoAcceptanceTests.observedBus = (ICraftingProvider) (Object) this;
        NeoEcoAcceptanceTests.observedJob = job;
        NeoEcoAcceptanceTests.observedCalls++;
    }
}
