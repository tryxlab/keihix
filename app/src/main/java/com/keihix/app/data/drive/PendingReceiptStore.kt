package com.keihix.app.data.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class LocalPendingItem(
    val localId: String,
    val displayName: String,
    val fileName: String,
    val mimeType: String,
    val extension: String,
    val year: Int,
    val createdAtMillis: Long,
    val driveFileId: String? = null
)

/**
 * オフライン（圏外）や Gemini 503 エラー時でも確実に証憑を保護するため、
 * アプリ専用の永続領域 (filesDir/pending_analysis/) に領収書画像・PDFを即時退避するストア。
 *
 * cacheDir ではなく filesDir 配下に保存するため OS による自動キャッシュ削除の対象にならず、
 * オンライン復帰時には PendingBackupWorker によって Google Drive の pending/ にも自動同期される。
 */
class PendingReceiptStore(
    private val baseDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    suspend fun saveLocalPending(
        year: Int,
        bytes: ByteArray,
        mimeType: String,
        extension: String
    ): LocalPendingItem = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()

        val cleanExt = extension.removePrefix(".").ifBlank { "jpg" }
        val localId = UUID.randomUUID().toString().take(8)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "pending_${timestamp}_$localId.$cleanExt"
        val displayName = "未解析_${timestamp}.$cleanExt"

        val dataFile = File(baseDir, fileName)
        dataFile.writeBytes(bytes)

        val item = LocalPendingItem(
            localId = localId,
            displayName = displayName,
            fileName = fileName,
            mimeType = mimeType,
            extension = cleanExt,
            year = year,
            createdAtMillis = System.currentTimeMillis(),
            driveFileId = null
        )
        writeMetadata(item)
        item
    }

    suspend fun listLocalPending(): List<LocalPendingItem> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull { metaFile ->
                runCatching {
                    val item = json.decodeFromString<LocalPendingItem>(metaFile.readText())
                    if (File(baseDir, item.fileName).exists()) item else null
                }.getOrNull()
            }
            .sortedByDescending { it.createdAtMillis }
    }

    suspend fun getById(localId: String): LocalPendingItem? = withContext(Dispatchers.IO) {
        val metaFile = metaFileFor(localId)
        if (!metaFile.exists()) return@withContext null
        runCatching { json.decodeFromString<LocalPendingItem>(metaFile.readText()) }.getOrNull()
    }

    suspend fun readBytes(item: LocalPendingItem): ByteArray? = withContext(Dispatchers.IO) {
        val dataFile = File(baseDir, item.fileName)
        if (!dataFile.exists()) return@withContext null
        runCatching { dataFile.readBytes() }.getOrNull()
    }

    suspend fun markSyncedToDrive(localId: String, driveFileId: String): LocalPendingItem? = withContext(Dispatchers.IO) {
        val current = getById(localId) ?: return@withContext null
        val updated = current.copy(driveFileId = driveFileId)
        writeMetadata(updated)
        updated
    }

    suspend fun deleteLocalPending(localId: String) = withContext(Dispatchers.IO) {
        val current = getById(localId)
        if (current != null) {
            File(baseDir, current.fileName).delete()
        }
        metaFileFor(localId).delete()
    }

    private fun writeMetadata(item: LocalPendingItem) {
        metaFileFor(item.localId).writeText(json.encodeToString(item))
    }

    private fun metaFileFor(localId: String): File =
        File(baseDir, "$localId$META_SUFFIX")

    companion object {
        private const val DIR_NAME = "pending_analysis"
        private const val META_SUFFIX = ".meta.json"
    }
}
