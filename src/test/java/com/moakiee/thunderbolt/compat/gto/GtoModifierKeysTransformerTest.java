package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.script.Invocable;
import javax.script.ScriptEngineManager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

class GtoModifierKeysTransformerTest {
    private static final String TARGET = "com/gregtechceu/gtceu/utils/GTUtil";
    private static final String HOOK = "com/moakiee/thunderbolt/compat/gto/ClientModifierKeys";
    private static final String[] METHODS = {"isShiftDown", "isCtrlDown", "isAltDown"};

    public static final class Probe {
        public static boolean client;
        public static int polls;
        public static boolean clientSide() { return client; }
        public static boolean poll() { polls++; return true; }
    }

    @Test
    void transformsAllKeysAndKeepsServerBranchFromLinkingClientHook() throws Exception {
        var engine = engine();
        var definitions = (Map<?, ?>) engine.invokeFunction("initializeCoreMod");
        var node = new ClassNode();
        new ClassReader(target()).accept(node, 0);
        for (var method : node.methods) {
            var definition = (Map<?, ?>) definitions.get("gto_modifier_" + method.name);
            var target = (Map<?, ?>) definition.get("target");
            assertEquals("METHOD", target.get("type"));
            assertEquals(TARGET.replace('/', '.'), target.get("class"));
            assertEquals(method.name, target.get("methodName"));
            assertEquals("()Z", target.get("methodDesc"));
            assertSame(method, engine.invokeMethod(definition, "transformer", method));
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(HOOK.replace('/', '.'))) {
                        var loaded = findLoadedClass(name);
                        if (loaded != null) return loaded;
                        // Client hook may not even be present on a dedicated server.
                        if (!Probe.client) throw new AssertionError("server resolved a client class");
                        byte[] hook = hook();
                        return defineClass(name, hook, 0, hook.length);
                    }
                    return super.loadClass(name, resolve);
                }
            }
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        };
        var utility = loader.define();
        Probe.client = false; Probe.polls = 0;
        for (String name : METHODS) assertEquals(false, utility.getMethod(name).invoke(null));
        assertEquals(0, Probe.polls);
        Probe.client = true;
        try {
            for (String name : METHODS) assertEquals(true, utility.getMethod(name).invoke(null));
            assertEquals(3, Probe.polls);
        } finally { Probe.client = false; }
    }

    @Test
    void rejectsChangedDescriptorsAndMissingClientQueries() throws Exception {
        var engine = engine();
        var definitions = (Map<?, ?>) engine.invokeFunction("initializeCoreMod");
        var definition = definitions.get("gto_modifier_isShiftDown");
        for (var method : new MethodNode[]{
                new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isShiftDown", "()I", null, null),
                new MethodNode(Opcodes.ACC_PUBLIC, "isShiftDown", "()Z", null, null),
                new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isShiftDown", "()Z", null, null)}) {
            assertThrows(Exception.class, () -> engine.invokeMethod(definition, "transformer", method));
        }
    }

    private static Invocable engine() throws Exception {
        var engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        try (var script = GtoModifierKeysTransformerTest.class.getResourceAsStream("/coremods/gto_modifier_keys.js")) {
            assertNotNull(script);
            engine.eval(new InputStreamReader(script, StandardCharsets.UTF_8));
        }
        return (Invocable) engine;
    }

    private static byte[] target() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        for (String name : METHODS) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Z", null, null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Probe.class), "clientSide", "()Z", false);
            var server = new Label(); method.visitJumpInsn(Opcodes.IFEQ, server);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/gui/screens/Screen", "unmappedClientQuery", "()Z", false);
            method.visitInsn(Opcodes.IRETURN); method.visitLabel(server);
            method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] hook() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOK, null, "java/lang/Object", null);
        for (String name : METHODS) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Z", null, null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Probe.class), "poll", "()Z", false);
            method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
}
