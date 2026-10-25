package com.keihix.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionDataTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testExpenseJsonParsing() {
        val expenseJson = """
            {
              "type": "expense",
              "date": "2026-10-25",
              "amount": 2980,
              "vendor": "Amazon Japan G.K.",
              "category": "消耗品費",
              "fee": null,
              "withholding_tax": null,
              "invoice_number": "T1234567890123",
              "memo": "USB-C ハブ",
              "confidence_note": null
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ParsedTransaction>(expenseJson)

        assertEquals(TransactionType.EXPENSE, parsed.type)
        assertEquals("2026-10-25", parsed.date)
        assertEquals(2980L, parsed.amount)
        assertEquals("Amazon Japan G.K.", parsed.vendor)
        assertEquals("消耗品費", parsed.category)
        assertNull(parsed.fee)
        assertNull(parsed.withholdingTax)
        assertEquals("T1234567890123", parsed.invoiceNumber)
        assertEquals("USB-C ハブ", parsed.memo)
    }

    @Test
    fun testIncomeJsonParsing() {
        val incomeJson = """
            {
              "type": "income",
              "date": "2026-10-31",
              "amount": 50000,
              "vendor": "note株式会社",
              "category": "売上",
              "fee": 5000,
              "withholding_tax": 4595,
              "invoice_number": null,
              "memo": "note 有料記事 10月度売上",
              "confidence_note": "振込手数料と源泉徴収税を分離して抽出しました"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ParsedTransaction>(incomeJson)

        assertEquals(TransactionType.INCOME, parsed.type)
        assertEquals("2026-10-31", parsed.date)
        assertEquals(50000L, parsed.amount)
        assertEquals("note株式会社", parsed.vendor)
        assertEquals("売上", parsed.category)
        assertEquals(5000L, parsed.fee)
        assertEquals(4595L, parsed.withholdingTax)
        assertEquals("note 有料記事 10月度売上", parsed.memo)
        assertEquals("振込手数料と源泉徴収税を分離して抽出しました", parsed.confidenceNote)
    }

    @Test
    fun testCompliantFileNameGeneration() {
        val transaction = ParsedTransaction(
            type = TransactionType.EXPENSE,
            date = "2026-10-25",
            amount = 1980L,
            vendor = "Seven Eleven / Store #1",
            category = "会議費"
        )

        val fileName = transaction.toCompliantFileName()
        // 特殊文字や空白が '_' にサニタイズされ、電帳法検索3要件の "YYYYMMDD_金額_取引先" 形式になっていること
        assertEquals("20261025_1980_Seven_Eleven___Store_#1", fileName)
    }

    @Test
    fun testCsvRowGeneration() {
        val transaction = ParsedTransaction(
            type = TransactionType.INCOME,
            date = "2026-10-31",
            amount = 50000L,
            vendor = "note, Inc.",
            category = "売上",
            fee = 5000L,
            withholdingTax = 4595L,
            memo = "10月度売上 \"特別賞\"",
            invoiceNumber = "T9876543210123"
        )

        val csvRow = transaction.toCsvRow("receipts/20261031_50000_note.pdf")
        
        // カンマやダブルクォートが正しくエスケープされていること
        val expected = "2026-10-31,income,50000,\"note, Inc.\",売上,5000,4595,T9876543210123,\"10月度売上 \"\"特別賞\"\"\",receipts/20261031_50000_note.pdf"
        assertEquals(expected, csvRow)
    }
}
