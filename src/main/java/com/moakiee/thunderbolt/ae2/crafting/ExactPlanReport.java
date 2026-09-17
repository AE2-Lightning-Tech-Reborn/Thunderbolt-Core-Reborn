package com.moakiee.thunderbolt.ae2.crafting;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Display data for a non-executable exact calculation. All quantities are in AEKey base units. */
public record ExactPlanReport(BigInteger bytes, Map<AEKey, Amounts> entries, boolean incomplete) {
    public static final int MAX_AMOUNT_BYTES = 4097;
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_PACKET_BYTES = 512 * 1024;

    public ExactPlanReport {
        positive(bytes);
        entries = Map.copyOf(entries);
    }

    public record Amounts(BigInteger stored, BigInteger missing, BigInteger crafting) {
        public Amounts {
            positive(stored);
            positive(missing);
            positive(crafting);
        }
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        writeAmount(buffer, bytes);
        buffer.writeBoolean(incomplete);
        buffer.writeVarInt(entries.size());
        int start = buffer.writerIndex();
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many exact report entries");
        for (var entry : entries.entrySet()) {
            AEKey.writeKey(buffer, entry.getKey());
            writeAmount(buffer, entry.getValue().stored());
            writeAmount(buffer, entry.getValue().missing());
            writeAmount(buffer, entry.getValue().crafting());
            if (buffer.writerIndex() - start > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Exact report exceeds packet budget");
            }
        }
    }

    public static ExactPlanReport read(RegistryFriendlyByteBuf buffer) {
        BigInteger bytes = readAmount(buffer);
        boolean incomplete = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid exact entry count");
        int start = buffer.readerIndex();
        Map<AEKey, Amounts> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            AEKey key = Objects.requireNonNull(AEKey.readKey(buffer), "report key");
            var amounts = new Amounts(readAmount(buffer), readAmount(buffer), readAmount(buffer));
            if (entries.put(key, amounts) != null) throw new IllegalArgumentException("Duplicate exact report key");
            if (buffer.readerIndex() - start > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Exact report exceeds packet budget");
            }
        }
        return new ExactPlanReport(bytes, entries, incomplete);
    }

    public static long project(BigInteger amount) {
        positive(amount);
        return amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    public static void writeAmount(net.minecraft.network.FriendlyByteBuf buffer, BigInteger amount) {
        positive(amount);
        buffer.writeByteArray(amount.toByteArray());
    }

    public static BigInteger readAmount(net.minecraft.network.FriendlyByteBuf buffer) {
        byte[] encoded = buffer.readByteArray(MAX_AMOUNT_BYTES);
        if (encoded.length == 0) throw new IllegalArgumentException("Empty exact amount");
        BigInteger amount = new BigInteger(encoded);
        positive(amount);
        return amount;
    }

    private static void positive(BigInteger amount) {
        if (amount == null || amount.signum() < 0 || amount.bitLength() > 32768) {
            throw new IllegalArgumentException("Invalid exact crafting amount");
        }
    }
}
