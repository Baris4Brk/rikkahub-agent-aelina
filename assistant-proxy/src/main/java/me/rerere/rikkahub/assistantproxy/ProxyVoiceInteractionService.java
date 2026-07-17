package me.rerere.rikkahub.assistantproxy;

import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

/** Lightweight whitelist tracer; it never loads or contacts the RikkaHub application. */
public final class ProxyVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        setDisabledShowContext(
                VoiceInteractionSession.SHOW_WITH_ASSIST
                        | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
