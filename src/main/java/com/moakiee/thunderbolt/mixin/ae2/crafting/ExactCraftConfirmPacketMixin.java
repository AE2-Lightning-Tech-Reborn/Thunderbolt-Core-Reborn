package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.core.sync.packets.CraftConfirmPlanPacket;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Append after the whole summary, including other mods' summary extensions. */
@Mixin(value = CraftConfirmPlanPacket.class, remap = false)
public abstract class ExactCraftConfirmPacketMixin {
    @Unique private static final int THUNDERBOLT_EXACT_MAGIC = 0x54424531;
    @Shadow @Final private CraftingPlanSummary plan;

    @WrapOperation(method = "<init>(Lappeng/menu/me/crafting/CraftingPlanSummary;)V",
            at = @At(value = "INVOKE", target = "Lappeng/menu/me/crafting/CraftingPlanSummary;write(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void thunderbolt$writeExactReport(CraftingPlanSummary summary, FriendlyByteBuf buffer,
            Operation<Void> original) {
        original.call(summary, buffer);
        var report = ExactPlanReports.get(summary);
        if (report != null) {
            buffer.writeInt(THUNDERBOLT_EXACT_MAGIC);
            report.write(buffer);
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void thunderbolt$readExactReport(FriendlyByteBuf buffer, CallbackInfo ci) {
        if (buffer.readableBytes() >= Integer.BYTES
                && buffer.getInt(buffer.readerIndex()) == THUNDERBOLT_EXACT_MAGIC) {
            buffer.readInt();
            ExactPlanReports.attach(plan, ExactPlanReport.read(buffer));
        }
    }
}
