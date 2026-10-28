package com.moakiee.thunderbolt.compat.neoeco;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client-only access behind a common Level descriptor for NeoECO's shared UI factory. */
@OnlyIn(Dist.CLIENT)
public final class NeoEcoClientLevel {
    private NeoEcoClientLevel() {}

    public static Level current() { return Minecraft.getInstance().level; }
}
