package com.keihix.app.data.summary

import com.keihix.app.data.drive.RecordedTransaction
import com.keihix.app.data.model.TransactionType
import java.text.NumberFormat
import java.util.Locale

data class CategorySummary(
    val category: String,
    val totalAmount: Long,
    val count: Int,
    val ratio: Float
)

data class MonthlySummary(
    val month: Int, // 1..12
    val incomeAmount: Long,
    val expenseAmount: Long,
    val count: Int
)

data class TransactionSummary(
    val year: Int,
    val totalIncome: Long,
    val totalExpense: Long,
    val netBalance: Long,
    val totalFee: Long,
    val totalWithholdingTax: Long,
    val transactionCount: Int,
    val incomeCount: Int,
    val expenseCount: Int,
    val expenseByCategory: List<CategorySummary>,
    val incomeByCategory: List<CategorySummary>,
    val monthlyBreakdown: List<MonthlySummary>
) {
    /**
     * 確定申告書等作成コーナーへの転記や AI への正確な数値連携に使うテキストサマリを生成
     */
    fun toPlainTextReport(): String {
        val nf = NumberFormat.getNumberInstance(Locale.JAPAN)
        fun yen(amount: Long) = "¥${nf.format(amount)}"

        return buildString {
            appendLine("【${year}年 収支サマリ（KeihiX集計）】")
            appendLine("・収入合計: ${yen(totalIncome)} (${incomeCount}件)")
            appendLine("・経費合計: ${yen(totalExpense)} (${expenseCount}件)")
            appendLine("・差引収支: ${yen(netBalance)}")
            if (totalFee > 0L) {
                appendLine("・手数料合計（収入明細内）: ${yen(totalFee)}")
            }
            if (totalWithholdingTax > 0L) {
                appendLine("・源泉徴収税合計（収入明細内）: ${yen(totalWithholdingTax)}")
            }

            if (expenseByCategory.isNotEmpty()) {
                appendLine()
                appendLine("■ 経費 科目別小計")
                expenseByCategory.forEach { item ->
                    val pct = (item.ratio * 100).toInt()
                    appendLine("・${item.category}: ${yen(item.totalAmount)} (${item.count}件 / ${pct}%)")
                }
            }

            if (incomeByCategory.isNotEmpty()) {
                appendLine()
                appendLine("■ 収入 科目別小計")
                incomeByCategory.forEach { item ->
                    val pct = (item.ratio * 100).toInt()
                    appendLine("・${item.category}: ${yen(item.totalAmount)} (${item.count}件 / ${pct}%)")
                }
            }

            if (monthlyBreakdown.isNotEmpty()) {
                appendLine()
                appendLine("■ 月別推移")
                monthlyBreakdown.forEach { m ->
                    appendLine("・${m.month}月: 収入 ${yen(m.incomeAmount)} / 経費 ${yen(m.expenseAmount)} (${m.count}件)")
                }
            }
        }.trimEnd()
    }
}

/**
 * transaction_log.csv のレコード群から年次・科目別・月別の集計を行う純粋関数ユーティリティ
 */
object TransactionSummaryCalculator {

    fun calculate(year: Int, records: List<RecordedTransaction>): TransactionSummary {
        val expenses = records.filter { it.transaction.type == TransactionType.EXPENSE }
        val incomes = records.filter { it.transaction.type == TransactionType.INCOME }

        val totalExpense = expenses.sumOf { it.transaction.amount }
        val totalIncome = incomes.sumOf { it.transaction.amount }
        val totalFee = records.sumOf { it.transaction.fee ?: 0L }
        val totalWithholdingTax = records.sumOf { it.transaction.withholdingTax ?: 0L }

        val expenseByCategory = buildCategorySummaries(expenses, totalExpense)
        val incomeByCategory = buildCategorySummaries(incomes, totalIncome)

        val monthlyMap = mutableMapOf<Int, MutableList<RecordedTransaction>>()
        records.forEach { rec ->
            val month = extractMonth(rec.transaction.date) ?: return@forEach
            monthlyMap.getOrPut(month) { mutableListOf() }.add(rec)
        }

        val monthlyBreakdown = monthlyMap.entries
            .sortedBy { it.key }
            .map { (month, list) ->
                MonthlySummary(
                    month = month,
                    incomeAmount = list.filter { it.transaction.type == TransactionType.INCOME }
                        .sumOf { it.transaction.amount },
                    expenseAmount = list.filter { it.transaction.type == TransactionType.EXPENSE }
                        .sumOf { it.transaction.amount },
                    count = list.size
                )
            }

        return TransactionSummary(
            year = year,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netBalance = totalIncome - totalExpense,
            totalFee = totalFee,
            totalWithholdingTax = totalWithholdingTax,
            transactionCount = records.size,
            incomeCount = incomes.size,
            expenseCount = expenses.size,
            expenseByCategory = expenseByCategory,
            incomeByCategory = incomeByCategory,
            monthlyBreakdown = monthlyBreakdown
        )
    }

    private fun buildCategorySummaries(
        records: List<RecordedTransaction>,
        grandTotal: Long
    ): List<CategorySummary> {
        if (records.isEmpty()) return emptyList()
        return records
            .groupBy { it.transaction.category.ifBlank { "未分類" } }
            .map { (category, list) ->
                val sum = list.sumOf { it.transaction.amount }
                val ratio = if (grandTotal > 0L) (sum.toFloat() / grandTotal.toFloat()) else 0f
                CategorySummary(
                    category = category,
                    totalAmount = sum,
                    count = list.size,
                    ratio = ratio
                )
            }
            .sortedByDescending { it.totalAmount }
    }

    private fun extractMonth(date: String): Int? {
        // "YYYY-MM-DD" 形式を想定
        val parts = date.split("-", "/")
        if (parts.size < 2) return null
        val month = parts[1].toIntOrNull() ?: return null
        return month.takeIf { it in 1..12 }
    }
}
