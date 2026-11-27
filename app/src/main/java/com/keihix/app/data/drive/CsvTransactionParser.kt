package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import java.nio.charset.StandardCharsets

/**
 * transaction_log.csv から復元された 1 件分の取引レコード
 */
data class RecordedTransaction(
    val rowIndex: Int,
    val transaction: ParsedTransaction,
    val driveFilePath: String
)

/**
 * transaction_log.csv の UTF-8 (BOM 付き/なし) バイト列をパースして
 * RecordedTransaction のリストに変換するパーサー。
 *
 * RFC 4180 のダブルクォート囲み・カンマ・エスケープ ("")・セル内改行に対応します。
 */
class CsvTransactionParser {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun parseBytes(csvBytes: ByteArray): List<RecordedTransaction> {
        if (csvBytes.isEmpty()) return emptyList()
        val contentBytes = if (hasUtf8Bom(csvBytes)) {
            csvBytes.copyOfRange(3, csvBytes.size)
        } else {
            csvBytes
        }
        val text = String(contentBytes, StandardCharsets.UTF_8)
        return parseText(text)
    }

    fun parseText(csvText: String): List<RecordedTransaction> {
        if (csvText.isBlank()) return emptyList()

        val rows = parseCsvRows(csvText)
        if (rows.isEmpty()) return emptyList()

        // 先頭行がヘッダー行であればスキップ
        val firstRowLower = rows.first().map { it.trim().lowercase() }
        val startIndex = if (firstRowLower.size >= 4 && firstRowLower[0] == "date" && firstRowLower[1] == "type") {
            1
        } else {
            0
        }

        val result = mutableListOf<RecordedTransaction>()
        for (index in startIndex until rows.size) {
            val cols = rows[index]
            if (cols.all { it.isBlank() }) continue
            if (cols.size < 5) continue

            val date = cols[0].trim()
            val typeStr = cols[1].trim().lowercase()
            val amount = cols[2].trim().toLongOrNull() ?: continue
            val vendor = cols[3].trim()
            val category = cols[4].trim().ifEmpty {
                if (typeStr == "income") "売上" else "雑費"
            }
            val fee = cols.getOrNull(5)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            val withholdingTax = cols.getOrNull(6)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            val invoiceNumber = cols.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() }
            val memo = cols.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() }
            val driveFilePath = cols.getOrNull(9)?.trim().orEmpty()

            val type = when (typeStr) {
                "income" -> TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }

            result += RecordedTransaction(
                rowIndex = index,
                transaction = ParsedTransaction(
                    type = type,
                    date = date,
                    amount = amount,
                    vendor = vendor,
                    category = category,
                    fee = fee,
                    withholdingTax = withholdingTax,
                    invoiceNumber = invoiceNumber,
                    memo = memo
                ),
                driveFilePath = driveFilePath
            )
        }

        return result
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes[0] == utf8Bom[0] && bytes[1] == utf8Bom[1] && bytes[2] == utf8Bom[2]
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()

        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when (c) {
                    '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow += field.toString()
                        field.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') {
                            i++
                        }
                        currentRow += field.toString()
                        field.setLength(0)
                        rows += currentRow.toList()
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow += field.toString()
                        field.setLength(0)
                        rows += currentRow.toList()
                        currentRow.clear()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += field.toString()
            rows += currentRow.toList()
        }

        return rows
    }
}
