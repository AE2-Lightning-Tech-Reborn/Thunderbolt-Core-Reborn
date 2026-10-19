package com.moakiee.thunderbolt.core.channel;

/** Pending count is published by AE2 12's ChannelFinalizer after grid boot completes. */
public interface PendingChannelConnection {
    void thunderbolt$setPendingChannels(int channels);
}
