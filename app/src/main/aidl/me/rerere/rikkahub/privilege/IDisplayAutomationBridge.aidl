package me.rerere.rikkahub.privilege;

/** Dedicated fixed-operation Binder for managed virtual displays. */
interface IDisplayAutomationBridge {
    String createDisplay();
    String displayStatus(int displayId);
    String sendKey(int displayId, int keyCode);
    String closeDisplay(int displayId);
    String closeAllDisplays();
}
