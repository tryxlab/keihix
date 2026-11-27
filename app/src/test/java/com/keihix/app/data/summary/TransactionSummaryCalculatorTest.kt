package com.keihix.app.data.summary

import com.keihix.app.data.drive.RecordedTransaction
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSummaryCalculatorTest {

    @Test
    fun calculatesTotalsCategoriesAndMonthlyBreakdown() {
        val records = listOf(
            RecordedTransaction(
                rowIndex = 1,
                transaction = ParsedTransaction(
                    type = TransactionType.EXPENSE,
                    date = "2026-01-10",
                    amount = 3000,
                    vendor = "Amazon",
                    category = "消耗品費"
                ),
                driveFilePath = "receipts/1.jpg"
            ),
            RecordedTransaction(
                rowIndex = 2,
                transaction = ParsedTransaction(
                    type = TransactionType.EXPENSE,
                    date = "2026-01-20",
                    amount = 1000,
                    vendor = "AWS",
                    category = "通信費"
                ),
                driveFilePath = "receipts/2.pdf"
            ),
            RecordedTransaction(
                rowIndex = 3,
                transaction = ParsedTransaction(
                    type = TransactionType.INCOME,
                    date = "2026-02-28",
                    amount = 10000,
                    vendor = "note",
                    category = "売上",
                    fee = 1000,
                    withholdingTax = 1021
                ),
                driveFilePath = "incomes/3.pdf"
            )
        )

        val summary = TransactionSummaryCalculator.calculate(2026, records)

        assertEquals(2026, summary.year)
        assertEquals(10000L, summary.totalIncome)
        assertEquals(4000L, summary.totalExpense)
        assertEquals(6000L, summary.netBalance)
        assertEquals(1000L, summary.totalFee)
        assertEquals(1021L, summary.totalWithholdingTax)
        assertEquals(3, summary.transactionCount)

        // 科目別は金額降順
        assertEquals(2, summary.expenseByCategory.size)
        assertEquals("消耗品費", summary.expenseByCategory[0].category)
        assertEquals(3000L, summary.expenseByCategory[0].totalAmount)
        assertEquals(0.75f, summary.expenseByCategory[0].ratio, 0.001f)

        // 月別推移（1月と2月）
        assertEquals(2, summary.monthlyBreakdown.size)
        assertEquals(1, summary.monthlyBreakdown[0].month)
        assertEquals(4000L, summary.monthlyBreakdown[0].expenseAmount)
        assertEquals(2, summary.monthlyBreakdown[1].month)
        assertEquals(10000L, summary.monthlyBreakdown[1].incomeAmount)

        val report = summary.toPlainTextReport()
        assertTrue(report.contains("2026年 収支サマリ"))
        assertTrue(report.contains("消耗品費"))
        assertTrue(report.contains("源泉徴収税合計"))
    }
}
