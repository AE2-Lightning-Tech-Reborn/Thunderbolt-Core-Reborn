package com.moakiee.thunderbolt.api.crafting;

import java.math.BigInteger;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import net.minecraft.network.chat.Component;

/** Shared native confirmation entry point. Long-sized requests retain AE2's normal pipeline. */
public interface CraftingRequestMenu {
    boolean thunderbolt$planRequest(AEKey key, BigInteger amount, CalculationStrategy strategy);

    boolean thunderbolt$isExactRequest();

    Component thunderbolt$getRequestError();
}
