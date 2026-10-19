package com.moakiee.thunderbolt.mixin.compatfixture;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.moakiee.thunderbolt.gametest.CompatTests.GtlEntry;
import org.spongepowered.asm.mixin.*;
@Mixin(value = CraftingPlanSummaryEntry.class, remap = false)
public abstract class GtlEntryMixin implements GtlEntry {
    @Unique private long gtlcore$craftTimes;
    public long gtlcore$getCraftTimes() { return gtlcore$craftTimes; }
    public void gtlcore$setCraftTimes(long value) { gtlcore$craftTimes = value; }
}
