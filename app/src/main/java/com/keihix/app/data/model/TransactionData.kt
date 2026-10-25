package com.keihix.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 取引区分（経費または収入）
 */
@Serializable
enum class TransactionType {
    @SerialName("expense")
    EXPENSE,

    @SerialName("income")
    INCOME
}

/**
 * Gemini 1.5 Flash から抽出されるトランザクションデータモデル。
 * 電帳法の検索要件（日付・金額・取引先）および、
 * 確定申告に必要な勘定科目・手数料・源泉徴収税を網羅します。
 */
@Serializable
data class ParsedTransaction(
    @SerialName("type")
    val type: TransactionType = TransactionType.EXPENSE,

    /** 取引日または売上計上日 (YYYY-MM-DD) */
    @SerialName("date")
    val date: String,

    /** 税込総額（売上額または支払総額） */
    @SerialName("amount")
    val amount: Long,

    /** 取引先名・店舗名・支払元プラットフォーム名 */
    @SerialName("vendor")
    val vendor: String,

    /** 勘定科目（消耗品費、通信費、旅費交通費、売上など） */
    @SerialName("category")
    val category: String,

    /** プラットフォーム手数料等（収入時） */
    @SerialName("fee")
    val fee: Long? = null,

    /** 源泉徴収税額（収入時） */
    @SerialName("withholding_tax")
    val withholdingTax: Long? = null,

    /** 適格請求書発行事業者番号 (Tから始まる13桁) */
    @SerialName("invoice_number")
    val invoiceNumber: String? = null,

    /** 購入品目・摘要メモ */
    @SerialName("memo")
    val memo: String? = null,

    /** AIの読み取り備考（不鮮明な箇所や判断理由の補足） */
    @SerialName("confidence_note")
    val confidenceNote: String? = null
) {
    /**
     * 電帳法に準拠したGoogle Drive保存用ファイル名（拡張子除く）を生成
     * 例: "20261025_1980_Amazon"
     */
    fun toCompliantFileName(): String {
        val cleanDate = date.replace("-", "")
        val cleanVendor = vendor.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        return "${cleanDate}_${amount}_${cleanVendor}"
    }

    /**
     * transaction_log.csv に出力する行（CSVエスケープ付き）を生成
     */
    fun toCsvRow(driveFilePath: String = ""): String {
        fun escape(value: String?): String {
            if (value == null) return ""
            return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }

        return listOf(
            date,
            type.name.lowercase(),
            amount.toString(),
            escape(vendor),
            escape(category),
            (fee ?: 0).toString(),
            (withholdingTax ?: 0).toString(),
            escape(invoiceNumber ?: ""),
            escape(memo ?: ""),
            escape(driveFilePath)
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = "date,type,amount,vendor,category,fee,withholding_tax,invoice_number,memo,drive_file_path"
    }
}
