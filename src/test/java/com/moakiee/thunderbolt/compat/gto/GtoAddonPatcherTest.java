package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.jar.JarFile;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.tools.GtoAddonPatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

class GtoAddonPatcherTest {
    @TempDir Path temporary;

    private static Path addon(String name) {
        return Path.of(System.getProperty("thunderbolt.optionalModShapeDir", "build/optional-mod-shapes"), name);
    }

    @Test
    void publishedAddonsMigrateWithoutChangingResourcesOrNestedLibraries() throws Exception {
        for (String name : new String[] { "advancedae-1.3.6-1.20.1.jar",
                "ae2-crafting-tree-1086241-7182165.jar" }) {
            var input = addon(name);
            var target = temporary.resolve(name);
            byte[] original = Files.readAllBytes(input);
            GtoAddonPatcher.patch(input, target);
            try (var before = new JarFile(input.toFile()); var after = new JarFile(target.toFile())) {
                for (var entry : before.stream().toList()) {
                    byte[] bytes = before.getInputStream(entry).readAllBytes();
                    byte[] migrated = after.getInputStream(after.getJarEntry(entry.getName())).readAllBytes();
                    if (!entry.getName().endsWith(".class")) assertArrayEquals(bytes, migrated, entry.getName());
                    if (entry.getName().endsWith(".class")) {
                        assertArrayEquals(migrated, GtoAddonPatcher.migrate(migrated));
                    }
                }
            }
            assertArrayEquals(original, Files.readAllBytes(input), "input archive is immutable");
            assertThrows(java.io.IOException.class, () -> GtoAddonPatcher.patch(input, target));
        }
    }

    @Test
    void unknownAddonIsRejectedBeforeCreatingOutput() throws Exception {
        var input = temporary.resolve("unknown.jar");
        Files.write(input, new byte[] { 1, 2, 3 });
        var target = temporary.resolve("output.jar");
        assertThrows(IllegalArgumentException.class, () -> GtoAddonPatcher.patch(input, target));
        assertFalse(Files.exists(target));
    }

    @Test
    void appliedERetainsPermanentPatternsAndEmcAccountingWithoutDeletedPlannerHooks() throws Exception {
        var input = addon("appliede-0.14.3.jar");
        var output = temporary.resolve("appliede-gto.jar");
        GtoAddonPatcher.patch(input, output);
        try (var before = new JarFile(input.toFile()); var after = new JarFile(output.toFile())) {
            String config = new String(after.getInputStream(after.getJarEntry("appliede.mixins.json")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String metadata = new String(after.getInputStream(after.getJarEntry("META-INF/mods.toml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(metadata.contains("[[dependencies]]"), "Forge dependencies need the mod ID table");
            assertTrue(metadata.contains("modId = \"thunderbolt\""));
            assertTrue(metadata.contains("modId = \"gtocore\""));
            byte[] patternBytes = after.getInputStream(after.getJarEntry(
                    "gripe/_90/appliede/me/misc/TransmutationPattern.class")).readAllBytes();
            assertArrayEquals(patternBytes, GtoAddonPatcher.migrate(patternBytes));
            var patternNode = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(patternBytes).accept(patternNode, 0);
            assertTrue(patternNode.interfaces.contains("com/gtolib/api/ae2/pattern/IDetails"));
            assertTrue(patternNode.methods.stream().anyMatch(method -> method.name.equals("gtolib$getInputHolder")));
            for (String mixin : new String[] { "CraftingTreeNodeMixin", "CraftingCPULogicMixin", "CraftConfirmMenuMixin" }) {
                assertFalse(config.contains(mixin));
                assertNull(after.getJarEntry("gripe/_90/appliede/mixin/crafting/" + mixin + ".class"));
            }
            byte[] menuBytes = after.getInputStream(after.getJarEntry(
                    "gripe/_90/appliede/mixin/tooltip/MEStorageMenuMixin.class")).readAllBytes();
            assertArrayEquals(menuBytes, GtoAddonPatcher.migrate(menuBytes), "typed-local migration is idempotent");
            var menu = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(menuBytes).accept(menu, 0);
            var callback = menu.methods.stream().filter(method -> method.name.equals("replacePacket")).findFirst().orElseThrow();
            for (int parameter : new int[] { 1, 2 }) {
                assertTrue(callback.invisibleParameterAnnotations[parameter].stream().anyMatch(annotation ->
                        annotation.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;")
                                && annotation.values.equals(java.util.List.of("ordinal", 0))));
            }
            for (var annotation : callback.visibleAnnotations) {
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                    assertFalse(annotation.values.contains("locals"), "no legacy positional local capture");
                }
            }
            // The unit/tier recipes, EMC withdrawal rules, module queue, knowledge and all other
            // features must remain byte-for-byte identical; this is a recipe lifecycle migration.
            for (var entry : before.stream().toList()) {
                if (entry.getName().startsWith("gripe/_90/appliede/mixin/crafting/")
                        || entry.getName().equals("gripe/_90/appliede/mixin/tooltip/MEStorageMenuMixin.class")
                        || entry.getName().equals("gripe/_90/appliede/me/misc/TransmutationPattern.class")
                        || entry.getName().equals("META-INF/mods.toml")
                        || entry.getName().equals("appliede.mixins.json")) continue;
                assertNotNull(after.getJarEntry(entry.getName()), entry.getName());
                assertArrayEquals(before.getInputStream(entry).readAllBytes(),
                        after.getInputStream(after.getJarEntry(entry.getName())).readAllBytes(), entry.getName());
            }
        }
    }

    @Test
    void legacyConstructorAndAccessorLinkAgainstRealGtoWithImmutableBoxedMap() throws Exception {
        byte[] original = legacyCaller();
        var pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IPatternDetails.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        byte[] transformed = GtoAddonPatcher.migrate(original);
        var migrated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, transformed, 0, transformed.length); }
        }.define();
        var counts = Map.of(pattern, Long.MAX_VALUE);
        var plan = (CraftingPlan) migrated.getMethod("create", GenericStack.class, KeyCounter.class, Map.class)
                .invoke(null, null, new KeyCounter(), counts);
        assertEquals(Long.MAX_VALUE, plan.patternTimes().getLong(pattern));
        assertEquals(counts, migrated.getMethod("read", ICraftingPlan.class).invoke(null, plan));
        assertArrayEquals(GtoAddonPatcher.migrate(original),
                GtoAddonPatcher.migrate(GtoAddonPatcher.migrate(original)), "migration is idempotent");
    }

    private static byte[] legacyCaller() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "qa/LegacyPlanCaller", null, "java/lang/Object", null);
        String stack = "Lappeng/api/stacks/GenericStack;";
        String counter = "Lappeng/api/stacks/KeyCounter;";
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(" + stack + counter + "Ljava/util/Map;)Lappeng/crafting/CraftingPlan;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "appeng/crafting/CraftingPlan");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.LCONST_1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        for (int i = 0; i < 3; i++) method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "appeng/crafting/CraftingPlan", "<init>",
                "(" + stack + "JZZ" + counter.repeat(3) + "Ljava/util/Map;)V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read",
                "(Lappeng/api/networking/crafting/ICraftingPlan;)Ljava/util/Map;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "appeng/api/networking/crafting/ICraftingPlan",
                "patternTimes", "()Ljava/util/Map;", true);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
