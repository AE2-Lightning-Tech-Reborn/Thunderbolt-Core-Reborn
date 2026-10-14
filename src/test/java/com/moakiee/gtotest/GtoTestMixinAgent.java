package com.moakiee.gtotest;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Applies the shipped AE2-GTO identity mixins in plain JUnit, which has no ModLauncher.
 * Reads the actual dependency bytecode; never rewrites or packages the upstream JAR.
 * Full mod loading and injection conflicts are tested separately in the real Forge pack.
 */
public final class GtoTestMixinAgent {
    private static final Map<String, String> TARGETS = Map.of(
        "net/minecraft/world/item/Item", "appeng/mixins/ItemMixin",
        "net/minecraft/world/level/material/Fluid", "appeng/mixins/FluidMixin",
        "net/minecraft/world/level/block/Block", "appeng/mixins/BlockMixin");

    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                // Forge normally synthesizes this event constructor during ModLauncher loading.
                if (name.startsWith("net/minecraftforge/network/NetworkEvent")) {
                    var node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    if (node.methods.stream().noneMatch(m -> m.name.equals("<init>") && m.desc.equals("()V"))) {
                        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
                        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
                        node.methods.add(ctor);
                        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        node.accept(writer);
                        return writer.toByteArray();
                    }
                }
                String mixin = TARGETS.get(name);
                if (mixin == null) return null;
                try (var input = loader.getResourceAsStream(mixin + ".class")) {
                    if (input == null) throw new IllegalStateException("Missing " + mixin);
                    var donor = new ClassNode();
                    new ClassReader(input).accept(new ClassRemapper(donor,
                            new SimpleRemapper(mixin, name)), 0);
                    var target = new ClassNode();
                    new ClassReader(bytes).accept(target, 0);
                    target.interfaces.addAll(donor.interfaces);
                    target.fields.addAll(donor.fields);
                    for (var method : donor.methods) {
                        if (!method.name.equals("<init>") && !method.name.equals("onConstructed")) {
                            target.methods.add(method);
                        }
                    }
                    for (var method : target.methods) {
                        if (!method.name.equals("<init>")) continue;
                        for (var instruction : method.instructions.toArray()) {
                            if (instruction.getOpcode() != Opcodes.RETURN) continue;
                            var uid = new InsnList();
                            uid.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            uid.add(new FieldInsnNode(Opcodes.GETSTATIC, "appeng/hooks/IUnique", "ID", "Ljava/util/concurrent/atomic/AtomicInteger;"));
                            uid.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicInteger", "getAndIncrement", "()I", false));
                            uid.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "ae2$uid", "I"));
                            method.instructions.insertBefore(instruction, uid);
                        }
                    }
                    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    target.accept(writer);
                    return writer.toByteArray();
                } catch (Exception failure) {
                    throw new AssertionError("GTO test mixin failed: " + name, failure);
                }
            }
        });
    }
}
