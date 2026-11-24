package com.keihix.app.data.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.keihix.app.KeihiXApplication
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * オフライン耐性と自動リトライを備えた Google Drive バックグラウンド保存ワーカー。
 *
 * 共有インテントの Uri 権限失効やアプリ終了に備え、事前にローカルの一時領域へ
 * 書き出したファイルを読み込み、Drive へのアップロードと CSV 台帳追記を実行する。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val localFilePath = inputData.getString(KEY_LOCAL_FILE_PATH)
            ?: return failureResult("アップロード対象ファイルが見つかりません。")
        val year = inputData.getInt(KEY_YEAR, 2026)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        val extension = inputData.getString(KEY_EXTENSION) ?: "jpg"
        val transaction = transactionFromInputData(inputData)
            ?: return failureResult("取引データの読み込みに失敗しました。")

        val file = File(localFilePath)
        if (!file.exists()) {
            return failureResult("一時ファイルが既に削除されています。")
        }

        val fileBytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return failureResult("ローカルファイルの読み込みに失敗しました: ${e.localizedMessage}")
        }

        val app = applicationContext as KeihiXApplication
        return when (
            val saveResult = app.driveStorageRepository.saveTransaction(
                year = year,
                transaction = transaction,
                fileBytes = fileBytes,
                mimeType = mimeType,
                extension = extension
            )
        ) {
            is DriveSaveResult.Success -> {
                file.delete()
                inputData.getString(KEY_PENDING_FILE_ID)?.let { pendingId ->
                    app.driveStorageRepository.deletePendingFile(pendingId)
                }
                Result.success(workDataOf(KEY_RESULT_PATH to saveResult.relativeDrivePath))
            }
            is DriveSaveResult.Error -> {
                if (isRetryableError(saveResult.message) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    failureResult(saveResult.message)
                }
            }
        }
    }

    private fun failureResult(message: String): Result =
        Result.failure(workDataOf(KEY_ERROR_MESSAGE to message))

    companion object {
        const val WORK_TAG = "keihix_drive_upload"
        const val KEY_YEAR = "year"
        const val KEY_LOCAL_FILE_PATH = "local_file_path"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_EXTENSION = "extension"
        const val KEY_TYPE = "tx_type"
        const val KEY_DATE = "tx_date"
        const val KEY_AMOUNT = "tx_amount"
        const val KEY_VENDOR = "tx_vendor"
        const val KEY_CATEGORY = "tx_category"
        const val KEY_FEE = "tx_fee"
        const val KEY_WITHHOLDING_TAX = "tx_withholding_tax"
        const val KEY_INVOICE_NUMBER = "tx_invoice_number"
        const val KEY_MEMO = "tx_memo"
        const val KEY_CONFIDENCE_NOTE = "tx_confidence_note"
        const val KEY_RESULT_PATH = "result_path"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PENDING_FILE_ID = "pending_file_id"

        private const val MAX_RETRY_ATTEMPTS = 5
        private const val PENDING_DIR_NAME = "pending_uploads"

        /**
         * ファイルを端末内の永続一時ディレクトリへ退避し、WorkManager に非同期保存を登録する。
         */
        fun enqueue(
            context: Context,
            year: Int,
            transaction: ParsedTransaction,
            fileBytes: ByteArray,
            mimeType: String,
            extension: String,
            pendingDriveFileId: String? = null
        ): UUID {
            val pendingDir = File(context.filesDir, PENDING_DIR_NAME).apply { mkdirs() }
            val stagedFile = File(pendingDir, "upload_${UUID.randomUUID()}.$extension")
            stagedFile.writeBytes(fileBytes)

            val inputData = buildInputData(
                year = year,
                localFilePath = stagedFile.absolutePath,
                mimeType = mimeType,
                extension = extension,
                transaction = transaction,
                pendingDriveFileId = pendingDriveFileId
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()

            val uniqueWorkName = "upload_${year}_${transaction.toCompliantFileName()}"
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                request
            )
            return request.id
        }

        internal fun buildInputData(
            year: Int,
            localFilePath: String,
            mimeType: String,
            extension: String,
            transaction: ParsedTransaction,
            pendingDriveFileId: String? = null
        ): Data = Data.Builder()
            .putInt(KEY_YEAR, year)
            .putString(KEY_LOCAL_FILE_PATH, localFilePath)
            .putString(KEY_MIME_TYPE, mimeType)
            .putString(KEY_EXTENSION, extension)
            .putString(KEY_TYPE, transaction.type.name)
            .putString(KEY_DATE, transaction.date)
            .putLong(KEY_AMOUNT, transaction.amount)
            .putString(KEY_VENDOR, transaction.vendor)
            .putString(KEY_CATEGORY, transaction.category)
            .apply {
                transaction.fee?.let { putLong(KEY_FEE, it) }
                transaction.withholdingTax?.let { putLong(KEY_WITHHOLDING_TAX, it) }
                transaction.invoiceNumber?.let { putString(KEY_INVOICE_NUMBER, it) }
                transaction.memo?.let { putString(KEY_MEMO, it) }
                transaction.confidenceNote?.let { putString(KEY_CONFIDENCE_NOTE, it) }
                pendingDriveFileId?.let { putString(KEY_PENDING_FILE_ID, it) }
            }
            .build()

        internal fun transactionFromInputData(data: Data): ParsedTransaction? {
            val typeName = data.getString(KEY_TYPE) ?: return null
            val type = runCatching { TransactionType.valueOf(typeName) }.getOrNull() ?: return null
            val date = data.getString(KEY_DATE) ?: return null
            val amount = data.getLong(KEY_AMOUNT, -1L)
            if (amount < 0L) return null
            val vendor = data.getString(KEY_VENDOR) ?: return null
            val category = data.getString(KEY_CATEGORY) ?: return null

            val feeRaw = data.getLong(KEY_FEE, -1L)
            val taxRaw = data.getLong(KEY_WITHHOLDING_TAX, -1L)

            return ParsedTransaction(
                type = type,
                date = date,
                amount = amount,
                vendor = vendor,
                category = category,
                fee = if (feeRaw >= 0L) feeRaw else null,
                withholdingTax = if (taxRaw >= 0L) taxRaw else null,
                invoiceNumber = data.getString(KEY_INVOICE_NUMBER),
                memo = data.getString(KEY_MEMO),
                confidenceNote = data.getString(KEY_CONFIDENCE_NOTE)
            )
        }

        internal fun isRetryableError(message: String): Boolean {
            val lower = message.lowercase()
            // 認証エラーや未連携時はリトライしても回復しないため即失敗扱い
            val nonRetryable = listOf("連携", "認証", "権限", "トークン", "oauth", "sha-1", "token", "401", "403")
            return nonRetryable.none { it in lower }
        }
    }
}
