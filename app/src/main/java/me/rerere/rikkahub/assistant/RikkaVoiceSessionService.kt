package me.rerere.rikkahub.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Main-process factory for the UI session shown by the Android assistant framework. */
class RikkaVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        RikkaVoiceInteractionSession(this)
}
