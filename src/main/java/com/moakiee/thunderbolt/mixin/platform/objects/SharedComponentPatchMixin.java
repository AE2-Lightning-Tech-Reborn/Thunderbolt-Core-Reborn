package com.moakiee.thunderbolt.mixin.platform.objects;

import java.util.Optional;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moakiee.thunderbolt.core.keys.SharedComponentPatch;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;

@Mixin(PatchedDataComponentMap.class)
abstract class SharedComponentPatchMixin implements SharedComponentPatch {
    @Shadow private Reference2ObjectMap<DataComponentType<?>, Optional<?>> patch;
    @Shadow private boolean copyOnWrite;
    @Shadow @Final private DataComponentMap prototype;
    @Unique private boolean thunderbolt$repeatedCopy;

    @WrapMethod(method = "copy")
    private PatchedDataComponentMap thunderbolt$observeRepeatedCopy(Operation<PatchedDataComponentMap> original) {
        boolean alreadyShared = copyOnWrite;
        var result = original.call();
        ((SharedComponentPatch) (Object) result).thunderbolt$markRepeatedCopy(alreadyShared);
        return result;
    }

    @Override
    public void thunderbolt$markRepeatedCopy(boolean repeated) {
        thunderbolt$repeatedCopy = repeated;
    }

    @Override
    public Reference2ObjectMap<DataComponentType<?>, Optional<?>> thunderbolt$componentPatchView() {
        // Unlike asPatch(), a transient query must not promote a writable patch to a COW snapshot.
        return patch;
    }

    @Override
    public void thunderbolt$shareComponentPatch() { copyOnWrite = true; }

    @Override
    public Object thunderbolt$prototypeIdentity() { return prototype; }

    @Override
    public Object thunderbolt$copyOnWritePatchIdentity() {
        // Query only: this does not admit a first-use patch into the construction cache.
        return copyOnWrite ? patch : null;
    }

    @Override
    public Object thunderbolt$sharedPatchIdentity() {
        // Native stack.copy() first marks both maps COW. set/remove/applyPatch detach the map.
        // An addon-provided writable map does not qualify for snapshot-identity caching.
        // A first copy of a fresh patch is often a one-off key. Avoid identity hashing/cache
        // publication until the native copy path has observed an already-shared snapshot.
        return copyOnWrite && thunderbolt$repeatedCopy ? patch : null;
    }
}
