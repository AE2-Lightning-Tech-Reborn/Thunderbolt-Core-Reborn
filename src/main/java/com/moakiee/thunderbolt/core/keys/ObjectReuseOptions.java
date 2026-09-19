package com.moakiee.thunderbolt.core.keys;

/** Hot-path flags published after the common config loads; no config/loader calls inside factories. */
public final class ObjectReuseOptions {
    public static volatile boolean cacheHashes;
    public static volatile boolean fastNbtCopies;
    private ObjectReuseOptions() {}
}
