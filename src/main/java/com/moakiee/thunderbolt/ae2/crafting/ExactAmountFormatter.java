package com.moakiee.thunderbolt.ae2.crafting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Formats exact base-unit amounts without a double conversion, including fluid unit scaling. */
public final class ExactAmountFormatter {
    private static final String[] PREFIXES = {"", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};

    private ExactAmountFormatter() {}

    public static String compact(BigInteger amount, long units) {
        BigDecimal scaled = new BigDecimal(amount).divide(BigDecimal.valueOf(Math.max(1, units)),
                new MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros();
        int group = Math.max(0, (scaled.precision() - scaled.scale() - 1) / 3);
        if (group == 0) return scaled.toPlainString();
        // Continue beyond quetta with KQ, MQ, ... QQ, KQQ, etc.
        String suffix = PREFIXES[group % 10] + "Q".repeat(group / 10);
        return scaled.movePointLeft(group * 3).toPlainString() + suffix;
    }

    public static String full(BigInteger amount, long units) {
        BigDecimal scaled;
        try {
            scaled = new BigDecimal(amount).divide(BigDecimal.valueOf(Math.max(1, units)));
        } catch (ArithmeticException repeating) {
            // Custom key types need not use decimal units. A rational representation remains exact.
            return amount + "/" + units;
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMaximumFractionDigits(Math.max(0, scaled.scale()));
        return format.format(scaled);
    }
}
