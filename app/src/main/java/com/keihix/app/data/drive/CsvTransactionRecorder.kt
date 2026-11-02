package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction
import java.nio.charset.StandardCharsets

/**
 * transaction_log.csv の追記・構築を行うヘルパークラス。
 * Google Drive REST API の Read-Modify-Write パターンで使用します。
 */
class CsvTransactionRecorder {

    /**
     * UTF-8 BOM (Byte Order Mark: 0xEF, 0xBB, 0xBF)
     * Windows Excel 等で開いた際の文字化けを防止します。
     */
    val utf8Bom: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * 初期ヘッダー行（BOM付きバイト列）を生成
     */
    fun createInitialCsvBytes(firstTransaction: ParsedTransaction? = null, drivePath: String = ""): ByteArray {
        val sb = StringBuilder()
        sb.append(ParsedTransaction.CSV_HEADER).append("\r\n")
        if (firstTransaction != null) {
            sb.append(firstTransaction.toCsvRow(drivePath)).append("\r\n")
        }
        val textBytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        return utf8Bom + textBytes
    }

    /**
     * 既存の CSV バイト列に新しいトランザクション行を追記した新しいバイト列を生成。
     * 既存の BOM は保持し、末尾の改行が抜けている場合は適切に補正します。
     */
    fun appendTransactionRow(
        existingCsvBytes: ByteArray,
        newTransaction: ParsedTransaction,
        drivePath: String
    ): ByteArray {
        val hasBom = hasUtf8Bom(existingCsvBytes)
        val contentBytes = if (hasBom) {
            existingCsvBytes.copyOfRange(3, existingCsvBytes.size)
        } else {
            existingCsvBytes
        }

        val existingContent = String(contentBytes, StandardCharsets.UTF_8)
        val newRow = newTransaction.toCsvRow(drivePath)

        val updatedContent = buildString {
            append(existingContent)
            // 末尾に改行がない場合は改行を追加
            if (existingContent.isNotEmpty() && !existingContent.endsWith("\n") && !existingContent.endsWith("\r\n")) {
                append("\r\n")
            }
            append(newRow)
            append("\r\n")
        }

        val updatedBytes = updatedContent.toByteArray(StandardCharsets.UTF_8)
        return if (hasBom) {
            utf8Bom + updatedBytes
        } else {
            // 将来的な Excel 互換性のため BOM を付与して返す
            utf8Bom + updatedBytes
        }
    }

    /**
     * バイト列の先頭が UTF-8 BOM か判定
     */
    fun hasUtf8Bom(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes[0] == utf8Bom[0] && bytes[1] == utf8Bom[1] && bytes[2] == utf8Bom[2]
    }
}
