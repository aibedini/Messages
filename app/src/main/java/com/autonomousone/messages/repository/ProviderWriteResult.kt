package com.autonomousone.messages.repository

sealed interface SourceWriteResult {
    data class Success(val updatedRows: Int) : SourceWriteResult
    data object NotApplicable : SourceWriteResult
    data class Failure(val reason: String, val cause: Throwable? = null) : SourceWriteResult
}

data class MarkReadProviderResult(
    val sms: SourceWriteResult,
    val mms: SourceWriteResult
) {
    val hasFailure: Boolean
        get() = sms is SourceWriteResult.Failure || mms is SourceWriteResult.Failure
    val fullySuccessful: Boolean
        get() = !hasFailure
}
