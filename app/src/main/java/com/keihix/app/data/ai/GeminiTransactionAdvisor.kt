package com.keihix.app.data.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.keihix.app.data.drive.RecordedTransaction
import com.keihix.app.data.security.SecureKeyManager
import com.keihix.app.data.summary.TransactionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AdvisorMessage(
    val isUser: Boolean,
    val text: String,
    val suggestedDeleteRowIds: List<Int> = emptyList()
)

sealed interface AdvisorResult {
    data class Success(
        val answer: String,
        val suggestedDeleteRowIds: List<Int> = emptyList()
    ) : AdvisorResult
    data class Error(val message: String, val isRetryable: Boolean = true) : AdvisorResult
}

/**
 * ユーザーの Gemini API キー（BYOK）を使って、Google Drive から読み込んだ
 * transaction_log.csv および集計サマリに対して自然言語で質問できるアシスタント。
 *
 * 計算ミス防止のため、アプリ側で事前計算した正確なサマリ数値と明細データの両方を
 * コンテキストとして渡します。
 */
class GeminiTransactionAdvisor(
    private val secureKeyManager: SecureKeyManager
) {

    private val systemPrompt = """
        あなたは日本国内の個人事業主・副業エンジニア向けアプリ「KeihiX」に内蔵された、
        取引台帳データ（transaction_log.csv）の集計・検索・確認アシスタントです。

        【回答ルール】
        1. 金額や件数は、提供された【事前計算サマリ】および【取引明細データ】に記載された事実のみに基づいて回答してください。データにない金額や取引を推測・創作しないでください。
        2. スマートフォンの縦画面で読みやすいよう、Markdown のテーブル記法（|...|）は使わず、簡潔な箇条書きと短い段落で回答してください。
        3. 金額は「¥1,980」のようにカンマ区切りで読みやすく表記してください。
        4. 【法的境界・免責の遵守】あなたは税理士ではありません。個別の税務相談や「これは確実に経費にできます」といった断定的な税務判断は行わず、あくまで台帳データの整理・確認・一般的な勘定科目区分の参考情報の提示にとどめてください。税務判断が必要な質問には「最終判断は所轄の税務署または税理士へご確認ください」と簡潔に添えてください。
        5. 【削除提案タグ】ユーザーが特定の取引や重複行の削除を希望した場合、削除対象とすべき明細の row_id を回答の最終行に `[[DELETE_ROWS:2]]` または `[[DELETE_ROWS:2,5]]` の形式で出力してください（重複が2件ある場合は1件残して削除すべき片方の row_id のみを指定）。回答本文の末尾には「下の削除ボタンから確認のうえ台帳を更新できます」と簡潔に案内してください。削除依頼ではない単なる集計・確認の質問ではこのタグを出力しないでください。
    """.trimIndent()

    suspend fun ask(
        question: String,
        summary: TransactionSummary,
        records: List<RecordedTransaction>,
        history: List<AdvisorMessage> = emptyList()
    ): AdvisorResult = withContext(Dispatchers.IO) {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            return@withContext AdvisorResult.Error("質問を入力してください。", isRetryable = false)
        }

        val apiKey = secureKeyManager.getGeminiApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext AdvisorResult.Error(
                message = "Gemini API キーが設定されていません。設定画面で登録してください。",
                isRetryable = false
            )
        }

        try {
            val promptText = buildPrompt(
                question = trimmedQuestion,
                summary = summary,
                records = records,
                history = history
            )

            val answer = generateWith503Fallback(apiKey = apiKey, promptText = promptText)
            if (answer.isNullOrBlank()) {
                AdvisorResult.Error("AI から空の応答が返されました。もう一度お試しください。", isRetryable = true)
            } else {
                val (cleanText, deleteIds) = parseDeleteActionTag(answer)
                AdvisorResult.Success(
                    answer = cleanText,
                    suggestedDeleteRowIds = deleteIds
                )
            }
        } catch (e: Exception) {
            Log.e("GeminiAdvisor", "AI question failed", e)
            val mapped = GeminiTransactionParser.mapExceptionToError(e)
            val rawLower = mapped.rawOutput?.lowercase().orEmpty()
            val detail = listOfNotNull(
                e::class.simpleName,
                mapped.rawOutput?.take(200)?.takeIf { it.isNotBlank() }
            ).joinToString(": ")

            val userMessage = when {
                "404" in rawLower || "not found" in rawLower ->
                    "指定された Gemini モデルが見つかりませんでした (404)。\n(詳細: $detail)"
                else -> {
                    val base = mapped.message
                        .replace("AI解析に失敗しました。", "AI への質問に失敗しました。")
                        .replace("再解析", "再送信")
                        .replace("「手入力で進む」から登録してください。", "少し待ってから再度お試しください。")
                    if (base.startsWith("AI への質問に失敗しました") && detail.isNotBlank()) {
                        "$base\n(詳細: $detail)"
                    } else {
                        base
                    }
                }
            }
            AdvisorResult.Error(
                message = userMessage,
                isRetryable = mapped.isRetryable
            )
        }
    }

    private suspend fun generateWith503Fallback(
        apiKey: String,
        promptText: String
    ): String? {
        suspend fun callModel(modelName: String): String? {
            val model = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.2f
                },
                systemInstruction = content { text(systemPrompt) }
            )
            return model.generateContent(content { text(promptText) }).text?.trim()
        }

        return try {
            callModel(PRIMARY_MODEL)
        } catch (firstError: Exception) {
            if (!isServiceUnavailableError(firstError)) throw firstError
            Log.w("GeminiAdvisor", "Primary model 503, retrying after 1500ms", firstError)
            delay(1500)
            try {
                callModel(PRIMARY_MODEL)
            } catch (retryError: Exception) {
                if (!isServiceUnavailableError(retryError)) throw retryError
                Log.w("GeminiAdvisor", "Primary model still 503, falling back to $FALLBACK_MODEL", retryError)
                callModel(FALLBACK_MODEL)
            }
        }
    }

    /**
     * 他 AI アプリへの貼り付け用に、事前計算サマリ＋明細＋質問のテキストを生成する。
     */
    fun buildShareablePrompt(
        question: String,
        summary: TransactionSummary,
        records: List<RecordedTransaction>
    ): String = buildPrompt(
        question = question,
        summary = summary,
        records = records,
        history = emptyList()
    )

    internal fun buildPrompt(
        question: String,
        summary: TransactionSummary,
        records: List<RecordedTransaction>,
        history: List<AdvisorMessage>
    ): String {
        // コンテキスト肥大化を防ぐため、直近最大 500 件までを含める
        val cappedRecords = records.takeLast(500)
        val csvRows = buildString {
            appendLine("row_id,date,type,amount,vendor,category,fee,withholding_tax,invoice_number,memo")
            cappedRecords.forEach { rec ->
                val t = rec.transaction
                appendLine(
                    listOf(
                        rec.rowIndex.toString(),
                        t.date,
                        t.type.name.lowercase(),
                        t.amount.toString(),
                        t.vendor.replace(",", " "),
                        t.category.replace(",", " "),
                        (t.fee ?: 0L).toString(),
                        (t.withholdingTax ?: 0L).toString(),
                        t.invoiceNumber.orEmpty(),
                        t.memo.orEmpty().replace(",", " ").replace("\n", " ")
                    ).joinToString(",")
                )
            }
        }

        val recentHistory = history.takeLast(6)

        return buildString {
            appendLine("【事前計算サマリ（正確な集計値）】")
            appendLine(summary.toPlainTextReport())
            appendLine()
            appendLine("【取引明細データ（全${records.size}件中 ${cappedRecords.size}件）】")
            appendLine(csvRows.trimEnd())

            if (recentHistory.isNotEmpty()) {
                appendLine()
                appendLine("【直前の会話履歴】")
                recentHistory.forEach { msg ->
                    val role = if (msg.isUser) "ユーザー" else "アシスタント"
                    appendLine("$role: ${msg.text}")
                }
            }

            appendLine()
            appendLine("【今回の質問】")
            appendLine(question)
        }
    }

    companion object {
        private const val PRIMARY_MODEL = "gemini-3.1-flash-lite"
        private const val FALLBACK_MODEL = "gemini-2.5-flash"
        private val DELETE_TAG_REGEX = Regex("""\[\[DELETE_ROWS:([0-9,\s]+)]]""")

        internal fun isServiceUnavailableError(e: Throwable): Boolean {
            val raw = buildString {
                append(e.message.orEmpty())
                e.cause?.message?.let {
                    append(' ')
                    append(it)
                }
            }.lowercase()
            return "503" in raw || "unavailable" in raw || "high demand" in raw || "overloaded" in raw
        }

        /**
         * AI 応答に含まれる `[[DELETE_ROWS:...]]` タグを解析して除去し、
         * クリーンな本文と削除対象 row_id リストを返す。
         */
        internal fun parseDeleteActionTag(rawText: String): Pair<String, List<Int>> {
            val ids = linkedSetOf<Int>()
            DELETE_TAG_REGEX.findAll(rawText).forEach { match ->
                match.groupValues[1]
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .forEach { ids.add(it) }
            }
            val cleaned = rawText
                .replace(DELETE_TAG_REGEX, "")
                .lines()
                .dropLastWhile { it.isBlank() }
                .joinToString("\n")
                .trim()
            return cleaned to ids.toList()
        }
    }
}
