package com.keihix.app.ui.screens.summary

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keihix.app.data.ai.AdvisorMessage
import com.keihix.app.data.ai.AdvisorResult
import com.keihix.app.data.ai.GeminiTransactionAdvisor
import com.keihix.app.data.drive.CsvTransactionParser
import com.keihix.app.data.drive.CsvTransactionRecorder
import com.keihix.app.data.drive.DriveStorageRepository
import com.keihix.app.data.drive.RecordedTransaction
import com.keihix.app.data.model.TransactionType
import com.keihix.app.data.summary.TransactionSummary
import com.keihix.app.data.summary.TransactionSummaryCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

enum class TransactionFilter {
    ALL, EXPENSE, INCOME
}

data class SummaryUiState(
    val selectedYear: Int = LocalDate.now().year,
    val isLoading: Boolean = false,
    val isUpdatingCsv: Boolean = false,
    val hasCsvFile: Boolean = false,
    val rawCsvBytes: ByteArray? = null,
    val records: List<RecordedTransaction> = emptyList(),
    val summary: TransactionSummary = TransactionSummaryCalculator.calculate(LocalDate.now().year, emptyList()),
    val selectedFilter: TransactionFilter = TransactionFilter.ALL,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    // AI 質問タブ用
    val questionInput: String = "",
    val messages: List<AdvisorMessage> = emptyList(),
    val isAskingAi: Boolean = false,
    val aiErrorMessage: String? = null,
    val lastFailedQuestion: String? = null
)

class SummaryViewModel(
    private val driveRepository: DriveStorageRepository,
    private val advisor: GeminiTransactionAdvisor,
    private val csvParser: CsvTransactionParser = CsvTransactionParser(),
    private val csvRecorder: CsvTransactionRecorder = CsvTransactionRecorder()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    fun loadYear(year: Int = _uiState.value.selectedYear) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedYear = year,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = null
                )
            }

            val result = driveRepository.downloadTransactionCsv(year)
            result.fold(
                onSuccess = { bytes ->
                    if (bytes == null || bytes.isEmpty()) {
                        val emptySummary = TransactionSummaryCalculator.calculate(year, emptyList())
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasCsvFile = false,
                                rawCsvBytes = null,
                                records = emptyList(),
                                summary = emptySummary
                            )
                        }
                    } else {
                        val parsed = csvParser.parseBytes(bytes)
                            .sortedByDescending { it.transaction.date }
                        val summary = TransactionSummaryCalculator.calculate(year, parsed)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasCsvFile = true,
                                rawCsvBytes = bytes,
                                records = parsed,
                                summary = summary
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "Google Drive からの読み込みに失敗しました。"
                        )
                    }
                }
            )
        }
    }

    fun selectPreviousYear() {
        val prev = (_uiState.value.selectedYear - 1).coerceAtLeast(2020)
        if (prev != _uiState.value.selectedYear) {
            clearConversation()
            loadYear(prev)
        }
    }

    fun selectNextYear() {
        val next = (_uiState.value.selectedYear + 1).coerceAtMost(LocalDate.now().year + 1)
        if (next != _uiState.value.selectedYear) {
            clearConversation()
            loadYear(next)
        }
    }

    fun setFilter(filter: TransactionFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun updateQuestionInput(text: String) {
        _uiState.update { it.copy(questionInput = text, aiErrorMessage = null) }
    }

    fun clearConversation() {
        _uiState.update { it.copy(messages = emptyList(), aiErrorMessage = null, lastFailedQuestion = null) }
    }

    fun consumeInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun askAi(questionOverride: String? = null) {
        val state = _uiState.value
        val question = (questionOverride ?: state.questionInput).trim()
        if (question.isEmpty() || state.isAskingAi) return

        executeAiQuestion(
            question = question,
            clearInput = questionOverride == null,
            appendUserMessage = true
        )
    }

    fun retryLastQuestion() {
        val state = _uiState.value
        val failedQuestion = state.lastFailedQuestion?.trim().orEmpty()
        if (failedQuestion.isEmpty() || state.isAskingAi) return

        val lastMessage = state.messages.lastOrNull()
        val alreadyAtTail = lastMessage != null && lastMessage.isUser && lastMessage.text == failedQuestion
        executeAiQuestion(
            question = failedQuestion,
            clearInput = false,
            appendUserMessage = !alreadyAtTail
        )
    }

    fun restoreLastFailedQuestionToInput() {
        val failed = _uiState.value.lastFailedQuestion ?: return
        _uiState.update {
            it.copy(
                questionInput = failed,
                aiErrorMessage = null
            )
        }
    }

    fun restoreTextToInput(text: String) {
        _uiState.update {
            it.copy(
                questionInput = text,
                aiErrorMessage = null
            )
        }
    }

    fun buildCopyablePromptWithData(question: String): String {
        val state = _uiState.value
        return advisor.buildShareablePrompt(
            question = question,
            summary = state.summary,
            records = state.records
        )
    }

    private fun executeAiQuestion(
        question: String,
        clearInput: Boolean,
        appendUserMessage: Boolean
    ) {
        val state = _uiState.value
        if (state.records.isEmpty()) {
            _uiState.update {
                it.copy(aiErrorMessage = "${state.selectedYear}年の取引データがまだ登録されていないため質問できません。")
            }
            return
        }

        val currentMessages = state.messages
        val historyForPrompt = if (appendUserMessage) {
            currentMessages
        } else {
            currentMessages.dropLast(1)
        }
        val updatedMessages = if (appendUserMessage) {
            currentMessages + AdvisorMessage(isUser = true, text = question)
        } else {
            currentMessages
        }

        _uiState.update {
            it.copy(
                questionInput = if (clearInput) "" else it.questionInput,
                messages = updatedMessages,
                isAskingAi = true,
                aiErrorMessage = null,
                lastFailedQuestion = null
            )
        }

        viewModelScope.launch {
            val result = advisor.ask(
                question = question,
                summary = state.summary,
                records = state.records,
                history = historyForPrompt
            )
            when (result) {
                is AdvisorResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAskingAi = false,
                            lastFailedQuestion = null,
                            messages = it.messages + AdvisorMessage(
                                isUser = false,
                                text = result.answer,
                                suggestedDeleteRowIds = result.suggestedDeleteRowIds
                            )
                        )
                    }
                }
                is AdvisorResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAskingAi = false,
                            aiErrorMessage = result.message,
                            lastFailedQuestion = question
                        )
                    }
                }
            }
        }
    }

    /**
     * 読み込み済みの transaction_log.csv を端末の一時キャッシュへ書き出し、
     * 他アプリへ共有する Intent (ACTION_SEND) を生成する。
     */
    fun createCsvShareIntent(context: Context): Intent? {
        val state = _uiState.value
        val bytes = state.rawCsvBytes ?: return null
        return try {
            val exportFile = File(context.cacheDir, "確定申告_${state.selectedYear}_transaction_log.csv")
            exportFile.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "KeihiX ${state.selectedYear}年 取引台帳 (transaction_log.csv)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "CSV エクスポートの準備に失敗しました: ${e.localizedMessage}") }
            null
        }
    }

    fun deleteTransactionsByRowIds(rowIds: List<Int>) {
        val state = _uiState.value
        if (state.isUpdatingCsv || rowIds.isEmpty()) return

        val targetSet = rowIds.toSet()
        val deletedCount = state.records.count { it.rowIndex in targetSet }
        if (deletedCount == 0) {
            _uiState.update { it.copy(errorMessage = "対象の取引が台帳に見つかりませんでした。") }
            return
        }

        val remaining = state.records
            .filterNot { it.rowIndex in targetSet }
            .sortedBy { it.rowIndex }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingCsv = true, errorMessage = null) }
            val newBytes = csvRecorder.buildCsvBytes(remaining)
            val result = driveRepository.updateTransactionCsv(state.selectedYear, newBytes)
            result.fold(
                onSuccess = {
                    val parsed = csvParser.parseBytes(newBytes)
                        .sortedByDescending { it.transaction.date }
                    val newSummary = TransactionSummaryCalculator.calculate(state.selectedYear, parsed)
                    val clearedMessages = state.messages.map { msg ->
                        if (msg.suggestedDeleteRowIds.isNotEmpty()) {
                            msg.copy(suggestedDeleteRowIds = emptyList())
                        } else {
                            msg
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isUpdatingCsv = false,
                            hasCsvFile = true,
                            rawCsvBytes = newBytes,
                            records = parsed,
                            summary = newSummary,
                            messages = clearedMessages,
                            infoMessage = "${deletedCount}件の取引を台帳から削除しました"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isUpdatingCsv = false,
                            errorMessage = err.localizedMessage ?: "台帳の更新に失敗しました。"
                        )
                    }
                }
            )
        }
    }

    fun filteredRecords(): List<RecordedTransaction> {
        val state = _uiState.value
        return when (state.selectedFilter) {
            TransactionFilter.ALL -> state.records
            TransactionFilter.EXPENSE -> state.records.filter { it.transaction.type == TransactionType.EXPENSE }
            TransactionFilter.INCOME -> state.records.filter { it.transaction.type == TransactionType.INCOME }
        }
    }
}
