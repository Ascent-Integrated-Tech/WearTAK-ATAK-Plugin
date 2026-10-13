package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsSessionGuardTest {
    @Test public void sendsRequireCompleteCurrentSnapshot() {
        SettingsSessionGuard session = new SettingsSessionGuard();
        assertFalse(session.canSend());
        assertTrue(session.acceptSnapshot(session.token(), false));
        assertFalse(session.canSend());
        assertTrue(session.acceptSnapshot(session.token(), true));
        assertTrue(session.canSend());
    }

    @Test public void deviceSwitchDisconnectAndModeSwitchRejectPreviousSnapshot() {
        SettingsSessionGuard session = new SettingsSessionGuard();
        for (int i = 0; i < 3; ++i) {
            long previous = session.token();
            session.acceptSnapshot(previous, true);
            session.clear();
            assertFalse(session.canSend());
            assertFalse(session.isCurrent(previous));
            assertFalse(session.acceptSnapshot(previous, true));
            assertFalse(session.canSend());
            assertTrue(session.acceptSnapshot(session.token(), true));
            assertTrue(session.canSend());
        }
    }

    @Test public void partialChangeDoesNotUnlockNewSessionOrLockLoadedSession() {
        SettingsSessionGuard session = new SettingsSessionGuard();
        session.acceptSnapshot(session.token(), false);
        assertFalse(session.canSend());
        session.acceptSnapshot(session.token(), true);
        session.acceptSnapshot(session.token(), false);
        assertTrue(session.canSend());
    }
}
