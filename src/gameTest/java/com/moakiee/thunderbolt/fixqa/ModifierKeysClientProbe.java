package com.moakiee.thunderbolt.fixqa;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Standalone client regression fixture; never packaged in the production mod. */
@Mod("thunderbolt_fix_qa")
@Mod.EventBusSubscriber(modid = "thunderbolt_fix_qa", value = Dist.CLIENT)
public final class ModifierKeysClientProbe {
    private static boolean checked;

    public ModifierKeysClientProbe() {
        GtoDataReadinessClientProbe.install();
    }

    @SubscribeEvent
    public static void check(RenderGuiEvent.Post event) {
        if (checked || Minecraft.getInstance().level == null) return;
        checked = true;
        try {
            Class<?> utility = Class.forName("com.gregtechceu.gtceu.utils.GTUtil");
            Method[] methods = {utility.getMethod("isShiftDown"), utility.getMethod("isCtrlDown"),
                    utility.getMethod("isAltDown")};
            boolean[] expected = {Screen.hasShiftDown(), Screen.hasControlDown(), Screen.hasAltDown()};
            for (int i = 0; i < methods.length; i++) {
                if (!methods[i].invoke(null).equals(expected[i])) {
                    throw new AssertionError("foreground key result changed: " + methods[i].getName());
                }
            }
            var failure = new AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try { assertBackgroundKeys(methods); }
                catch (Throwable error) { failure.set(error); }
            }, "thunderbolt-modifier-key-probe");
            worker.start(); worker.join(5000);
            if (worker.isAlive()) throw new AssertionError("background key query blocked");
            if (failure.get() != null) throw new AssertionError("background key query failed", failure.get());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    assertBackgroundKeys(methods);
                    System.out.println("THUNDERBOLT_KEY_QA PASS shutdown background key queries");
                } catch (Throwable error) {
                    System.out.println("THUNDERBOLT_KEY_QA FAIL shutdown " + error);
                    error.printStackTrace();
                }
            }, "thunderbolt-shutdown-key-probe"));
            System.out.println("THUNDERBOLT_KEY_QA PASS foreground unchanged; background queries neutral");
        } catch (Throwable error) {
            System.out.println("THUNDERBOLT_KEY_QA FAIL " + error);
            error.printStackTrace();
        }
    }

    private static void assertBackgroundKeys(Method[] methods) throws Exception {
        for (int repeat = 0; repeat < 100; repeat++) {
            for (Method method : methods) {
                if (!Boolean.FALSE.equals(method.invoke(null))) {
                    throw new AssertionError("background key query must be neutral: " + method.getName());
                }
            }
            if (Screen.hasShiftDown() || Screen.hasControlDown() || Screen.hasAltDown()) {
                throw new AssertionError("direct Screen key query must be neutral in the background");
            }
        }
    }
}
