package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWorkerTest {

    @Test
    fun `buildInputData and transactionFromInputData round-trip income transaction`() {
        val original = ParsedTransaction(
            type = TransactionType.INCOME,
            date = "2026-11-24",
            amount = 50000L,
            vendor = "note株式会社",
            category = "売上",
            fee = 5000L,
            withholdingTax = 4595L,
            invoiceNumber = "T1234567890123",
            memo = "記事売上",
            confidenceNote = null
        )

        val data = UploadWorker.buildInputData(
            year = 2026,
            localFilePath = "/data/user/0/com.keihix.app/files/pending_uploads/sample.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            transaction = original
        )

        val restored = UploadWorker.transactionFromInputData(data)
        assertNotNull(restored)
        assertEquals(original.type, restored!!.type)
        assertEquals(original.date, restored.date)
        assertEquals(original.amount, restored.amount)
        assertEquals(original.vendor, restored.vendor)
        assertEquals(original.category, restored.category)
        assertEquals(original.fee, restored.fee)
        assertEquals(original.withholdingTax, restored.withholdingTax)
        assertEquals(original.invoiceNumber, restored.invoiceNumber)
        assertEquals(original.memo, restored.memo)
        assertNull(restored.confidenceNote)
    }

    @Test
    fun `isRetryableError distinguishes network errors from auth errors`() {
        assertTrue(UploadWorker.isRetryableError("SocketTimeoutException: timeout"))
        assertTrue(UploadWorker.isRetryableError("503 Service Unavailable"))
        assertFalse(UploadWorker.isRetryableError("Google アカウントと連携されていません"))
        assertFalse(UploadWorker.isRetryableError("Google Drive の認証に失敗しました（OAuth / SHA-1）"))
        assertFalse(UploadWorker.isRetryableError("401 Unauthorized"))
    }
}
