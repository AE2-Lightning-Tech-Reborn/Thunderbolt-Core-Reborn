package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.core.sync.packets.CraftConfirmPlanPacket;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 1.20.1 AE2 encodes this packet inside its {@code (summary)} constructor: packet id,
 * {@code summary.write(buf)}, then {@code configureWrite(buf)}. Appending after
 * {@code summary.write} therefore still lands after every other mod's summary extension,
 * mirroring the 1.21 write/decode tail injections.
 */
@Mixin(value = CraftConfirmPlanPacket.class, remap = false)
public abstract class ExactCraftConfirmPacketMixin {
    @Unique
    private static final int THUNDERBOLT_EXACT_MAGIC = 0x54424531;

    @Shadow @Final private CraftingPlanSummary plan;

    @WrapOperation(
            method = "<init>(Lappeng/menu/me/crafting/CraftingPlanSummary;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lappeng/core/sync/packets/CraftConfirmPlanPacket;configureWrite(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void thunderbolt$writeExactReport(
            CraftConfirmPlanPacket packet, FriendlyByteBuf buffer, Operation<Void> original) {
        var report = ExactPlanReports.get(plan);
        if (report != null) {
            buffer.writeInt(THUNDERBOLT_EXACT_MAGIC);
            report.write(buffer);
        }
        original.call(packet, buffer);
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
