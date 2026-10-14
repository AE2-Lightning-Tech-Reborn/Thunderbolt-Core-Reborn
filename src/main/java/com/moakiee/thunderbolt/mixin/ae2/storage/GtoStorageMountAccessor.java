package com.moakiee.thunderbolt.mixin.ae2.storage;

import appeng.api.storage.MEStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "appeng.me.storage.NetworkStorage$MountOperation", remap = false)
public interface GtoStorageMountAccessor {
    @Accessor("priority") int thunderbolt$priority();
    @Accessor("storage") MEStorage thunderbolt$storage();
}
