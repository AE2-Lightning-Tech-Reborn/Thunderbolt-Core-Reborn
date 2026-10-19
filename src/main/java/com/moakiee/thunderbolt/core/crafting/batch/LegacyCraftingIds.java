package com.moakiee.thunderbolt.core.crafting.batch;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Stable identity bridge for AE2 12's string crafting-link IDs. */
public final class LegacyCraftingIds {
    private LegacyCraftingIds() {}
    public static UUID toUuid(String id) {
        try { return UUID.fromString(id); }
        catch (IllegalArgumentException legacy) { return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)); }
    }
}
