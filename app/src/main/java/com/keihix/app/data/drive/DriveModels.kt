package com.keihix.app.data.drive

import com.keihix.app.data.model.ParsedTransaction

/**
 * Google Drive 上の確定申告フォルダ階層のIDキャッシュ
 */
data class DriveFolderHierarchy(
    val year: Int,
    val rootFolderId: String,
    val receiptsFolderId: String,
    val incomesFolderId: String,
    val pendingFolderId: String? = null,
    val csvFileId: String? = null
)

/**
 * Drive の pending フォルダに一時退避された未解析ファイル
 */
data class PendingDriveFile(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val createdTimeMillis: Long? = null
)

/**
 * トランザクション保存処理の結果
 */
sealed interface DriveSaveResult {
    data class Success(
        val fileId: String,
        val relativeDrivePath: String,
        val csvUpdated: Boolean,
        val transaction: ParsedTransaction
    ) : DriveSaveResult

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : DriveSaveResult
}

/**
 * Google アカウントの連携状態
 */
sealed interface GoogleAuthStatus {
    object NotConnected : GoogleAuthStatus
    object Connecting : GoogleAuthStatus
    data class Connected(
        val email: String,
        val displayName: String? = null
    ) : GoogleAuthStatus
    data class Error(val message: String) : GoogleAuthStatus
}
