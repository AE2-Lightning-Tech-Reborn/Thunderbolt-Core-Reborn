package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.Invocable;
import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class GtoDataInitializationTransformerTest {
    private static final String TARGET = "com/gtocore/data/Data";
    private static final String EXECUTOR = "com/gtolib/utils/GTOUtils";
    private static final String HOOK = "com/moakiee/thunderbolt/compat/gto/GtoDataInitialization";

    public static final class Probe {
        public static boolean client;
        public static final AtomicInteger submissions = new AtomicInteger();
        public static final AtomicInteger tracks = new AtomicInteger();
        public static final AtomicInteger registrations = new AtomicInteger();
        public static boolean clientSide() { return client; }
        public static Runnable task() { return registrations::incrementAndGet; }
        public static void commonInit() { registrations.incrementAndGet(); }
        public static Runnable track(Runnable task) {
            tracks.incrementAndGet();
            return () -> { assertEquals(1, submissions.get()); task.run(); };
        }
        public static void asyncExecute(Runnable task) {
            assertEquals(1, tracks.get(), "tracking must precede submission");
            submissions.incrementAndGet();
            task.run();
        }
    }

    @Test
    void tracksClientTaskBeforeOriginalSubmissionAndLeavesServerPathSynchronous() throws Exception {
        var engine = engine();
        var definition = definition(engine);
        var target = (Map<?, ?>) definition.get("target");
        assertEquals("METHOD", target.get("type"));
        assertEquals(TARGET.replace('/', '.'), target.get("class"));
        assertEquals("init", target.get("methodName"));
        assertEquals("()V", target.get("methodDesc"));
        var node = new ClassNode();
        new ClassReader(target()).accept(node, 0);
        var method = node.methods.getFirst();
        engine.invokeMethod(definition, "transformer", method);
        int hooks = 0;
        int executors = 0;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(HOOK)) { hooks++; call.owner = Type.getInternalName(Probe.class); }
            if (call.owner.equals(EXECUTOR)) { executors++; call.owner = Type.getInternalName(Probe.class); }
        }
        assertEquals(1, hooks);
        assertEquals(1, executors, "keep the original asyncExecute invocation");
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        var data = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Probe.client = false;
        Probe.tracks.set(0); Probe.submissions.set(0); Probe.registrations.set(0);
        data.getMethod("init").invoke(null);
        assertEquals(0, Probe.tracks.get());
        assertEquals(0, Probe.submissions.get());
        assertEquals(1, Probe.registrations.get());
        Probe.client = true;
        try {
            data.getMethod("init").invoke(null);
            assertEquals(1, Probe.tracks.get());
            assertEquals(1, Probe.submissions.get());
            assertEquals(2, Probe.registrations.get());
        } finally { Probe.client = false; }
    }

    @Test
    void tracksRenamedGtoExecutorWithoutReplacingItsInvocation() throws Exception {
        var engine = engine();
        var definition = definition(engine);
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
        var call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/gtolib/renamed", "renamed",
                "(Ljava/lang/Runnable;)V", false);
        method.instructions.add(call);
        engine.invokeMethod(definition, "transformer", method);
        assertEquals(2, method.instructions.size());
        assertEquals(HOOK, ((MethodInsnNode) call.getPrevious()).owner);
        assertEquals("com/gtolib/renamed", call.owner);
        assertEquals("renamed", call.name);
    }

    @Test
    void rejectsMissingOrMultipleSubmissionsWithoutPartiallyTransforming() throws Exception {
        var engine = engine();
        var definition = definition(engine);
        for (int count : new int[]{0, 2}) {
            var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
            for (int i = 0; i < count; i++) method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EXECUTOR, "asyncExecute", "(Ljava/lang/Runnable;)V", false));
            assertThrows(Exception.class, () -> engine.invokeMethod(definition, "transformer", method));
            assertEquals(count, method.instructions.size());
        }
        var changed = new MethodNode(Opcodes.ACC_PUBLIC, "init", "()V", null, null);
        assertThrows(Exception.class, () -> engine.invokeMethod(definition, "transformer", changed));
    }

    private static Invocable engine() throws Exception {
        var engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        try (var script = GtoDataInitializationTransformerTest.class.getResourceAsStream("/coremods/gto_data_initialization.js")) {
            assertNotNull(script);
            engine.eval(new InputStreamReader(script, StandardCharsets.UTF_8));
        }
        return (Invocable) engine;
    }

    private static Map<?, ?> definition(Invocable engine) throws Exception {
        return (Map<?, ?>) ((Map<?, ?>) engine.invokeFunction("initializeCoreMod")).get("gto_data_initialization");
    }

    private static byte[] target() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
        String probe = Type.getInternalName(Probe.class);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, probe, "clientSide", "()Z", false);
        var server = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, server);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, probe, "task", "()Ljava/lang/Runnable;", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, EXECUTOR, "asyncExecute", "(Ljava/lang/Runnable;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(server);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, probe, "commonInit", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
}
