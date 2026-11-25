package com.keihix.app.data.ai

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.keihix.app.data.model.ParsedTransaction
import com.keihix.app.data.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface ParseResult {
    data class Success(val transaction: ParsedTransaction) : ParseResult
    data class Error(
        val message: String,
        val rawOutput: String? = null,
        val isRetryable: Boolean = true
    ) : ParseResult
}

class GeminiTransactionParser(
    private val secureKeyManager: SecureKeyManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) {

    private val systemPrompt = """
        あなたは日本国内の個人事業主・副業エンジニア向けの領収書および売上明細解析AIです。
        入力された領収書、レシート、請求書、売上明細、支払通知書などの画像から情報を抽出し、
        指定されたJSONスキーマに完全準拠したJSONオブジェクトのみを出力してください。
        Markdownの装飾（```json や ``` など）や前置き・後書きは一切含めず、純粋なJSON文字列のみを返してください。

        【抽出・推論ルール】
        1. type:
           - 経費（レシート、領収書、購入明細、Amazon注文書など）は "expense"
           - 収入（プラットフォームからの支払明細書、アドセンス振込通知、原稿料明細など）は "income"
        2. date:
           - 取引日または売上確定日を "YYYY-MM-DD" 形式で出力。年は明記がなければ現在の年度（2026年）を補完。
        3. amount:
           - 支払総額または売上総額（税込）を半角整数（数値）で出力。
        4. vendor:
           - レシート発行元の企業名・店舗名、または収入の支払元プラットフォーム名（例: "Amazon", "note株式会社", "セブン-イレブン"）。
        5. category (勘定科目):
           - 一般的な日本の確定申告科目から最も適切なものを1つ選択（例: "消耗品費", "通信費", "旅費交通費", "会議費", "新聞図書費", "支払手数料", "売上"）。
        6. fee (数値/任意):
           - 収入においてプラットフォーム利用料や振込手数料が明記されている場合はその金額。なければ null。
        7. withholding_tax (数値/任意):
           - 収入において源泉徴収税額が明記されている場合はその金額。なければ null。
        8. invoice_number (文字列/任意):
           - "T" で始まる13桁の適格請求書発行事業者登録番号があれば抽出。なければ null。
        9. memo (文字列/任意):
           - 主な購入品目やサービス内容の簡潔な要約（例: "クラウドサーバー利用料", "技術書2冊"）。
        10. confidence_note (文字列/任意):
            - 画像が不鮮明な箇所や、推測・補完した内容があれば簡潔に記述。特になければ null。

        【JSONスキーマ形式】
        {
          "type": "expense" | "income",
          "date": "2026-10-25",
          "amount": 1980,
          "vendor": "Amazon",
          "category": "消耗品費",
          "fee": null,
          "withholding_tax": null,
          "invoice_number": "T1234567890123",
          "memo": "USB-Cケーブル",
          "confidence_note": null
        }
    """.trimIndent()

    suspend fun parseReceiptImage(bitmap: Bitmap): ParseResult = executeParse(
        missingKeyMessage = "Gemini APIキーが設定されていません。設定画面で登録してください。"
    ) {
        content {
            image(bitmap)
            text("この画像から経費または収入の情報を抽出し、指定のJSONフォーマットで出力してください。")
        }
    }

    /**
     * PDFや各種画像（JPEG/PNG等）のバイナリデータ（ByteArray）から抽出を行う
     */
    suspend fun parseDocument(bytes: ByteArray, mimeType: String): ParseResult = executeParse(
        missingKeyMessage = "Gemini APIキーが設定されていません。"
    ) {
        content {
            blob(mimeType, bytes)
            text("このドキュメント/明細書から経費または収入の情報を抽出し、指定のJSONフォーマットで出力してください。")
        }
    }

    private suspend fun executeParse(
        missingKeyMessage: String,
        contentBuilder: () -> Content
    ): ParseResult = withContext(Dispatchers.IO) {
        val apiKey = secureKeyManager.getGeminiApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext ParseResult.Error(
                message = missingKeyMessage,
                isRetryable = false
            )
        }

        try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-3.1-flash-lite",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    temperature = 0.1f
                },
                systemInstruction = content { text(systemPrompt) }
            )

            val response = generativeModel.generateContent(contentBuilder())
            val responseText = response.text?.trim()

            if (responseText.isNullOrBlank()) {
                return@withContext ParseResult.Error(
                    message = "Gemini API から空の応答が返されました。再解析するか手入力で登録してください。",
                    isRetryable = true
                )
            }

            // コードブロック装飾が混ざっている場合のフェイルセーフ
            val cleanedJson = responseText
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            try {
                val transaction = json.decodeFromString<ParsedTransaction>(cleanedJson)
                ParseResult.Success(transaction)
            } catch (e: Exception) {
                ParseResult.Error(
                    message = "AI応答の解析に失敗しました。再解析するか手入力で修正してください。",
                    rawOutput = cleanedJson,
                    isRetryable = true
                )
            }
        } catch (e: Exception) {
            mapExceptionToError(e)
        }
    }

    companion object {
        /**
         * Google AI SDK の例外（503 UNAVAILABLE 時の GRpcError パース失敗を含む）を
         * ユーザー向けの分かりやすいエラーメッセージに変換する。
         */
        internal fun mapExceptionToError(e: Throwable): ParseResult.Error {
            val raw = buildString {
                append(e.message.orEmpty())
                e.cause?.message?.let {
                    append(' ')
                    append(it)
                }
            }
            val lower = raw.lowercase()

            return when {
                "503" in lower || "unavailable" in lower || "high demand" in lower || "overloaded" in lower -> {
                    ParseResult.Error(
                        message = "現在 Gemini API が混雑しています（503）。少し待ってから「再解析」するか、「手入力で進む」から登録してください。",
                        rawOutput = raw,
                        isRetryable = true
                    )
                }
                "429" in lower || "quota" in lower || "resource_exhausted" in lower || "rate limit" in lower -> {
                    ParseResult.Error(
                        message = "Gemini API の利用上限（レート制限）に達しました。少し待ってから再解析するか、手入力で登録してください。",
                        rawOutput = raw,
                        isRetryable = true
                    )
                }
                "401" in lower || "403" in lower || "api_key_invalid" in lower || "api key not valid" in lower -> {
                    ParseResult.Error(
                        message = "Gemini API キーが無効または権限がありません。設定画面でキーを確認してください。",
                        rawOutput = raw,
                        isRetryable = false
                    )
                }
                e is UnknownHostException || e is SocketTimeoutException || e is IOException -> {
                    ParseResult.Error(
                        message = "ネットワークに接続できませんでした。接続を確認して再解析するか、手入力で登録してください。",
                        rawOutput = raw,
                        isRetryable = true
                    )
                }
                else -> {
                    ParseResult.Error(
                        message = "AI解析に失敗しました。再解析するか「手入力で進む」から登録してください。",
                        rawOutput = raw,
                        isRetryable = true
                    )
                }
            }
        }
    }
}
