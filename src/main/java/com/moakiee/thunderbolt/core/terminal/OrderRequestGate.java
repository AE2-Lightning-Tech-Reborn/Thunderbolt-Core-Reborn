package com.moakiee.thunderbolt.core.terminal;

/** A server-issued menu nonce plus a strictly increasing, positive request sequence. */
public final class OrderRequestGate {
    private long lastRequest;

    public boolean accept(String expectedSession, String session, long request) {
        if (expectedSession == null || expectedSession.isEmpty()
                || !expectedSession.equals(session) || request <= lastRequest) return false;
        lastRequest = request;
        return true;
    }
}
