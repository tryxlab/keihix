package com.keihix.app.ui.screens.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.keihix.app.data.ai.GeminiTransactionParser
import com.keihix.app.data.ai.ParseResult
import com.keihix.app.data.drive.DriveStorageRepository
import com.keihix.app.data.drive.PendingDriveFile
import com.keihix.app.data.drive.UploadWorker
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import com.keihix.app.data.preference.UserPreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate

data class ExtractionUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val selectedImageBitmap: Bitmap? = null,
    val selectedDocumentUri: Uri? = null,
    val selectedDocumentName: String? = null,
    val isPdfSelected: Boolean = false,
    val errorMessage: String? = null,
    val isErrorRetryable: Boolean = false,
    val rawJsonOutput: String? = null,
    val pendingUploadCount: Int = 0,
    val uploadStatusMessage: String? = null,
    val pendingDriveFiles: List<PendingDriveFile> = emptyList(),
    val isLoadingPendingFiles: Boolean = false,
    val activePendingDriveFileId: String? = null,
    val activePendingDriveBytes: ByteArray? = null,
    val activePendingDriveMimeType: String? = null,
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
            date = date.ifBlank { LocalDate.now().toString() },
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

    private var uploadQueueObserverJob: Job? = null

    /**
     * WorkManager の待機中・実行中ジョブ数を監視する
     */
    fun observePendingUploads(context: Context) {
        if (uploadQueueObserverJob != null) return
        uploadQueueObserverJob = viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(UploadWorker.WORK_TAG)
                .collect { infos ->
                    val activeCount = infos.count {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                    _uiState.update { it.copy(pendingUploadCount = activeCount) }
                }
        }
    }

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
                    isPdfSelected = false,
                    errorMessage = null,
                    isErrorRetryable = false,
                    saveSuccessMessage = null,
                    uploadStatusMessage = null
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
            val isPdf = mimeType.contains("pdf", ignoreCase = true) ||
                uri.toString().endsWith(".pdf", ignoreCase = true)
            val displayName = resolveDisplayName(context, uri)

            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedDocumentUri = uri,
                    selectedDocumentName = displayName,
                    isPdfSelected = isPdf,
                    selectedImageBitmap = null,
                    errorMessage = null,
                    isErrorRetryable = false,
                    saveSuccessMessage = null,
                    uploadStatusMessage = null
                )
            }

            val result = if (isPdf) {
                val bytes = readBytesFromUri(context, uri)
                if (bytes != null) {
                    parser.parseDocument(bytes, "application/pdf")
                } else {
                    ParseResult.Error("PDFファイルの読み込みに失敗しました", isRetryable = false)
                }
            } else {
                val bitmap = loadOrientedBitmapFromUri(context, uri)
                if (bitmap != null) {
                    _uiState.update { it.copy(selectedImageBitmap = bitmap) }
                    parser.parseReceiptImage(bitmap)
                } else {
                    ParseResult.Error("画像ファイルの読み込みに失敗しました", isRetryable = false)
                }
            }

            handleParseResult(result)
        }
    }

    /**
     * 最後に選択した画像/PDFで再解析を実行する（503等の一次エラー復帰用）
     */
    fun retryLastExtraction(context: Context) {
        val state = _uiState.value
        when {
            state.selectedDocumentUri != null -> processUri(context, state.selectedDocumentUri)
            state.selectedImageBitmap != null -> processBitmap(state.selectedImageBitmap)
        }
    }

    /**
     * AI解析エラー時やオフライン時に、手入力フォームを直接開く
     */
    fun openManualEntry() {
        _uiState.update {
            it.copy(
                hasExtracted = true,
                errorMessage = null,
                isErrorRetryable = false,
                date = it.date.ifBlank { LocalDate.now().toString() },
                category = it.category.ifBlank {
                    if (it.type == TransactionType.INCOME) "売上" else "消耗品費"
                }
            )
        }
    }

    /**
     * Google Drive の pending フォルダ内の未解析ファイル一覧を更新する
     */
    fun refreshPendingDriveFiles() {
        val repo = driveRepository ?: return
        val targetYear = preferencesRepository?.targetYear?.value ?: LocalDate.now().year
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPendingFiles = true) }
            val result = repo.listPendingFiles(targetYear)
            _uiState.update { state ->
                state.copy(
                    isLoadingPendingFiles = false,
                    pendingDriveFiles = result.getOrElse { state.pendingDriveFiles }
                )
            }
        }
    }

    /**
     * 解析に失敗した画像・PDFを一旦 Google Drive の pending/ フォルダに退避する（あとで解析用）
     */
    fun saveCurrentToPendingDrive(context: Context) {
        val repo = driveRepository ?: run {
            _uiState.update { it.copy(errorMessage = "DriveRepository が初期化されていません") }
            return
        }
        val targetYear = preferencesRepository?.targetYear?.value ?: LocalDate.now().year
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccessMessage = null) }
            val (bytes, mimeType, ext) = extractCurrentFilePayload(context, state) ?: run {
                _uiState.update { it.copy(isSaving = false, errorMessage = "退避するファイルが見つかりません。") }
                return@launch
            }

            val result = repo.savePendingFile(
                year = targetYear,
                fileBytes = bytes,
                mimeType = mimeType,
                extension = ext
            )
            result.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = null,
                        isErrorRetryable = false,
                        saveSuccessMessage = "未解析フォルダ (pending/${saved.name}) に退避しました。あとから解析できます。"
                    )
                }
                refreshPendingDriveFiles()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Drive への退避に失敗しました: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Drive の pending/ に退避したファイルをダウンロードして Gemini で再解析する
     */
    fun parsePendingDriveFile(file: PendingDriveFile) {
        val repo = driveRepository ?: return
        viewModelScope.launch {
            val isPdf = file.mimeType.contains("pdf", ignoreCase = true) ||
                file.name.endsWith(".pdf", ignoreCase = true)

            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedDocumentUri = null,
                    selectedDocumentName = file.name,
                    isPdfSelected = isPdf,
                    selectedImageBitmap = null,
                    errorMessage = null,
                    isErrorRetryable = false,
                    saveSuccessMessage = null,
                    activePendingDriveFileId = file.fileId,
                    activePendingDriveMimeType = file.mimeType
                )
            }

            val bytes = repo.downloadFileBytes(file.fileId).getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "未解析ファイルのダウンロードに失敗しました: ${err.localizedMessage}"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(activePendingDriveBytes = bytes) }

            val parseResult = if (isPdf) {
                parser.parseDocument(bytes, "application/pdf")
            } else {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    _uiState.update { it.copy(selectedImageBitmap = bitmap) }
                    parser.parseReceiptImage(bitmap)
                } else {
                    parser.parseDocument(bytes, file.mimeType)
                }
            }
            handleParseResult(parseResult)
        }
    }

    private fun handleParseResult(result: ParseResult) {
        when (result) {
            is ParseResult.Success -> {
                val data = result.transaction
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasExtracted = true,
                        errorMessage = null,
                        isErrorRetryable = false,
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
                        errorMessage = result.message,
                        isErrorRetryable = result.isRetryable,
                        rawJsonOutput = result.rawOutput
                    )
                }
            }
        }
    }

    /**
     * WorkManager を介して Google Drive へ非同期保存する（オフライン時は自動待機・再試行）
     */
    fun saveToGoogleDrive(context: Context) {
        if (driveRepository == null) {
            _uiState.update { it.copy(errorMessage = "DriveRepository が初期化されていません") }
            return
        }

        val currentState = _uiState.value
        val transaction = currentState.toParsedTransaction()
        val targetYear = preferencesRepository?.targetYear?.value ?: LocalDate.now().year

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    errorMessage = null,
                    isErrorRetryable = false,
                    saveSuccessMessage = null,
                    uploadStatusMessage = "保存データを準備中..."
                )
            }

            val (fileBytes, mimeType, extension) = extractCurrentFilePayload(context, currentState)
                ?: run {
                    val placeholderText = "KeihiX Receipt Placeholder: ${transaction.vendor} (${transaction.date})"
                    Triple(placeholderText.toByteArray(), "text/plain", "txt")
                }

            val workId = UploadWorker.enqueue(
                context = context,
                year = targetYear,
                transaction = transaction,
                fileBytes = fileBytes,
                mimeType = mimeType,
                extension = extension,
                pendingDriveFileId = currentState.activePendingDriveFileId
            )

            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workId)
                .collect { info ->
                    if (info == null) return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    uploadStatusMessage = "送信キューに追加しました（オフライン時は接続後に自動保存されます）。"
                                )
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            _uiState.update {
                                it.copy(
                                    isSaving = true,
                                    uploadStatusMessage = "バックグラウンドで Google Drive に保存中..."
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val savedPath = info.outputData.getString(UploadWorker.KEY_RESULT_PATH)
                                ?: currentState.compliantFileNamePreview()
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    uploadStatusMessage = null,
                                    activePendingDriveFileId = null,
                                    activePendingDriveBytes = null,
                                    activePendingDriveMimeType = null,
                                    saveSuccessMessage = "Google Drive の [$savedPath] および台帳に保存しました。"
                                )
                            }
                            refreshPendingDriveFiles()
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            val errMsg = info.outputData.getString(UploadWorker.KEY_ERROR_MESSAGE)
                                ?: "Google Drive への保存に失敗しました。"
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    uploadStatusMessage = null,
                                    errorMessage = errMsg,
                                    isErrorRetryable = false
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun extractCurrentFilePayload(
        context: Context,
        state: ExtractionUiState
    ): Triple<ByteArray, String, String>? = when {
        state.activePendingDriveBytes != null -> {
            val mime = state.activePendingDriveMimeType ?: "image/jpeg"
            val ext = if (mime.contains("pdf", ignoreCase = true)) "pdf" else "jpg"
            Triple(state.activePendingDriveBytes, mime, ext)
        }
        state.isPdfSelected && state.selectedDocumentUri != null -> {
            val bytes = readBytesFromUri(context, state.selectedDocumentUri) ?: return null
            Triple(bytes, "application/pdf", "pdf")
        }
        state.selectedImageBitmap != null -> {
            val stream = ByteArrayOutputStream()
            state.selectedImageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            Triple(stream.toByteArray(), "image/jpeg", "jpg")
        }
        state.selectedDocumentUri != null -> {
            val uri = state.selectedDocumentUri
            val bytes = readBytesFromUri(context, uri) ?: return null
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = if (mime.contains("png")) "png" else "jpg"
            Triple(bytes, mime, ext)
        }
        else -> null
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
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

    /**
     * EXIF の向き情報を考慮して Bitmap を正立状態で読み込む
     */
    private fun loadOrientedBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null

            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees == 0f) {
                rawBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            }
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

    fun clearError() = _uiState.update { it.copy(errorMessage = null, isErrorRetryable = false) }
    fun clearSuccessMessage() = _uiState.update { it.copy(saveSuccessMessage = null) }
    fun clearUploadStatusMessage() = _uiState.update { it.copy(uploadStatusMessage = null) }

    /**
     * デモ・検証用: 経費サンプルの自動投入
     */
    fun loadExpenseDemo() {
        _uiState.update {
            it.copy(
                hasExtracted = true,
                errorMessage = null,
                isErrorRetryable = false,
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
                confidenceNote = "デモデータ: Gemini 抽出シミュレーション"
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
                isErrorRetryable = false,
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
