package com.justb81.watchbuddy.phone.ui.diagnostics

import com.justb81.watchbuddy.phone.llm.LlmEventLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point that [LlmEventDetailScreen] uses to resolve the singleton
 * [LlmEventLog] from inside a composable — bypasses the Compose ViewModel
 * factory path entirely. See [LlmEventDetailScreen] for the consumer side.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LlmEventDetailEntryPoint {
    fun llmEventLog(): LlmEventLog
}
