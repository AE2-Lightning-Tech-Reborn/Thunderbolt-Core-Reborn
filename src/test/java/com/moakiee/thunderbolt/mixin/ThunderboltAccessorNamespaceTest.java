package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code thunderbolt$} namespace on the AE2 accessors GTLCore also generates.
 *
 * <p>GTLCore defines identical accessors on the same AE2 targets. Mixin 0.8.5 does not fail on that;
 * it removes the earlier definition and keeps the later one, so two addons claiming the same method
 * name resolve to whichever mixin the platform applies last. Renaming Thunderbolt's side removes the
 * ambiguity, and this test keeps it removed.
 */
class ThunderboltAccessorNamespaceTest {
    private static final String NAMESPACE = "thunderbolt$";

    /**
     * Member names GTLCore generates on these targets; none may be declared here again.
     *
     * <p>{@code org.gtlcore.gtlcore.mixin.ae2.logic.ExecutingCraftingJobAccessor} claims the four
     * {@code ExecutingCraftingJob} getters, {@code ExecutingCraftingJobTaskProgressAccessor} claims
     * the {@code TaskProgress} pair and {@code ElapsedTimeTrackerAccessor} claims both invokers.
     * {@code CraftingCpuLogicAccessor} is namespaced preventively: GTLCore currently shadows the
     * same members under other names, but Mixin 0.8.5 would silently merge a later same-name
     * accessor.
     */
    private static final Set<String> GTLCORE_CLAIMED = Set.of(
            "getTasks", "getWaitingFor", "getTimeTracker", "getLink",
            "getValue", "setValue",
            "invokeAddMaxItems", "invokeDecrementItems",
            "getJob", "invokeFinishJob", "invokePostChange");

    private static final Path AE2_MIXIN_SOURCES =
            Path.of("src", "main", "java", "com", "moakiee", "thunderbolt", "mixin", "ae2", "crafting");

    private static final Pattern GENERATED_MEMBER =
            Pattern.compile("@(?:Accessor|Invoker)\\s*(?:\\([^)]*\\))?");

    private static final Pattern METHOD_NAME = Pattern.compile("([\\w$]+)\\s*\\(");

    @Test
    void accessorsSharedWithGtlcOreKeepTheirNamespace() throws IOException {
        assertNamespaced("ExecutingCraftingJobAccessor.java", "thunderbolt$getTasks");
        assertNamespaced("ElapsedTimeTrackerAccessor.java", "thunderbolt$invokeAddMaxItems");
        assertNamespaced("TaskProgressAccessor.java", "thunderbolt$getValue");
        assertNamespaced("CraftingCpuLogicAccessor.java", "thunderbolt$getJob");
    }

    private static void assertNamespaced(String fileName, String expectedMember) throws IOException {
        Path source = AE2_MIXIN_SOURCES.resolve(fileName);
        assertTrue(Files.exists(source), () -> "accessor source is missing: " + source.toAbsolutePath());

        var members = generatedMemberNames(Files.readString(source, StandardCharsets.UTF_8));
        assertFalse(members.isEmpty(), () -> "no generated accessor members found in " + fileName);
        assertTrue(members.contains(expectedMember),
                () -> fileName + " must still declare " + expectedMember + " but declares " + members);
        for (var member : members) {
            assertFalse(GTLCORE_CLAIMED.contains(member),
                    () -> fileName + " declares " + member
                            + ", which GTLCore also generates on the same AE2 target; keep the "
                            + NAMESPACE + " prefix");
        }
    }

    /** Reads the interface method names of every {@code @Accessor}/{@code @Invoker} declaration. */
    private static List<String> generatedMemberNames(String source) {
        List<String> members = new ArrayList<>();
        Matcher annotation = GENERATED_MEMBER.matcher(source);
        while (annotation.find()) {
            // The declaration ends at the first ';'; inside it the identifier before '(' is the method.
            var declaration = source.substring(annotation.end());
            int end = declaration.indexOf(';');
            Matcher name = METHOD_NAME.matcher(end < 0 ? declaration : declaration.substring(0, end));
            if (name.find()) {
                members.add(name.group(1));
            }
        }
        return members;
    }
}
