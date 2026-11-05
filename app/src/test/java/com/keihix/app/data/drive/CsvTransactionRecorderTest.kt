package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CsvTransactionRecorderTest {

    private val recorder = CsvTransactionRecorder()

    @Test
    fun testCreateInitialCsvBytes_WithNoInitialTransaction() {
        val bytes = recorder.createInitialCsvBytes()
        assertTrue(recorder.hasUtf8Bom(bytes))

        val text = String(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
        assertEquals(ParsedTransaction.CSV_HEADER + "\r\n", text)
    }

    @Test
    fun testCreateInitialCsvBytes_WithInitialTransaction() {
        val tx = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-25",
            amount = 1980L,
            vendor = "Amazon Japan",
            category = "消耗品費"
        )
        val bytes = recorder.createInitialCsvBytes(tx, "receipts/20261025_1980_Amazon.jpg")
        assertTrue(recorder.hasUtf8Bom(bytes))

        val text = String(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(ParsedTransaction.CSV_HEADER, lines[0])
        assertEquals("2026-10-25,expense,1980,Amazon Japan,消耗品費,0,0,,,receipts/20261025_1980_Amazon.jpg", lines[1])
    }

    @Test
    fun testAppendTransactionRow() {
        val initialBytes = recorder.createInitialCsvBytes()

        val tx = ParsedTransaction(
            type = TransactionType.INCOME,
            date = "2026-10-31",
            amount = 50000L,
            vendor = "note株式会社",
            category = "売上",
            fee = 5000L,
            withholdingTax = 4595L
        )

        val updatedBytes = recorder.appendTransactionRow(
            initialBytes,
            tx,
            "incomes/20261031_50000_note株式会社.pdf"
        )

        assertTrue(recorder.hasUtf8Bom(updatedBytes))
        val text = String(updatedBytes.copyOfRange(3, updatedBytes.size), StandardCharsets.UTF_8)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }

        assertEquals(2, lines.size)
        assertEquals(ParsedTransaction.CSV_HEADER, lines[0])
        assertEquals("2026-10-31,income,50000,note株式会社,売上,5000,4595,,,incomes/20261031_50000_note株式会社.pdf", lines[1])
    }
}
