package com.moakiee.thunderbolt.compat.appliede;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.moakiee.thunderbolt.mixin.OptionalMixinSelector;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import appeng.api.crafting.IPatternDetails;

class AppliedEModuleBatchSupportTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void forgePatternNameIsAcceptedAndNewPlatformOrUnrelatedTypesAreDeclined() throws Exception {
        var output = new GenericStack(AEItemKey.of(Items.IRON_INGOT), 32);
        var forge = pattern("gripe._90.appliede.me.misc.TransmutationPattern", output);
        assertEquals(output, AppliedEModuleBatchSupport.primaryOutput(forge));
        assertNull(AppliedEModuleBatchSupport.primaryOutput(
                pattern("gripe._90.appliede.me.service.TransmutationPattern", output)));
        assertNull(AppliedEModuleBatchSupport.primaryOutput(pattern("test.UnrelatedPattern", output)));
        assertNull(AppliedEModuleBatchSupport.primaryOutput(null));
        var pending = new CountingQueue();
        assertEquals(0, AppliedEModuleBatchSupport.enqueue(
                AppliedEModuleBatchSupport.primaryOutput(forge), pending, 1_000_000));
        assertEquals(32_000_000, pending.getLong(output.what()));
    }

    // Controlled type-name fixture, not a replacement for the published binary-shape test.
    private static IPatternDetails pattern(String name, GenericStack output) throws Exception {
        String internal = name.replace('.', '/');
        String stack = "Lappeng/api/stacks/GenericStack;";
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object",
                new String[]{"appeng/api/crafting/IPatternDetails"});
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "output", stack, null, null).visitEnd();
        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + stack + ")V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, internal, "output", stack);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        var outputs = writer.visitMethod(Opcodes.ACC_PUBLIC, "getOutputs", "()[" + stack, null, null);
        outputs.visitCode(); outputs.visitInsn(Opcodes.ICONST_1);
        outputs.visitTypeInsn(Opcodes.ANEWARRAY, "appeng/api/stacks/GenericStack");
        outputs.visitInsn(Opcodes.DUP); outputs.visitInsn(Opcodes.ICONST_0);
        outputs.visitVarInsn(Opcodes.ALOAD, 0); outputs.visitFieldInsn(Opcodes.GETFIELD, internal, "output", stack);
        outputs.visitInsn(Opcodes.AASTORE); outputs.visitInsn(Opcodes.ARETURN);
        outputs.visitMaxs(0, 0); outputs.visitEnd(); writer.visitEnd();
        var bytes = writer.toByteArray();
        var loader = new ClassLoader(AppliedEModuleBatchSupportTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        };
        return (IPatternDetails) loader.define().getConstructor(GenericStack.class).newInstance(output);
    }

    @Test
    void billionCopyBatchUsesOneQueueWriteAndPreservesOtherOutputs() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var other = AEItemKey.of(Items.GOLD_INGOT);
        var pending = new CountingQueue();
        pending.put(item, 7L);
        pending.put(other, 9L);
        pending.writes = 0;

        assertEquals(0L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 32L), pending, 1_000_000_000L));
        assertEquals(32_000_000_007L, pending.getLong(item));
        assertEquals(9L, pending.getLong(other));
        assertEquals(1, pending.writes);
    }

    @Test
    void returnsUnacceptedCopiesBeforeOutputAdditionCanOverflow() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var output = new GenericStack(item, 8L);
        var pending = new CountingQueue();
        pending.put(item, Long.MAX_VALUE - 19L);
        pending.writes = 0;

        assertEquals(2L, AppliedEModuleBatchSupport.capacity(output, pending));
        assertEquals(98L, AppliedEModuleBatchSupport.enqueue(output, pending, 100L));
        assertEquals(Long.MAX_VALUE - 3L, pending.getLong(item));
        assertEquals(1, pending.writes);
        assertEquals(100L, AppliedEModuleBatchSupport.enqueue(output, pending, 100L));
        assertEquals(1, pending.writes);
    }

    @Test
    void largePerCopyOutputsAreMultipliedWithoutWrapping() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var pending = new CountingQueue();
        assertEquals(Long.MAX_VALUE - 1L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, Long.MAX_VALUE), pending, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, pending.getLong(item));
        assertEquals(1, pending.writes);
    }

    @Test
    void invalidOrEmptyOffersDoNotChangeTheQueue() {
        var item = AEItemKey.of(Items.IRON_INGOT);
        var pending = new CountingQueue();
        assertEquals(12L, AppliedEModuleBatchSupport.enqueue(null, pending, 12L));
        assertEquals(12L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 0L), pending, 12L));
        assertEquals(0L, AppliedEModuleBatchSupport.enqueue(
                new GenericStack(item, 1L), pending, 0L));
        assertEquals(0, pending.writes);
    }

    @Test
    void addonMixinIsEnabledOnlyWithAppliedE() {
        String mixin = "com.moakiee.thunderbolt.mixin.compat.appliede."
                + "AppliedETransmutationModuleBatchMixin";
        assertFalse(OptionalMixinSelector.shouldApply(mixin, ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply(mixin, "ae2"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(mixin, "appliede"::equals));
    }

    private static final class CountingQueue extends Object2LongOpenHashMap<AEKey> {
        private int writes;

        @Override
        public long put(AEKey key, long amount) {
            writes++;
            return super.put(key, amount);
        }
    }
}
