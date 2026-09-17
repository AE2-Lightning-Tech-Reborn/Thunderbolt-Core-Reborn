package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.core.network.clientbound.CraftConfirmPlanPacket;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Append after the whole summary, including any other mod's summary extensions. */
@Mixin(value = CraftConfirmPlanPacket.class, remap = false)
public abstract class ExactCraftConfirmPacketMixin {
    @Unique
    private static final int THUNDERBOLT_EXACT_MAGIC = 0x54424531;

    @Inject(method = "write", at = @At("RETURN"))
    private void thunderbolt$writeExactReport(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        var packet = (CraftConfirmPlanPacket) (Object) this;
        var report = ExactPlanReports.get(packet.plan());
        if (report != null) {
            buffer.writeInt(THUNDERBOLT_EXACT_MAGIC);
            report.write(buffer);
        }
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private static void thunderbolt$readExactReport(RegistryFriendlyByteBuf buffer,
            CallbackInfoReturnable<CraftConfirmPlanPacket> cir) {
        if (buffer.readableBytes() >= Integer.BYTES
                && buffer.getInt(buffer.readerIndex()) == THUNDERBOLT_EXACT_MAGIC) {
            buffer.readInt();
            ExactPlanReports.attach(cir.getReturnValue().plan(), ExactPlanReport.read(buffer));
        }
    }
}
