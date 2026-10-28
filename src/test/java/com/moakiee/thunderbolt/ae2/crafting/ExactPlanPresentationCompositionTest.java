package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Items;

import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

class ExactPlanPresentationCompositionTest {
    static { MinecraftTestBootstrap.ensureInitialized(); }

    @Test
    void replacesProjectedAmountLinesAndKeepsAddonHints() {
        var wide = BigInteger.TEN.pow(30).add(BigInteger.valueOf(123));
        var entry = entry(wide);
        var hint = Component.translatable("gtlcore.ae.craft.stock_taken");
        var lines = new ArrayList<Component>();
        for (var line : List.of(GuiText.FromStorage.text("projected"), hint)) {
            if (!ExactPlanPresentation.appendExactAmount(entry, line, false, lines::add)) lines.add(line);
        }
        assertEquals(2, lines.size());
        assertSame(hint, lines.get(1));
        var contents = (TranslatableContents) lines.get(0).getContents();
        assertEquals(ExactAmountFormatter.compact(wide, 1), contents.getArgs()[0]);
    }

    @Test
    void fullTooltipSplitsWideAmountsWithoutLosingDigitsOrOtherLines() {
        var wide = BigInteger.TEN.pow(150).add(BigInteger.valueOf(12345));
        var entry = entry(wide);
        var lines = new ArrayList<Component>();
        var itemTooltip = Component.literal("item tooltip");
        lines.add(itemTooltip);
        assertTrue(ExactPlanPresentation.appendExactAmount(
                entry, GuiText.FromStorage.text("projected"), true, lines::add));
        assertSame(itemTooltip, lines.get(0));
        String digits = lines.subList(2, lines.size()).stream().map(Component::getString)
                .collect(java.util.stream.Collectors.joining()).replace(",", "");
        assertEquals(wide.toString(), digits);
        assertTrue(lines.subList(2, lines.size()).stream().allMatch(line -> line.getString().length() <= 64));
    }

    @Test
    void ordinaryRowsRetainTheirOriginalFormatting() {
        var entry = new CraftingPlanSummaryEntry(AEItemKey.of(Items.STONE), 0, 12, 0);
        assertFalse(ExactPlanPresentation.appendExactAmount(entry, GuiText.FromStorage.text("12"), false,
                ignored -> fail("ordinary rows must invoke the original operation")));
    }

    private static CraftingPlanSummaryEntry entry(BigInteger stored) {
        var key = AEItemKey.of(Items.STONE);
        var entry = new CraftingPlanSummaryEntry(key, 0, Long.MAX_VALUE, 0);
        ExactPlanReports.attach(new CraftingPlanSummary(1, true, List.of(entry)),
                new ExactPlanReport(BigInteger.ONE, Map.of(key,
                        new ExactPlanReport.Amounts(stored, BigInteger.ZERO, BigInteger.ZERO)), false));
        return entry;
    }
}
