package com.moakiee.thunderbolt.core.keys;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "thunderbolt", value = Dist.CLIENT)
public final class ObjectReuseClientMaintenance {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        KeyConstructionCache.maintain();
        ResourceConstructionCache.maintain();
    }
}
