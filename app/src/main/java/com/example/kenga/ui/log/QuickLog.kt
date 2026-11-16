package com.example.kenga.ui.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quick-log entry point (§7.3): a simple process-wide state holder other
 * screens use to ask the Log tab to open the weights dialog pre-selected —
 * the Body screen's region dialog (Phase 8) and the video detail's
 * "Log set" action (Phase 10).
 *
 * The LogViewModel collects [request] and opens [WeightsLogDialog] with the
 * pre-selection, then clears the request.
 */
data class QuickLogRequest(
    val muscleGroupId: String? = null,
    val exerciseName: String? = null,
)

object QuickLog {
    private val _request = MutableStateFlow<QuickLogRequest?>(null)

    /** Latest un-handled request; null when idle. */
    val request: StateFlow<QuickLogRequest?> = _request.asStateFlow()

    /** Fire a quick-log request from any screen. Duplicate-consecutive guard. */
    fun open(request: QuickLogRequest) {
        if (_request.value == request) return
        _request.value = request
    }

    /** Called by the Log tab once the request has been consumed. */
    fun consume() {
        _request.value = null
    }
}
