package me.rerere.rikkahub.assistantproxy;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Explicit framework compatibility target; it is intentionally not discoverable. */
public final class ProxyNoOpRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        reject(listener);
    }

    @Override
    protected void onCancel(Callback listener) {
        // No recognition work is ever started.
    }

    @Override
    protected void onStopListening(Callback listener) {
        reject(listener);
    }

    private static void reject(Callback listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {
            // The remote listener disappeared; there is no local work to clean up.
        }
    }
}
