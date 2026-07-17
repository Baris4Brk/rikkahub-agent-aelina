package me.rerere.rikkahub.assistantproxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ProbeUiPolicyTest {
    @Test
    public void lockedDeviceOnlyOffersUnlockPrompt() {
        assertEquals(
                ProbeUiPolicy.Mode.UNLOCK_REQUIRED,
                ProbeUiPolicy.modeForDeviceLocked(true));
    }

    @Test
    public void unlockedDeviceShowsDataIsolatedProbe() {
        assertEquals(
                ProbeUiPolicy.Mode.DATA_ISOLATED_PROBE,
                ProbeUiPolicy.modeForDeviceLocked(false));
    }
}
