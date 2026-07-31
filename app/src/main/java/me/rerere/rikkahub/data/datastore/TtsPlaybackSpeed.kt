package me.rerere.rikkahub.data.datastore

import kotlin.math.roundToInt

const val MIN_TTS_PLAYBACK_SPEED = 0.5f
const val MAX_TTS_PLAYBACK_SPEED = 2.0f

fun Float.normalizedTtsPlaybackSpeed(): Float {
    if (!isFinite()) return 1.0f
    return (coerceIn(MIN_TTS_PLAYBACK_SPEED, MAX_TTS_PLAYBACK_SPEED) * 10f).roundToInt() / 10f
}
