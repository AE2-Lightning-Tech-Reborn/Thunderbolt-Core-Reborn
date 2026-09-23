package com.moakiee.thunderbolt.ae2.crafting;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import appeng.client.api.AEKeyRendering;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.network.chat.Component;

/** Shared by the native AE2 table and the Tianshu report. */
public final class ExactPlanPresentation {
    private ExactPlanPresentation() {}

    public static List<Component> describe(CraftingPlanSummaryEntry entry, boolean full) {
        var amounts = ExactPlanReports.amounts(entry);
        if (amounts == null) return null;
        List<Component> lines = full ? new ArrayList<>(AEKeyRendering.getTooltip(entry.getWhat()))
                : new ArrayList<>();
        long units = entry.getWhat().getAmountPerUnit();
        append(lines, GuiText.FromStorage, amounts.stored(), units, full);
        append(lines, GuiText.Missing, amounts.missing(), units, full);
        append(lines, GuiText.ToCraft, amounts.crafting(), units, full);
        return lines;
    }

    private static void append(List<Component> lines, GuiText label, BigInteger amount, long units, boolean full) {
        if (amount.signum() == 0) return;
        String text = full ? ExactAmountFormatter.full(amount, units) : ExactAmountFormatter.compact(amount, units);
        if (text.length() <= 72) {
            lines.add(label.text(text));
        } else {
            lines.add(label.text(""));
            for (int offset = 0; offset < text.length(); offset += 64) {
                lines.add(Component.literal(text.substring(offset, Math.min(text.length(), offset + 64))));
            }
        }
    }
}
