package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExactAmountCodecTest {
    @Test
    void roundTripDoesNotTruncateAtMachineIntegerBoundaries() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (BigInteger value : new BigInteger[] {BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE),
                    BigInteger.ONE.shiftLeft(63), BigInteger.TEN.pow(150).add(BigInteger.valueOf(123))}) {
                ExactPlanReport.writeAmount(buffer, value);
                assertEquals(value, ExactPlanReport.readAmount(buffer));
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNegativeAndOversizeNetworkAmounts() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByteArray(new byte[] {-1});
            assertThrows(IllegalArgumentException.class, () -> ExactPlanReport.readAmount(buffer));
            buffer.clear();
            buffer.writeVarInt(ExactPlanReport.MAX_AMOUNT_BYTES + 1);
            assertThrows(RuntimeException.class, () -> ExactPlanReport.readAmount(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void fullDisplayPreservesEveryDigitAndFluidFraction() {
        BigInteger value = new BigInteger("123456789012345678901234567890001");
        assertEquals("123,456,789,012,345,678,901,234,567.890001", ExactAmountFormatter.full(value, 1000000));
        assertEquals("123.5E+24", ExactAmountFormatter.compact(value, 1000000));
        assertEquals(Long.MAX_VALUE, ExactPlanReport.project(value));
        BigInteger thousandDigits = BigInteger.TEN.pow(1000).add(BigInteger.ONE);
        assertEquals(thousandDigits.toString(), ExactAmountFormatter.full(thousandDigits, 1).replace(",", ""));
    }
}
