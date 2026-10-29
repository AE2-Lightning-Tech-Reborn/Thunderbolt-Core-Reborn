package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.script.Invocable;
import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class NeoEcoGtoRemoteUiTransformerTest {
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";

    @Test
    void removesBothClientLevelVerificationEdgesAndRetainsTheReader() throws Exception {
        for (String getter : new String[]{"getInstance", "m_91087_"}) {
            var engine = engine();
            var definition = definition(engine);
            var target = (Map<?, ?>) definition.get("target");
            assertEquals("cn.dancingsnow.neoecoae.compat.ae2.ECORemoteMachineUI$1", target.get("class"));
            var method = reader(2, getter);
            engine.invokeMethod(definition, "transformer", method);
            int hooks = 0;
            for (var node : method.instructions) {
                if (node instanceof FieldInsnNode field) fail("ClientLevel field still linked: " + field.desc);
                if (node instanceof MethodInsnNode call) {
                    assertNotEquals(MINECRAFT, call.owner);
                    assertEquals("com/moakiee/thunderbolt/compat/neoeco/NeoEcoClientLevel", call.owner);
                    assertEquals("()Lnet/minecraft/world/level/Level;", call.desc);
                    hooks++;
                }
            }
            assertEquals(2, hooks);
            assertEquals(Opcodes.ARETURN, method.instructions.getLast().getOpcode());
        }
    }

    @Test
    void fixedUpstreamReaderIsUntouchedAndPartialAccessesFailBeforeMutation() throws Exception {
        var engine = engine();
        var definition = definition(engine);
        for (int count : new int[]{0, 1, 3}) {
            var method = reader(count, "m_91087_");
            int before = method.instructions.size();
            if (count == 0) engine.invokeMethod(definition, "transformer", method);
            else assertThrows(Exception.class, () -> engine.invokeMethod(definition, "transformer", method));
            assertEquals(before, method.instructions.size());
        }
    }

    private static MethodNode reader(int count, String getter) {
        var method = new MethodNode(Opcodes.ACC_PROTECTED, "readHolderFromSyncData", "()Ljava/lang/Object;", null, null);
        for (int i = 0; i < count; i++) {
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MINECRAFT, getter,
                    "()Lnet/minecraft/client/Minecraft;", false));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MINECRAFT, "f_91073_",
                    "Lnet/minecraft/client/multiplayer/ClientLevel;"));
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static Invocable engine() throws Exception {
        var engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        try (var script = NeoEcoGtoRemoteUiTransformerTest.class.getResourceAsStream("/coremods/neoeco_gto_remote_ui.js")) {
            assertNotNull(script);
            engine.eval(new InputStreamReader(script, StandardCharsets.UTF_8));
        }
        return (Invocable) engine;
    }

    private static Map<?, ?> definition(Invocable engine) throws Exception {
        return (Map<?, ?>) ((Map<?, ?>) engine.invokeFunction("initializeCoreMod")).get("neoeco_gto_remote_ui");
    }
}
