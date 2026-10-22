package com.moakiee.thunderbolt.core.nbt;

/** Published by common config events; copying never touches the config or loader. */
public final class NbtCopyOptions {
    private static volatile boolean enabled;

    private NbtCopyOptions() {}

    public static boolean enabled() { return enabled; }

    public static void configure(boolean value) { enabled = value; }
}
