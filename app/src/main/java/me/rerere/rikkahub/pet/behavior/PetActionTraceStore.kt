package me.rerere.rikkahub.pet.behavior

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory, redacted diagnostic trace. It intentionally never outlives the app process. */
class PetActionTraceStore(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val lock = Any()
    private val entries = ArrayDeque<PetActionTrace>(maxEntries)
    private val _traces = MutableStateFlow<List<PetActionTrace>>(emptyList())
    val traces: StateFlow<List<PetActionTrace>> = _traces.asStateFlow()

    fun append(trace: PetActionTrace) {
        synchronized(lock) {
            while (entries.size >= maxEntries) entries.removeFirst()
            entries.addLast(trace)
            _traces.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _traces.value = emptyList()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
