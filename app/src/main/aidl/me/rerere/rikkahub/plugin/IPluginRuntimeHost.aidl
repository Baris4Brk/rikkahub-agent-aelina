package me.rerere.rikkahub.plugin;

/** Host-side permission gate for one plugin invocation. */
interface IPluginRuntimeHost {
    String handleRpc(String requestJson);
}
