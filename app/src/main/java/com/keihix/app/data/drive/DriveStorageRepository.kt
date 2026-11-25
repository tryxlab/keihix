package com.keihix.app.data.drive

import android.content.Intent
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Google Drive 上のファイル保存および台帳更新を管理するリポジトリインターフェース
 */
interface DriveStorageRepository {
    suspend fun ensureFolderHierarchy(year: Int): Result<DriveFolderHierarchy>
    suspend fun saveTransaction(
        year: Int,
        transaction: ParsedTransaction,
        fileBytes: ByteArray,
        mimeType: String,
        extension: String
    ): DriveSaveResult

    suspend fun savePendingFile(
        year: Int,
        fileBytes: ByteArray,
        mimeType: String,
        extension: String
    ): Result<PendingDriveFile>

    suspend fun listPendingFiles(year: Int): Result<List<PendingDriveFile>>
    suspend fun downloadFileBytes(fileId: String): Result<ByteArray>
    suspend fun deletePendingFile(fileId: String): Result<Unit>
}

/**
 * Google Drive REST API v3 を用いたストレージ実装
 */
class GoogleDriveStorageRepository(
    private val driveServiceProvider: () -> Drive?,
    private val csvRecorder: CsvTransactionRecorder = CsvTransactionRecorder(),
    private val onConsentRequired: ((Intent) -> Unit)? = null
) : DriveStorageRepository {

    // メモリ内フォルダIDキャッシュ (年 -> 階層ID)
    private val folderCache = mutableMapOf<Int, DriveFolderHierarchy>()

    override suspend fun ensureFolderHierarchy(year: Int): Result<DriveFolderHierarchy> = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext Result.failure(
            IllegalStateException("Google Drive が未連携または初期化されていません。")
        )

        folderCache[year]?.let { cached ->
            if (cached.pendingFolderId != null) return@withContext Result.success(cached)
        }

        try {
            // 1. ルートフォルダ "確定申告_{year}" の探索・作成
            val rootFolderName = "確定申告_$year"
            val rootFolderId = findOrCreateFolder(drive, rootFolderName, parentId = "root")

            // 2. サブフォルダ "receipts" (経費) の探索・作成
            val receiptsFolderId = findOrCreateFolder(drive, "receipts", parentId = rootFolderId)

            // 3. サブフォルダ "incomes" (収入) の探索・作成
            val incomesFolderId = findOrCreateFolder(drive, "incomes", parentId = rootFolderId)

            // 4. サブフォルダ "pending" (未解析の一時退避) の探索・作成
            val pendingFolderId = findOrCreateFolder(drive, "pending", parentId = rootFolderId)

            // 5. "transaction_log.csv" の存在確認 (IDのみ取得)
            val csvFileId = findFileId(drive, "transaction_log.csv", parentId = rootFolderId)

            val hierarchy = DriveFolderHierarchy(
                year = year,
                rootFolderId = rootFolderId,
                receiptsFolderId = receiptsFolderId,
                incomesFolderId = incomesFolderId,
                pendingFolderId = pendingFolderId,
                csvFileId = csvFileId
            )
            folderCache[year] = hierarchy
            Result.success(hierarchy)
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            Result.failure(IllegalStateException("Google Drive へのアクセス許可が必要です。表示される同意画面で許可してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveTransaction(
        year: Int,
        transaction: ParsedTransaction,
        fileBytes: ByteArray,
        mimeType: String,
        extension: String
    ): DriveSaveResult = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext DriveSaveResult.Error(
            "Google Drive にサインインしていません。設定画面から連携してください。"
        )

        try {
            // 1. フォルダ階層の解決
            val hierarchy = ensureFolderHierarchy(year).getOrElse {
                return@withContext DriveSaveResult.Error("Drive フォルダ構成の作成に失敗しました: ${it.localizedMessage}", it)
            }

            // 2. アップロード先フォルダと相対パスの決定
            val isExpense = transaction.type == TransactionType.EXPENSE
            val targetFolderId = if (isExpense) hierarchy.receiptsFolderId else hierarchy.incomesFolderId
            val subDirName = if (isExpense) "receipts" else "incomes"
            val cleanExt = if (extension.startsWith(".")) extension else ".$extension"
            val fileName = transaction.toCompliantFileName() + cleanExt
            val relativeDrivePath = "$subDirName/$fileName"

            // 3. 領収書 / 明細ファイルのアップロード（同名ファイルが既にある場合は上書き更新し重複を防ぐ）
            val mediaContent = ByteArrayContent(mimeType, fileBytes)
            val existingFileId = findFileId(drive, fileName, parentId = targetFolderId)
            val uploadedFileId = if (existingFileId != null) {
                drive.files().update(existingFileId, null, mediaContent)
                    .setFields("id, name")
                    .execute()
                    .id
            } else {
                val fileMetadata = File().apply {
                    name = fileName
                    parents = listOf(targetFolderId)
                }
                drive.files().create(fileMetadata, mediaContent)
                    .setFields("id, name")
                    .execute()
                    .id
            }

            // 4. transaction_log.csv の更新 (Read-Modify-Write)
            val csvUpdated = updateCsvLog(drive, hierarchy.rootFolderId, hierarchy.csvFileId, transaction, relativeDrivePath)

            DriveSaveResult.Success(
                fileId = uploadedFileId,
                relativeDrivePath = relativeDrivePath,
                csvUpdated = csvUpdated,
                transaction = transaction
            )
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            DriveSaveResult.Error("Google Drive へのアクセス許可が必要です。同意画面で許可したあと再度お試しください。", e)
        } catch (e: Exception) {
            val raw = "${e::class.simpleName}: ${e.localizedMessage.orEmpty()} ${e.cause?.localizedMessage.orEmpty()}"
            val lower = raw.lowercase()
            val message = if ("auth" in lower || "token" in lower || "consent" in lower || "401" in lower || "403" in lower) {
                "Google Drive の認証に失敗しました（OAuth 同意またはアプリの署名 SHA-1 を確認し、設定画面で再連携してください）。"
            } else {
                "保存中にエラーが発生しました: ${e.localizedMessage}"
            }
            DriveSaveResult.Error(message, e)
        }
    }

    override suspend fun savePendingFile(
        year: Int,
        fileBytes: ByteArray,
        mimeType: String,
        extension: String
    ): Result<PendingDriveFile> = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext Result.failure(
            IllegalStateException("Google Drive にサインインしていません。設定画面から連携してください。")
        )

        try {
            val hierarchy = ensureFolderHierarchy(year).getOrThrow()
            val pendingFolderId = hierarchy.pendingFolderId
                ?: findOrCreateFolder(drive, "pending", parentId = hierarchy.rootFolderId)

            val cleanExt = if (extension.startsWith(".")) extension else ".$extension"
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "pending_$timestamp$cleanExt"

            val metadata = File().apply {
                name = fileName
                parents = listOf(pendingFolderId)
            }
            val media = ByteArrayContent(mimeType, fileBytes)
            val created = drive.files().create(metadata, media)
                .setFields("id, name, mimeType, createdTime")
                .execute()

            Result.success(
                PendingDriveFile(
                    fileId = created.id,
                    name = created.name ?: fileName,
                    mimeType = created.mimeType ?: mimeType,
                    createdTimeMillis = created.createdTime?.value
                )
            )
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            Result.failure(IllegalStateException("Google Drive へのアクセス許可が必要です。表示される同意画面で許可してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listPendingFiles(year: Int): Result<List<PendingDriveFile>> = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext Result.failure(
            IllegalStateException("Google Drive にサインインしていません。")
        )

        try {
            val hierarchy = ensureFolderHierarchy(year).getOrThrow()
            val pendingFolderId = hierarchy.pendingFolderId ?: return@withContext Result.success(emptyList())

            val query = "'$pendingFolderId' in parents and trashed = false"
            val result = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setOrderBy("createdTime desc")
                .setFields("files(id, name, mimeType, createdTime)")
                .execute()

            val items = result.files.orEmpty().map { file ->
                PendingDriveFile(
                    fileId = file.id,
                    name = file.name ?: file.id,
                    mimeType = file.mimeType ?: "application/octet-stream",
                    createdTimeMillis = file.createdTime?.value
                )
            }
            Result.success(items)
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            Result.failure(IllegalStateException("Google Drive へのアクセス許可が必要です。表示される同意画面で許可してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadFileBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext Result.failure(
            IllegalStateException("Google Drive にサインインしていません。")
        )

        try {
            val out = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(out)
            Result.success(out.toByteArray())
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            Result.failure(IllegalStateException("Google Drive へのアクセス許可が必要です。表示される同意画面で許可してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePendingFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val drive = driveServiceProvider() ?: return@withContext Result.failure(
            IllegalStateException("Google Drive にサインインしていません。")
        )

        try {
            drive.files().delete(fileId).execute()
            Result.success(Unit)
        } catch (e: UserRecoverableAuthIOException) {
            onConsentRequired?.invoke(e.intent)
            Result.failure(IllegalStateException("Google Drive へのアクセス許可が必要です。表示される同意画面で許可してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun updateCsvLog(
        drive: Drive,
        rootFolderId: String,
        knownCsvFileId: String?,
        transaction: ParsedTransaction,
        relativeDrivePath: String
    ): Boolean {
        val csvFileId = knownCsvFileId ?: findFileId(drive, "transaction_log.csv", parentId = rootFolderId)

        if (csvFileId == null) {
            // 新規作成
            val initialBytes = csvRecorder.createInitialCsvBytes(transaction, relativeDrivePath)
            val csvMetadata = File().apply {
                name = "transaction_log.csv"
                parents = listOf(rootFolderId)
            }
            val media = ByteArrayContent("text/csv; charset=UTF-8", initialBytes)
            drive.files().create(csvMetadata, media).execute()
        } else {
            // 既存ファイル取得 (Download)
            val outputStream = ByteArrayOutputStream()
            drive.files().get(csvFileId).executeMediaAndDownloadTo(outputStream)
            val currentBytes = outputStream.toByteArray()

            // 追記マージ
            val updatedBytes = csvRecorder.appendTransactionRow(currentBytes, transaction, relativeDrivePath)
            val media = ByteArrayContent("text/csv; charset=UTF-8", updatedBytes)
            drive.files().update(csvFileId, null, media).execute()
        }
        return true
    }

    private fun findOrCreateFolder(drive: Drive, folderName: String, parentId: String): String {
        val existingId = findFolderId(drive, folderName, parentId)
        if (existingId != null) return existingId

        val folderMetadata = File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentId)
        }
        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()
        return folder.id
    }

    private fun findFolderId(drive: Drive, folderName: String, parentId: String): String? {
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$folderName' and '$parentId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    private fun findFileId(drive: Drive, fileName: String, parentId: String): String? {
        val query = "name = '$fileName' and '$parentId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
        return result.files?.firstOrNull()?.id
    }
}
