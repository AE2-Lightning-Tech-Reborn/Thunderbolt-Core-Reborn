package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.world.level.ItemLike;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

class GtoChemicalCacheSynchronizationTest {
    private static final String HELPER = "com/gregtechceu/gtceu/api/data/chemical/ChemicalHelper";
    private static final String ENTRY = "com/gregtechceu/gtceu/api/data/chemical/material/stack/MaterialEntry";

    public static final class Probe {
        static final AtomicInteger active = new AtomicInteger();
        static final AtomicInteger maximum = new AtomicInteger();
        static volatile boolean fail;

        public static void enter() {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                LockSupport.parkNanos(100_000);
                if (fail) throw new IllegalStateException("lookup failure");
            } finally { active.decrementAndGet(); }
        }
    }

    @Test
    void transformedLookupSerializesParallelCacheMissesAndReleasesLockOnException() throws Exception {
        var node = new ClassNode();
        new ClassReader(helper()).accept(node, 0);
        var engine = new javax.script.ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine, "Forge's coremod JavaScript engine must be available");
        try (var script = getClass().getResourceAsStream("/coremods/gto_material_cache.js")) {
            assertNotNull(script);
            engine.eval(new java.io.InputStreamReader(script, java.nio.charset.StandardCharsets.UTF_8));
        }
        var invocable = (javax.script.Invocable) engine;
        var definitions = (java.util.Map<?, ?>) invocable.invokeFunction("initializeCoreMod");
        var definition = (java.util.Map<?, ?>) definitions.get("gto_material_cache");
        var target = (java.util.Map<?, ?>) definition.get("target");
        assertEquals("METHOD", target.get("type"));
        assertEquals(HELPER.replace('/', '.'), target.get("class"));
        assertEquals("getMaterialEntry", target.get("methodName"));
        assertEquals(node.methods.get(0).desc, target.get("methodDesc"));
        assertSame(node.methods.get(0), invocable.invokeMethod(definition, "transformer", node.methods.get(0)));
        var writer = new ClassWriter(0);
        node.accept(writer);
        Class<?> helper = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                var stub = new ClassWriter(0);
                stub.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ENTRY, null, "java/lang/Object", null);
                stub.visitEnd();
                byte[] bytes = stub.toByteArray();
                defineClass(null, bytes, 0, bytes.length);
                bytes = writer.toByteArray();
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        var lookup = helper.getMethod("getMaterialEntry", ItemLike.class);
        Probe.active.set(0);
        Probe.maximum.set(0);
        Probe.fail = true;
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> lookup.invoke(null, new Object[] { null }));
        Probe.fail = false;
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) jobs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 40; i++) lookup.invoke(null, new Object[] { null });
                return null;
            }));
            start.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1, Probe.maximum.get());
        assertEquals(0, Probe.active.get());
    }

    private static byte[] helper() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getMaterialEntry",
                "(Lnet/minecraft/world/level/ItemLike;)L" + ENTRY + ";", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Probe.class), "enter", "()V", false);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
