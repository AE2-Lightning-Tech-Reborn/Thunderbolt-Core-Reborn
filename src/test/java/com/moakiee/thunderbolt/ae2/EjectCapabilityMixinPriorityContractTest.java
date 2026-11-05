package com.moakiee.thunderbolt.mixin.platform.eject;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.junit.jupiter.api.Test;

final class EjectCapabilityMixinPriorityContractTest {
    @Test
    void thunderboltEjectOverrideTargetsOnlyBlockEntitiesAndKeepsSuperFallback() throws Exception {
        var mixin = read("com.moakiee.thunderbolt.mixin.platform.eject.EjectCapabilityMixin");
        var annotation = mixin.invisibleAnnotations.stream()
                .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                .findFirst().orElseThrow();
        assertEquals(List.of(Type.getObjectType("net/minecraft/world/level/block/entity/BlockEntity")),
                value(annotation, "value"));
        assertEquals(2000, value(annotation, "priority"));
        assertEquals(Boolean.FALSE, value(annotation, "remap"));
        assertEquals("net/minecraftforge/common/capabilities/CapabilityProvider", mixin.superName);

        var method = mixin.methods.stream().filter(candidate -> candidate.name.equals("getCapability")
                && candidate.desc.equals("(Lnet/minecraftforge/common/capabilities/Capability;"
                        + "Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;"))
                .findFirst().orElseThrow();
        assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0);
        int fallbacks = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(mixin.superName) && call.name.equals("getCapability")) {
                assertFalse(call.itf);
                assertEquals(method.desc, call.desc);
                fallbacks++;
            }
        }
        assertEquals(1, fallbacks);
    }

    @Test
    void noConfiguredThunderboltMixinTargetsTheSharedCapabilityProvider() throws Exception {
        try (var resource = resource("thunderbolt.mixins.json")) {
            var config = JsonParser.parseString(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (String group : List.of("mixins", "client", "server")) {
                if (!config.has(group)) continue;
                for (var name : config.getAsJsonArray(group)) {
                    String className = config.get("package").getAsString() + "." + name.getAsString();
                    var mixin = read(className);
                    if (mixin.invisibleAnnotations == null) continue;
                    for (var annotation : mixin.invisibleAnnotations) {
                        if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                        var targets = value(annotation, "value");
                        if (targets instanceof List<?> values) {
                            assertFalse(values.contains(Type.getObjectType(
                                    "net/minecraftforge/common/capabilities/CapabilityProvider")), className);
                        }
                        var named = value(annotation, "targets");
                        if (named instanceof List<?> values) {
                            assertFalse(values.contains("net.minecraftforge.common.capabilities.CapabilityProvider"), className);
                        }
                    }
                }
            }
        }
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var resource = resource(name.replace('.', '/') + ".class")) {
            var type = new ClassNode();
            new ClassReader(resource).accept(type, 0);
            return type;
        }
    }

    private static InputStream resource(String name) {
        return java.util.Objects.requireNonNull(EjectCapabilityMixinPriorityContractTest.class
                .getClassLoader().getResourceAsStream(name), name);
    }
}
