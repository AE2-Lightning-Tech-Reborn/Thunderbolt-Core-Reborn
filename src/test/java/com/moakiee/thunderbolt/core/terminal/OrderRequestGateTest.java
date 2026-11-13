package com.moakiee.thunderbolt.core.terminal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderRequestGateTest {
    @Test void rejectsReplayAndOutOfOrderRequestsWithoutBlockingTheNextClick() {
        var gate = new OrderRequestGate();
        String session = UUID.randomUUID().toString();
        assertTrue(gate.accept(session, session, 1));
        assertFalse(gate.accept(session, session, 1));
        assertTrue(gate.accept(session, session, 3));
        assertFalse(gate.accept(session, session, 2));
        assertTrue(gate.accept(session, session, 4));
    }

    @Test void staleMenuNonceCannotClaimAReusedContainerId() {
        var gate = new OrderRequestGate();
        String oldSession = UUID.randomUUID().toString();
        String currentSession = UUID.randomUUID().toString();
        assertFalse(gate.accept(currentSession, oldSession, Long.MAX_VALUE));
        assertTrue(gate.accept(currentSession, currentSession, 1));
    }

    @Test void rejectsUnsynchronizedAndNonPositiveSequences() {
        var gate = new OrderRequestGate();
        assertFalse(gate.accept("", "", 1));
        assertFalse(gate.accept(null, null, 1));
        assertFalse(gate.accept("session", "session", 0));
        assertFalse(gate.accept("session", "session", -1));
        assertFalse(gate.accept("session", null, 1));
        assertTrue(gate.accept("session", "session", 1));
    }

    @Test void independentMenusAndPlayersDoNotShareReplayState() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        var first = new OrderRequestGate();
        var second = new OrderRequestGate();
        assertTrue(first.accept(a, a, 50));
        assertTrue(second.accept(b, b, 1));
        assertFalse(second.accept(b, a, 51));
        assertTrue(second.accept(b, b, 2));
    }
}
