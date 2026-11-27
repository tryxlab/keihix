package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvTransactionParserTest {

    private val recorder = CsvTransactionRecorder()
    private val parser = CsvTransactionParser()

    @Test
    fun roundTripWithBomAndSpecialCharacters() {
        val tx1 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-15",
            amount = 1980,
            vendor = "Amazon, Co. \"Japan\"",
            category = "消耗品費",
            invoiceNumber = "T1234567890123",
            memo = "USB-Cケーブル, 2m"
        )
        val tx2 = ParsedTransaction(
            type = TransactionType.INCOME,
            date = "2026-10-31",
            amount = 50000,
            vendor = "note株式会社",
            category = "売上",
            fee = 5000,
            withholdingTax = 5105,
            memo = "記事売上"
        )

        val initialBytes = recorder.createInitialCsvBytes(tx1, "receipts/20261015_1980_Amazon.jpg")
        val updatedBytes = recorder.appendTransactionRow(initialBytes, tx2, "incomes/20261031_50000_note.pdf")

        val parsed = parser.parseBytes(updatedBytes)
        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals(TransactionType.EXPENSE, first.transaction.type)
        assertEquals("2026-10-15", first.transaction.date)
        assertEquals(1980L, first.transaction.amount)
        assertEquals("Amazon, Co. \"Japan\"", first.transaction.vendor)
        assertEquals("消耗品費", first.transaction.category)
        assertNull(first.transaction.fee)
        assertEquals("T1234567890123", first.transaction.invoiceNumber)
        assertEquals("USB-Cケーブル, 2m", first.transaction.memo)
        assertEquals("receipts/20261015_1980_Amazon.jpg", first.driveFilePath)

        val second = parsed[1]
        assertEquals(TransactionType.INCOME, second.transaction.type)
        assertEquals(50000L, second.transaction.amount)
        assertEquals(5000L, second.transaction.fee)
        assertEquals(5105L, second.transaction.withholdingTax)
    }

    @Test
    fun skipsBlankAndMalformedRows() {
        val csv = """
            date,type,amount,vendor,category,fee,withholding_tax,invoice_number,memo,drive_file_path
            
            invalid_row_only_two_cols,expense
            2026-11-01,expense,not_a_number,セブン,消耗品費,0,0,,,
            2026-11-02,expense,540,セブン-イレブン,会議費,0,0,,コーヒー,receipts/a.jpg
        """.trimIndent()

        val parsed = parser.parseText(csv)
        assertEquals(1, parsed.size)
        assertEquals("セブン-イレブン", parsed.first().transaction.vendor)
        assertEquals(540L, parsed.first().transaction.amount)
    }

    @Test
    fun buildCsvBytes_removesDeletedRowAndPreservesRemaining() {
        val tx1 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-12",
            amount = 5000,
            vendor = "ガソリンスタンド",
            category = "車両費"
        )
        val tx2 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-12",
            amount = 5000,
            vendor = "ガソリンスタンド",
            category = "車両費"
        )
        val tx3 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-11-01",
            amount = 110,
            vendor = "コンビニ",
            category = "消耗品費"
        )

        val initial = recorder.createInitialCsvBytes(tx1, "receipts/1.jpg")
            .let { recorder.appendTransactionRow(it, tx2, "receipts/1.jpg") }
            .let { recorder.appendTransactionRow(it, tx3, "receipts/2.jpg") }

        val parsed = parser.parseBytes(initial)
        assertEquals(3, parsed.size)

        // 2件目（重複行）を削除して再構築
        val remaining = parsed.filterNot { it.rowIndex == parsed[1].rowIndex }
        val rebuiltBytes = recorder.buildCsvBytes(remaining)
        val reparsed = parser.parseBytes(rebuiltBytes)

        assertEquals(2, reparsed.size)
        assertEquals("2026-10-12", reparsed[0].transaction.date)
        assertEquals(5000L, reparsed[0].transaction.amount)
        assertEquals("2026-11-01", reparsed[1].transaction.date)
        assertEquals(110L, reparsed[1].transaction.amount)
    }
}
