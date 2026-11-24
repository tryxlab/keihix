package com.keihix.app.data.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.keihix.app.KeihiXApplication
import java.util.concurrent.TimeUnit

/**
 * 端末内に退避された未解析レシート (PendingReceiptStore) を、
 * ネットワーク接続時にバックグラウンドで Google Drive の pending/ フォルダへ自動バックアップするワーカー。
 */
class PendingBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val localId = inputData.getString(KEY_LOCAL_ID) ?: return Result.failure()
        val store = PendingReceiptStore(applicationContext)
        val item = store.getById(localId) ?: return Result.success() // 既に解析・確定済みなら完了扱い

        if (item.driveFileId != null) {
            return Result.success() // 既に Drive バックアップ済み
        }

        val bytes = store.readBytes(item) ?: return Result.failure()
        val app = applicationContext as KeihiXApplication

        return app.driveStorageRepository.savePendingFile(
            year = item.year,
            fileBytes = bytes,
            mimeType = item.mimeType,
            extension = item.extension
        ).fold(
            onSuccess = { pendingDriveFile ->
                store.markSyncedToDrive(localId, pendingDriveFile.fileId)
                Result.success(workDataOf(KEY_DRIVE_FILE_ID to pendingDriveFile.fileId))
            },
            onFailure = { err ->
                val msg = err.localizedMessage.orEmpty()
                if (UploadWorker.isRetryableError(msg) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    // Drive バックアップが失敗してもローカル永続ファイルは安全に保持され続ける
                    Result.failure()
                }
            }
        )
    }

    companion object {
        const val WORK_TAG = "keihix_pending_backup"
        const val KEY_LOCAL_ID = "local_pending_id"
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        private const val MAX_RETRY_ATTEMPTS = 5

        fun enqueue(context: Context, localId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PendingBackupWorker>()
                .setInputData(workDataOf(KEY_LOCAL_ID to localId))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "pending_backup_$localId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
