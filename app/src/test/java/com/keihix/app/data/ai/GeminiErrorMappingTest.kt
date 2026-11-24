package com.keihix.app.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class GeminiErrorMappingTest {

    @Test
    fun `503 UNAVAILABLE and GRpcError MissingFieldException maps to retryable high-demand message`() {
        val sdkException = RuntimeException(
            "Unexpected Response: {\"error\":{\"code\":503,\"message\":\"This model is currently experiencing high demand.\",\"status\":\"UNAVAILABLE\"}}",
            IllegalStateException("Field 'details' is required for type with serial name 'com.google.ai.client.generativeai.common.server.GRpcError'")
        )

        val result = GeminiTransactionParser.mapExceptionToError(sdkException)

        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("503"))
        assertTrue(result.message.contains("再解析"))
    }

    @Test
    fun `429 quota error maps to retryable rate-limit message`() {
        val result = GeminiTransactionParser.mapExceptionToError(
            RuntimeException("429 RESOURCE_EXHAUSTED: quota exceeded")
        )

        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("レート制限"))
    }

    @Test
    fun `403 API_KEY_INVALID maps to non-retryable key error message`() {
        val result = GeminiTransactionParser.mapExceptionToError(
            RuntimeException("403 API_KEY_INVALID: API key not valid")
        )

        assertFalse(result.isRetryable)
        assertTrue(result.message.contains("API キー"))
    }

    @Test
    fun `UnknownHostException maps to retryable network error message`() {
        val result = GeminiTransactionParser.mapExceptionToError(
            UnknownHostException("generativelanguage.googleapis.com")
        )

        assertTrue(result.isRetryable)
        assertTrue(result.message.contains("ネットワーク"))
    }
}
