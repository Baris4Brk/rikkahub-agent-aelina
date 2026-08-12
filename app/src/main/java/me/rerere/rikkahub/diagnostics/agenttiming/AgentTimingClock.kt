package me.rerere.rikkahub.diagnostics.agenttiming

import android.os.SystemClock

fun interface AgentTimingClock {
    fun elapsedRealtimeNanos(): Long
}

/** Android's monotonic clock. Wall-clock changes cannot corrupt Agent Timing durations. */
object AndroidAgentTimingClock : AgentTimingClock {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
