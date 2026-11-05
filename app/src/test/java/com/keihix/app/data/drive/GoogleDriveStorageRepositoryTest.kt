package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class GoogleDriveStorageRepositoryTest {

    private val csvRecorder = CsvTransactionRecorder()

    @Test
    fun testCsvReadModifyWrite_MultipleTransactions() {
        // 1. 初回生成（1件目の経費）
        val tx1 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-25",
            amount = 1980L,
            vendor = "Amazon Japan",
            category = "消耗品費",
            memo = "USBハブ"
        )
        val initialBytes = csvRecorder.createInitialCsvBytes(tx1, "receipts/20261025_1980_Amazon_Japan.jpg")

        // 2. 2件目の追記（売上）
        val tx2 = ParsedTransaction(
            type = TransactionType.INCOME,
            date = "2026-10-31",
            amount = 50000L,
            vendor = "note株式会社",
            category = "売上",
            fee = 5000L,
            withholdingTax = 4595L,
            memo = "10月度売上"
        )
        val secondBytes = csvRecorder.appendTransactionRow(initialBytes, tx2, "incomes/20261031_50000_note株式会社.pdf")

        // 3. 3件目の追記（交通費）
        val tx3 = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-11-01",
            amount = 1240L,
            vendor = "JR九州",
            category = "旅費交通費"
        )
        val thirdBytes = csvRecorder.appendTransactionRow(secondBytes, tx3, "receipts/20261101_1240_JR九州.jpg")

        // 検証
        assertTrue(csvRecorder.hasUtf8Bom(thirdBytes))
        val text = String(thirdBytes.copyOfRange(3, thirdBytes.size), StandardCharsets.UTF_8)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }

        assertEquals(4, lines.size) // ヘッダー + 3レコード
        assertEquals(ParsedTransaction.CSV_HEADER, lines[0])
        assertEquals("2026-10-25,expense,1980,Amazon Japan,消耗品費,0,0,,USBハブ,receipts/20261025_1980_Amazon_Japan.jpg", lines[1])
        assertEquals("2026-10-31,income,50000,note株式会社,売上,5000,4595,,10月度売上,incomes/20261031_50000_note株式会社.pdf", lines[2])
        assertEquals("2026-11-01,expense,1240,JR九州,旅費交通費,0,0,,,receipts/20261101_1240_JR九州.jpg", lines[3])
    }
}
