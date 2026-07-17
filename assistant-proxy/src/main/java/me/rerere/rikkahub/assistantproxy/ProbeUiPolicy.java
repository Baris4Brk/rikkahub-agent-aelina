package me.rerere.rikkahub.assistantproxy;

/** Fail-closed UI policy for the standalone MagicOS voice-interaction tracer. */
public final class ProbeUiPolicy {
    public enum Mode {
        UNLOCK_REQUIRED,
        DATA_ISOLATED_PROBE,
    }

    private ProbeUiPolicy() {}

    public static Mode modeForDeviceLocked(boolean deviceLocked) {
        return deviceLocked ? Mode.UNLOCK_REQUIRED : Mode.DATA_ISOLATED_PROBE;
    }
}
