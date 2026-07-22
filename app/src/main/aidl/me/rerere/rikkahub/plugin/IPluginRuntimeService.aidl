package me.rerere.rikkahub.plugin;

import me.rerere.rikkahub.plugin.IPluginRuntimeHost;

/** Short-lived WebView invocation service hosted in the :plugin_runtime process. */
interface IPluginRuntimeService {
    String invoke(String requestJson, IPluginRuntimeHost host);
    void cancel(String invocationId);
}
