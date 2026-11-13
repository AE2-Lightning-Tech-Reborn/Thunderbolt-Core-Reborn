package com.moakiee.thunderbolt.integration.jei;

import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Probe bytecode without linking GTLCore, JEI or any client class during discovery. */
public final class GtlJeiOrderCapability {
    private GtlJeiOrderCapability() {}

    public static boolean supports(InputStream classBytes) throws IOException {
        var node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return supports(node);
    }

    public static boolean supports(ClassNode node) {
        boolean event = node.methods.stream().anyMatch(m -> m.name.equals("onJeiWirelessOrderMousePressed")
                && m.desc.equals("(Lnet/minecraftforge/client/event/ScreenEvent$MouseButtonPressed$Pre;)V")
                && (m.access & Opcodes.ACC_STATIC) != 0);
        if (!event) return false;
        var lambda = node.methods.stream().filter(m -> m.name.equals("lambda$onJeiWirelessOrderMousePressed$1")
                && m.desc.equals("(Lnet/minecraftforge/client/event/ScreenEvent$MouseButtonPressed$Pre;Lappeng/api/stacks/AEKey;)V")
                && (m.access & Opcodes.ACC_STATIC) != 0).findFirst();
        if (lambda.isEmpty()) return false;
        int sends = 0;
        for (var instruction : lambda.get().instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("org/gtlcore/gtlcore/integration/ae2/wireless/JeiWirelessTerminalOrderPackets")
                    && call.name.equals("sendRequest") && call.desc.equals("(Lappeng/api/stacks/AEKey;)V")) sends++;
        }
        return sends == 1;
    }
}
