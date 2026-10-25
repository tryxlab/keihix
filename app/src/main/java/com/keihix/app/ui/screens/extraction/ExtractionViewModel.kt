package com.keihix.app.ui.screens.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keihix.app.data.ai.GeminiTransactionParser
import com.keihix.app.data.ai.ParseResult
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

data class ExtractionUiState(
    val isLoading: Boolean = false,
    val selectedImageBitmap: Bitmap? = null,
    val selectedDocumentUri: Uri? = null,
    val selectedDocumentName: String? = null,
    val errorMessage: String? = null,
    val rawJsonOutput: String? = null,
    // 編集可能なフィールド
    val type: TransactionType = TransactionType.EXPENSE,
    val date: String = "",
    val amount: String = "",
    val vendor: String = "",
    val category: String = "",
    val fee: String = "",
    val withholdingTax: String = "",
    val invoiceNumber: String = "",
    val memo: String = "",
    val confidenceNote: String? = null,
    val hasExtracted: Boolean = false,
    val saveSuccessMessage: String? = null
) {
    /**
     * 現在の入力内容から電帳法準拠のファイル名プレビューを生成
     */
    fun compliantFileNamePreview(): String {
        val cleanDate = date.replace("-", "").ifBlank { "YYYYMMDD" }
        val cleanAmount = amount.ifBlank { "0" }
        val cleanVendor = vendor.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { "取引先" }
        return "${cleanDate}_${cleanAmount}_${cleanVendor}"
    }

    /**
     * 現在の編集内容から ParsedTransaction を生成
     */
    fun toParsedTransaction(): ParsedTransaction {
        return ParsedTransaction(
            type = type,
            date = date.ifBlank { "2026-01-01" },
            amount = amount.toLongOrNull() ?: 0L,
            vendor = vendor.ifBlank { "未指定" },
            category = category.ifBlank { if (type == TransactionType.INCOME) "売上" else "消耗品費" },
            fee = fee.toLongOrNull(),
            withholdingTax = withholdingTax.toLongOrNull(),
            invoiceNumber = invoiceNumber.ifBlank { null },
            memo = memo.ifBlank { null },
            confidenceNote = confidenceNote
        )
    }
}

class ExtractionViewModel(
    private val parser: GeminiTransactionParser
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtractionUiState())
    val uiState: StateFlow<ExtractionUiState> = _uiState.asStateFlow()

    /**
     * ギャラリーやカメラ等から Bitmap が渡された時の解析
     */
    fun processBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedImageBitmap = bitmap,
                    selectedDocumentUri = null,
                    selectedDocumentName = null,
                    errorMessage = null
                )
            }

            val result = parser.parseReceiptImage(bitmap)
            handleParseResult(result)
        }
    }

    /**
     * Uri (画像またはPDF) が渡された時の解析
     */
    fun processUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val fileName = uri.lastPathSegment ?: "document"

            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedDocumentUri = uri,
                    selectedDocumentName = fileName,
                    errorMessage = null
                )
            }

            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val bytes = inputStream?.use { it.readBytes() }

                if (bytes == null || bytes.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "ファイルの読み込みに失敗しました。"
                        )
                    }
                    return@launch
                }

                // 画像の場合はプレビュー用Bitmapも生成
                if (mimeType.startsWith("image/")) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    _uiState.update { it.copy(selectedImageBitmap = bitmap) }
                } else {
                    _uiState.update { it.copy(selectedImageBitmap = null) }
                }

                val result = parser.parseDocument(bytes, mimeType)
                handleParseResult(result)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "読み込みエラー: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun handleParseResult(result: ParseResult) {
        when (result) {
            is ParseResult.Success -> {
                val tx = result.transaction
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasExtracted = true,
                        errorMessage = null,
                        type = tx.type,
                        date = tx.date,
                        amount = tx.amount.toString(),
                        vendor = tx.vendor,
                        category = tx.category,
                        fee = tx.fee?.toString().orEmpty(),
                        withholdingTax = tx.withholdingTax?.toString().orEmpty(),
                        invoiceNumber = tx.invoiceNumber.orEmpty(),
                        memo = tx.memo.orEmpty(),
                        confidenceNote = tx.confidenceNote
                    )
                }
            }
            is ParseResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message,
                        rawJsonOutput = result.rawOutput
                    )
                }
            }
        }
    }

    // フォーム入力変更ハンドラ
    fun onTypeChange(newType: TransactionType) {
        _uiState.update {
            val defaultCategory = if (newType == TransactionType.INCOME) "売上" else "消耗品費"
            it.copy(type = newType, category = if (it.category.isBlank()) defaultCategory else it.category)
        }
    }

    fun onDateChange(newDate: String) = _uiState.update { it.copy(date = newDate) }
    fun onAmountChange(newAmount: String) = _uiState.update { it.copy(amount = newAmount.filter { c -> c.isDigit() }) }
    fun onVendorChange(newVendor: String) = _uiState.update { it.copy(vendor = newVendor) }
    fun onCategoryChange(newCategory: String) = _uiState.update { it.copy(category = newCategory) }
    fun onFeeChange(newFee: String) = _uiState.update { it.copy(fee = newFee.filter { c -> c.isDigit() }) }
    fun onWithholdingTaxChange(newTax: String) = _uiState.update { it.copy(withholdingTax = newTax.filter { c -> c.isDigit() }) }
    fun onInvoiceNumberChange(newNumber: String) = _uiState.update { it.copy(invoiceNumber = newNumber) }
    fun onMemoChange(newMemo: String) = _uiState.update { it.copy(memo = newMemo) }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    /**
     * デモ・検証用: 経費サンプルの自動投入
     */
    fun loadExpenseDemo() {
        _uiState.update {
            it.copy(
                hasExtracted = true,
                errorMessage = null,
                type = TransactionType.EXPENSE,
                date = "2026-10-25",
                amount = "1980",
                vendor = "Amazon Japan G.K.",
                category = "消耗品費",
                fee = "",
                withholdingTax = "",
                invoiceNumber = "T1234567890123",
                memo = "USB-C ハブ 10Gbps対応",
                confidenceNote = "デモデータ: Gemini 1.5 Flash 抽出シミュレーション"
            )
        }
    }

    /**
     * デモ・検証用: 収入サンプルの自動投入
     */
    fun loadIncomeDemo() {
        _uiState.update {
            it.copy(
                hasExtracted = true,
                errorMessage = null,
                type = TransactionType.INCOME,
                date = "2026-10-31",
                amount = "50000",
                vendor = "note株式会社",
                category = "売上",
                fee = "5000",
                withholdingTax = "4595",
                invoiceNumber = "",
                memo = "10月度 有料記事売上（振込完了）",
                confidenceNote = "デモデータ: 売上総額から手数料・源泉徴収税を分離抽出"
            )
        }
    }
}
