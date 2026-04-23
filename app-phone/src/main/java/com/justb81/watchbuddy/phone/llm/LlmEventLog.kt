package com.justb81.watchbuddy.phone.llm

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of recent on-device LLM invocations (recap + scrobble
 * title extraction). Feeds the Settings → Diagnostics → LLM Activity view so
 * the user can inspect what prompt the phone shipped to the local model and
 * what it got back — without attaching adb.
 *
 * Separate from [com.justb81.watchbuddy.core.logging.DiagnosticLog] on purpose:
 * prompts and responses are multi-KB, have stable IDs, and must not pollute
 * the shared breadcrumb ring that the share-diagnostics button exports.
 * [LlmProviderFactory] still emits terse `DiagnosticLog` breadcrumbs
 * ("llm id=… caller=… backend=… duration=… status=…") so the shared snapshot
 * keeps a trail of activity.
 */
@Singleton
class LlmEventLog @Inject constructor() {

    enum class Status { SUCCESS, EMPTY, ERROR }

    data class LlmEvent(
        val id: Long,
        val caller: String,
        val backend: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val prompt: String,
        val response: String?,
        val status: Status,
        val errorSummary: String?,
    )

    private val nextId = AtomicLong(1L)
    private val buffer = ArrayDeque<LlmEvent>(MAX_ENTRIES)
    private val lock = Any()

    private val _updates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits after every [record] / [clear] so the UI can refresh without polling. */
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    fun record(
        caller: String,
        backend: String,
        startedAtMs: Long,
        durationMs: Long,
        prompt: String,
        response: String?,
        status: Status,
        errorSummary: String? = null,
    ): LlmEvent {
        val event = LlmEvent(
            id = nextId.getAndIncrement(),
            caller = caller,
            backend = backend,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            prompt = prompt,
            response = response,
            status = status,
            errorSummary = errorSummary,
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
            buffer.addLast(event)
        }
        _updates.tryEmit(Unit)
        return event
    }

    /** Newest-first list, suitable for direct rendering. */
    fun snapshot(): List<LlmEvent> = synchronized(lock) { buffer.toList().asReversed() }

    fun findById(id: Long): LlmEvent? = synchronized(lock) { buffer.firstOrNull { it.id == id } }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _updates.tryEmit(Unit)
    }

    private companion object {
        const val MAX_ENTRIES = 100
    }
}
