package me.rerere.rikkahub.assistantproxy;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Main-process session factory for the isolated compatibility probe. */
public final class ProxyVoiceSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new ProxyVoiceInteractionSession(this);
    }
}
