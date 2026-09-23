package com.moakiee.thunderbolt.api.crafting;

import java.util.Objects;

import net.minecraft.resources.Identifier;

/** Immutable metadata declared when a crafting algorithm is registered. */
public record CraftingPlanningEngineDescriptor(
        CraftingPlanningEngine engine,
        int algorithmPriority,
        boolean publicAlgorithm) {

    public CraftingPlanningEngineDescriptor {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(engine.id(), "engine.id()");
        Objects.requireNonNull(engine.getName(), "engine.getName()");
    }

    public Identifier id() {
        return engine.id();
    }
}
