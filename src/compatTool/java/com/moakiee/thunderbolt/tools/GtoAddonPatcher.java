package com.moakiee.thunderbolt.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Offline migration of the published addons' boxed AE2 plan API to AE2-GTO 15.2610.8.
 * Produces separate jars; never changes AE2, Minecraft mappings, or the input archive.
 */
public final class GtoAddonPatcher {
    private static final String PLAN = "appeng/crafting/CraftingPlan";
    private static final String PLAN_API = "appeng/api/networking/crafting/ICraftingPlan";
    private static final String MAP = "Ljava/util/Map;";
    private static final String LONG_MAP = "Lit/unimi/dsi/fastutil/objects/Object2LongMap;";
    private static final String CONSTRUCTOR = "(Lappeng/api/stacks/GenericStack;JZZ"
            + "Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;"
            + "Lappeng/api/stacks/KeyCounter;Ljava/util/Map;)V";
    private static final String CONVERTER = "thunderbolt$gtoPatternTimes";
    private static final String APPLIEDE = "76a863b993a77c6939c34f78385b92cdb08312701220552c06865924aaf0021b";
    private static final Set<String> APPLIEDE_TEMPORARY_PATTERN_MIXINS = Set.of(
            "crafting.CraftConfirmMenuMixin", "crafting.CraftingCPULogicMixin", "crafting.CraftingTreeNodeMixin");
    // These migrations are deliberately tied to audited published artifacts.
    private static final Map<String, Integer> INPUTS = Map.of(
            "e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2", 3,
            "0996cd736e5a951593f94125c059710800056de8def77995deb7ba6b50099367", 1,
            APPLIEDE, 2);

    private GtoAddonPatcher() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java -jar gto-addon-patcher.jar input.jar output.jar");
        patch(Path.of(args[0]), Path.of(args[1]));
    }

    public static void patch(Path input, Path output) throws Exception {
        if (input.toRealPath().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Output must differ from input");
        }
        if (Files.exists(output)) throw new IOException("Output already exists: " + output);
        String inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        Integer expectedChanges = INPUTS.get(inputHash);
        if (expectedChanges == null) throw new IllegalArgumentException("Unaudited addon SHA-256: " + inputHash);
        int changed = 0;
        int removed = 0;
        int resourcesChanged = 0;
        try (var source = new JarFile(input.toFile())) {
            // Reject signed archives rather than producing an invalid signature.
            if (source.stream().anyMatch(entry -> entry.getName().matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))) {
                throw new IllegalArgumentException("Signed addon archives are not supported");
            }
            var bytes = new java.io.ByteArrayOutputStream();
            try (var target = new JarOutputStream(bytes)) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    byte[] original;
                    try (var stream = source.getInputStream(entry)) { original = stream.readAllBytes(); }
                    if (inputHash.equals(APPLIEDE)) {
                        // Keep KnowledgeService's permanent one-item and tier-conversion patterns.
                        // Only the obsolete request-sized temporary-pattern lifecycle is removed:
                        // it mutates the grid from vanilla planner internals that GTO no longer has.
                        if (APPLIEDE_TEMPORARY_PATTERN_MIXINS.stream().anyMatch(mixin -> entry.getName().equals(
                                "gripe/_90/appliede/mixin/" + mixin.replace('.', '/') + ".class"))) {
                            removed++;
                            continue;
                        }
                        if (entry.getName().equals("appliede.mixins.json")) {
                            String config = new String(original, StandardCharsets.UTF_8);
                            for (String mixin : APPLIEDE_TEMPORARY_PATTERN_MIXINS) {
                                String token = "    \"" + mixin + "\",\n";
                                if (!config.contains(token)) throw new IllegalArgumentException("Unexpected AppliedE mixin config");
                                config = config.replace(token, "");
                            }
                            original = config.getBytes(StandardCharsets.UTF_8);
                            resourcesChanged++;
                        }
                        if (entry.getName().equals("META-INF/mods.toml")) {
                            String metadata = new String(original, StandardCharsets.UTF_8)
                                    .replace("[[dependencies]]", "[[dependencies.appliede]]");
                            metadata += "\n[[dependencies.appliede]]\nmodId = \"thunderbolt\"\nmandatory = true\n"
                                    + "versionRange = \"[2.0.3-gto.1,)\"\nordering = \"AFTER\"\nside = \"BOTH\"\n"
                                    + "\n[[dependencies.appliede]]\nmodId = \"gtocore\"\nmandatory = true\n"
                                    + "versionRange = \"[26.10.5]\"\nordering = \"AFTER\"\nside = \"BOTH\"\n";
                            original = metadata.getBytes(StandardCharsets.UTF_8);
                            resourcesChanged++;
                        }
                    }
                    byte[] migrated = entry.getName().endsWith(".class") ? migrate(original) : original;
                    if (migrated != original) changed++;
                    var copy = new JarEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    target.putNextEntry(copy);
                    target.write(migrated);
                    target.closeEntry();
                }
                if (changed != expectedChanges) throw new IllegalArgumentException("Unexpected migration count: " + changed);
                if (removed != (inputHash.equals(APPLIEDE) ? 3 : 0)
                        || resourcesChanged != (inputHash.equals(APPLIEDE) ? 2 : 0)) {
                    throw new IllegalArgumentException("Unexpected resource migration count");
                }
                target.putNextEntry(new JarEntry("META-INF/thunderbolt-gto-migration.txt"));
                target.write(("AE2-GTO 15.2610.8\nInput SHA-256: "
                        + inputHash
                        + "\nChanged classes: " + changed
                        + (inputHash.equals(APPLIEDE) ? "\nRemoved obsolete classes: " + removed
                        + "\nAppliedE: permanent unit/tier patterns; no temporary patterns."
                        + "\nRequires Thunderbolt V2 planning; the GTO native planner does not support this pattern type.\n" : "\n"))
                        .getBytes(StandardCharsets.UTF_8));
                target.closeEntry();
            }
            if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
            Files.write(output, bytes.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        System.out.println(output + ": migrated " + changed + " classes, removed " + removed + " obsolete classes");
        if (inputHash.equals(APPLIEDE)) System.out.println("AppliedE: select Thunderbolt V2 on the network's algorithm provider.");
    }

    public static byte[] migrate(byte[] original) {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        boolean changed = false;
        boolean converter = false;
        if (node.name.equals("gripe/_90/appliede/me/misc/TransmutationPattern")
                && !node.interfaces.contains("com/gtolib/api/ae2/pattern/IDetails")) {
            // Implement GTO's public CPU input-holder contract. Planning these custom recipes
            // still requires Thunderbolt V2: GTO's native operator builder rejects their type.
            node.interfaces.add("com/gtolib/api/ae2/pattern/IDetails");
            String counter = "appeng/api/stacks/KeyCounter";
            String holders = "[L" + counter + ";";
            String field = "thunderbolt$gtoInputs";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, field, holders, null, null));
            for (var constructor : node.methods) if (constructor.name.equals("<init>")) {
                for (var instruction : constructor.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                    var init = new InsnList();
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getInputs",
                            "()[Lappeng/api/crafting/IPatternDetails$IInput;", false));
                    init.add(new InsnNode(Opcodes.ARRAYLENGTH));
                    init.add(new TypeInsnNode(Opcodes.ANEWARRAY, counter));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, field, holders));
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, holders));
                    init.add(new InvokeDynamicInsnNode("apply", "()Ljava/util/function/IntFunction;",
                            new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                                    "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                            org.objectweb.asm.Type.getMethodType("(I)Ljava/lang/Object;"),
                            new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, node.name,
                                    "thunderbolt$newInputCounter", "(I)L" + counter + ";", false),
                            org.objectweb.asm.Type.getMethodType("(I)L" + counter + ";")));
                    init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "setAll",
                            "([Ljava/lang/Object;Ljava/util/function/IntFunction;)V", false));
                    constructor.instructions.insertBefore(instruction, init);
                }
            }
            var factory = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "thunderbolt$newInputCounter", "(I)L" + counter + ";", null, null);
            factory.instructions.add(new TypeInsnNode(Opcodes.NEW, counter));
            factory.instructions.add(new InsnNode(Opcodes.DUP));
            factory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, counter, "<init>", "()V", false));
            factory.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(factory);
            var getter = new MethodNode(Opcodes.ACC_PUBLIC, "gtolib$getInputHolder", "()" + holders, null, null);
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, holders));
            getter.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(getter);
            var recipe = new MethodNode(Opcodes.ACC_PUBLIC, "getRecipe",
                    "()Lcom/gregtechceu/gtceu/api/recipe/GTRecipeDefinition;", null, null);
            recipe.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            recipe.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(recipe);
            changed = true;
        }
        boolean craftingServiceMixin = node.name.equals("net/pedroksl/advanced_ae/mixins/cpu/MixinCraftingService");
        if (craftingServiceMixin) {
            for (var field : node.fields) {
                if (field.name.equals("currentlyCrafting") && field.desc.equals("Ljava/util/Set;")) {
                    field.desc = "Lappeng/api/stacks/AEKeySet;";
                    field.signature = "Lappeng/api/stacks/AEKeySet<Lappeng/api/stacks/AEKey;>;";
                    changed = true;
                }
            }
        }
        for (var method : node.methods) {
            if (node.name.equals("gripe/_90/appliede/mixin/tooltip/MEStorageMenuMixin")
                    && method.name.equals("replacePacket")) {
                changed |= useTypedLocals(method, 1, 2);
            }
            if (node.name.equals("net/pedroksl/advanced_ae/mixins/cpu/MixinCraftingService")) {
                int[] parameters = switch (method.name) {
                    case "tickAdvClusters1", "onGetCpus" -> new int[] { 1 };
                    case "onSubmitJob" -> new int[] { 6, 7 };
                    case "onGetRequestedAmount" -> new int[] { 2 };
                    default -> new int[0];
                };
                if (parameters.length > 0) {
                    // GTO's transformed service no longer supports vanilla LocalCapture.
                    // MixinExtras resolves these typed locals at the actual injection site.
                    for (int parameter : parameters) {
                        if (method.invisibleParameterAnnotations != null
                                && method.invisibleParameterAnnotations[parameter] != null
                                && method.invisibleParameterAnnotations[parameter].stream().anyMatch(annotation ->
                                annotation.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"))) continue;
                        var local = method.visitParameterAnnotation(parameter,
                                "Lcom/llamalad7/mixinextras/sugar/Local;", false);
                        local.visit("ordinal", 0);
                        local.visitEnd();
                        changed = true;
                    }
                    if (method.visibleAnnotations != null) {
                        for (var annotation : method.visibleAnnotations) {
                            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                            for (int i = annotation.values.size() - 2; i >= 0; i -= 2) {
                                if (annotation.values.get(i).equals("locals")) {
                                    annotation.values.remove(i + 1);
                                    annotation.values.remove(i);
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
            for (var instruction : method.instructions.toArray()) {
                if (craftingServiceMixin && instruction instanceof FieldInsnNode field
                        && field.owner.equals(node.name) && field.name.equals("currentlyCrafting")
                        && field.desc.equals("Ljava/util/Set;")) {
                    field.desc = "Lappeng/api/stacks/AEKeySet;";
                    changed = true;
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if ((call.owner.equals(PLAN) || call.owner.equals(PLAN_API))
                        && call.name.equals("patternTimes") && call.desc.equals("()" + MAP)) {
                    call.desc = "()" + LONG_MAP;
                    changed = true;
                } else if (call.owner.equals(PLAN) && call.name.equals("<init>") && call.desc.equals(CONSTRUCTOR)) {
                    // The old caller may supply HashMap or Map.of; a cast is insufficient.
                    method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            node.name, CONVERTER, "(" + MAP + ")" + LONG_MAP, false));
                    call.desc = CONSTRUCTOR.replace(MAP, LONG_MAP);
                    converter = true;
                    changed = true;
                }
            }
        }
        if (!changed) return original;
        if (converter) {
            if (node.methods.stream().anyMatch(method -> method.name.equals(CONVERTER))) {
                throw new IllegalArgumentException("Converter name collision in " + node.name);
            }
            var helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    CONVERTER, "(" + MAP + ")" + LONG_MAP, null, null);
            String implementation = "it/unimi/dsi/fastutil/objects/Object2LongOpenHashMap";
            helper.instructions.add(new TypeInsnNode(Opcodes.NEW, implementation));
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, implementation, "<init>", "(" + MAP + ")V", false));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(helper);
        }
        // No control flow or local slots changed; preserve existing frames and recompute stack sizes.
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean useTypedLocals(MethodNode method, int... parameters) {
        boolean changed = false;
        for (int parameter : parameters) {
            if (method.invisibleParameterAnnotations != null
                    && method.invisibleParameterAnnotations[parameter] != null
                    && method.invisibleParameterAnnotations[parameter].stream().anyMatch(annotation ->
                    annotation.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;"))) continue;
            var local = method.visitParameterAnnotation(parameter, "Lcom/llamalad7/mixinextras/sugar/Local;", false);
            local.visit("ordinal", 0);
            local.visitEnd();
            changed = true;
        }
        if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
            for (int i = annotation.values.size() - 2; i >= 0; i -= 2) {
                if (annotation.values.get(i).equals("locals")) {
                    annotation.values.remove(i + 1);
                    annotation.values.remove(i);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
