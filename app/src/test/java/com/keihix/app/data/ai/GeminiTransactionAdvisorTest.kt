package com.keihix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTransactionAdvisorTest {

    @Test
    fun parseDeleteActionTag_singleRowId() {
        val raw = """
            重複している10月12日の取引を削除対象として提案します。
            下の削除ボタンから確認のうえ台帳を更新できます。
            [[DELETE_ROWS:2]]
        """.trimIndent()

        val (cleanText, ids) = GeminiTransactionAdvisor.parseDeleteActionTag(raw)

        assertEquals(listOf(2), ids)
        assertTrue(!cleanText.contains("DELETE_ROWS"))
        assertTrue(cleanText.endsWith("下の削除ボタンから確認のうえ台帳を更新できます。"))
    }

    @Test
    fun parseDeleteActionTag_multipleRowIdsWithSpacesAndDuplicates() {
        val raw = """
            2件の削除を提案します。
            [[DELETE_ROWS: 2, 5, 2 ]]
        """.trimIndent()

        val (cleanText, ids) = GeminiTransactionAdvisor.parseDeleteActionTag(raw)

        assertEquals(listOf(2, 5), ids)
        assertEquals("2件の削除を提案します。", cleanText)
    }

    @Test
    fun parseDeleteActionTag_noTag() {
        val raw = "今月の経費合計は ¥5,000 です。"

        val (cleanText, ids) = GeminiTransactionAdvisor.parseDeleteActionTag(raw)

        assertTrue(ids.isEmpty())
        assertEquals(raw, cleanText)
    }

    @Test
    fun isServiceUnavailableError_detects503AndHighDemand() {
        assertTrue(
            GeminiTransactionAdvisor.isServiceUnavailableError(
                RuntimeException("503 UNAVAILABLE: This model is currently experiencing high demand.")
            )
        )
        assertTrue(
            GeminiTransactionAdvisor.isServiceUnavailableError(
                RuntimeException("wrapped", IllegalStateException("Server overloaded"))
            )
        )
        assertTrue(
            !GeminiTransactionAdvisor.isServiceUnavailableError(
                RuntimeException("401 API_KEY_INVALID")
            )
        )
    }
}
