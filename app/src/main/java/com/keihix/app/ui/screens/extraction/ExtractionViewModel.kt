package com.keihix.app.ui.screens.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keihix.app.data.ai.GeminiTransactionParser
import com.keihix.app.data.ai.ParseResult
import com.keihix.app.data.drive.DriveSaveResult
import com.keihix.app.data.drive.DriveStorageRepository
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import com.keihix.app.data.preference.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class ExtractionUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
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
    private val parser: GeminiTransactionParser,
    private val driveRepository: DriveStorageRepository? = null,
    private val preferencesRepository: UserPreferencesRepository? = null
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
                    errorMessage = null,
                    saveSuccessMessage = null
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
            val isPdf = mimeType.contains("pdf", ignoreCase = true)

            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedDocumentUri = uri,
                    selectedDocumentName = uri.lastPathSegment,
                    selectedImageBitmap = null,
                    errorMessage = null,
                    saveSuccessMessage = null
                )
            }

            val result = if (isPdf) {
                val bytes = readBytesFromUri(context, uri)
                if (bytes != null) {
                    parser.parseReceiptPdf(bytes)
                } else {
                    ParseResult.Error("PDFファイルの読み込みに失敗しました")
                }
            } else {
                val bitmap = loadBitmapFromUri(context, uri)
                if (bitmap != null) {
                    _uiState.update { it.copy(selectedImageBitmap = bitmap) }
                    parser.parseReceiptImage(bitmap)
                } else {
                    ParseResult.Error("画像ファイルの読み込みに失敗しました")
                }
            }

            handleParseResult(result)
        }
    }

    private fun handleParseResult(result: ParseResult) {
        when (result) {
            is ParseResult.Success -> {
                val data = result.data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasExtracted = true,
                        errorMessage = null,
                        type = data.type,
                        date = data.date,
                        amount = data.amount.toString(),
                        vendor = data.vendor,
                        category = data.category,
                        fee = data.fee?.toString() ?: "",
                        withholdingTax = data.withholdingTax?.toString() ?: "",
                        invoiceNumber = data.invoiceNumber ?: "",
                        memo = data.memo ?: "",
                        confidenceNote = data.confidenceNote
                    )
                }
            }
            is ParseResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    /**
     * Google Drive へファイルおよび CSV ログを保存する
     */
    fun saveToGoogleDrive(context: Context) {
        val repo = driveRepository
        if (repo == null) {
            _uiState.update { it.copy(errorMessage = "DriveRepository が初期化されていません") }
            return
        }

        val currentState = _uiState.value
        val transaction = currentState.toParsedTransaction()
        val targetYear = preferencesRepository?.targetYear?.value ?: 2026

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccessMessage = null) }

            // 保存対象のバイナリデータと拡張子を準備
            val (fileBytes, mimeType, extension) = when {
                currentState.selectedDocumentUri != null -> {
                    val uri = currentState.selectedDocumentUri
                    val bytes = readBytesFromUri(context, uri) ?: ByteArray(0)
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val ext = if (mime.contains("pdf")) "pdf" else "jpg"
                    Triple(bytes, mime, ext)
                }
                currentState.selectedImageBitmap != null -> {
                    val stream = ByteArrayOutputStream()
                    currentState.selectedImageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    Triple(stream.toByteArray(), "image/jpeg", "jpg")
                }
                else -> {
                    // デモデータや手動入力の場合: プレースホルダーテキストをダミーファイルとして保存
                    val placeholderText = "KeihiX Receipt Placeholder: ${transaction.vendor} (${transaction.date})"
                    Triple(placeholderText.toByteArray(), "text/plain", "txt")
                }
            }

            val result = repo.saveTransaction(
                year = targetYear,
                transaction = transaction,
                fileBytes = fileBytes,
                mimeType = mimeType,
                extension = extension
            )

            when (result) {
                is DriveSaveResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            saveSuccessMessage = "Google Drive の [${result.relativeDrivePath}] および台帳に保存しました。"
                        )
                    }
                }
                is DriveSaveResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    private fun readBytesFromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    fun onTypeChange(newType: TransactionType) {
        _uiState.update {
            it.copy(
                type = newType,
                category = if (it.category.isBlank() || it.category == "消耗品費" || it.category == "売上") {
                    if (newType == TransactionType.INCOME) "売上" else "消耗品費"
                } else {
                    it.category
                }
            )
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
    fun clearSuccessMessage() = _uiState.update { it.copy(saveSuccessMessage = null) }

    /**
     * デモ・検証用: 経費サンプルの自動投入
     */
    fun loadExpenseDemo() {
        _uiState.update {
            it.copy(
                hasExtracted = true,
                errorMessage = null,
                saveSuccessMessage = null,
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
                saveSuccessMessage = null,
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
