package com.keihix.app.ui.screens.summary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keihix.app.data.ai.AdvisorMessage
import com.keihix.app.data.drive.RecordedTransaction
import com.keihix.app.data.model.TransactionType
import com.keihix.app.data.summary.CategorySummary
import com.keihix.app.data.summary.MonthlySummary
import com.keihix.app.data.summary.TransactionSummary
import java.text.NumberFormat
import java.util.Locale

private val PRESET_AI_QUESTIONS = listOf(
    "確定申告の転記用に科目別合計をまとめて",
    "支出が多い上位3件と傾向は？",
    "重複していそうな取引はある？",
    "今月の経費と収入を教えて",
    "源泉徴収税と手数料の合計は？"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    viewModel: SummaryViewModel,
    isGeminiKeyConfigured: Boolean,
    isDriveConnected: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var pendingDeleteRecords by remember { mutableStateOf<List<RecordedTransaction>?>(null) }

    pendingDeleteRecords?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!uiState.isUpdatingCsv) pendingDeleteRecords = null },
            title = { Text("台帳から取引を削除しますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "以下の ${targets.size} 件を Google Drive の transaction_log.csv から削除します（証憑ファイル本体は Drive に残ります）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    targets.forEach { rec ->
                        val t = rec.transaction
                        Text(
                            text = "・${t.date}  ${t.vendor.ifBlank { "(取引先未設定)" }}  ${formatYen(t.amount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTransactionsByRowIds(targets.map { it.rowIndex })
                        pendingDeleteRecords = null
                    },
                    enabled = !uiState.isUpdatingCsv,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteRecords = null },
                    enabled = !uiState.isUpdatingCsv
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    LaunchedEffect(isDriveConnected) {
        if (isDriveConnected) {
            viewModel.loadYear()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.consumeInfoMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("収支サマリ・AI質問", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (uiState.hasCsvFile) {
                        IconButton(
                            onClick = {
                                val intent = viewModel.createCsvShareIntent(context)
                                if (intent != null) {
                                    context.startActivity(Intent.createChooser(intent, "transaction_log.csv を共有"))
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "CSVを共有")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.loadYear() },
                        enabled = !uiState.isLoading && isDriveConnected
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "最新データを読み込み")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 年度セレクター（スマホで片手操作しやすいコンパクトバー）
            YearSelectorBar(
                year = uiState.selectedYear,
                transactionCount = uiState.summary.transactionCount,
                isLoading = uiState.isLoading,
                onPreviousYear = { viewModel.selectPreviousYear() },
                onNextYear = { viewModel.selectNextYear() }
            )

            // 3タブ切り替え（サマリ / 明細 / AI質問）
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("サマリ", maxLines = 1) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Text(
                            text = if (uiState.records.isNotEmpty()) "明細 (${uiState.records.size})" else "明細",
                            maxLines = 1
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("AI質問", maxLines = 1) }
                )
            }

            if (!isDriveConnected) {
                EmptyStateCard(
                    title = "Google Drive が未連携です",
                    description = "設定画面で Google アカウントを連携すると、Drive 上の transaction_log.csv を自動で読み込んで集計・AI質問ができます。",
                    actionLabel = "設定画面を開く",
                    onAction = onNavigateToSettings
                )
                return@Column
            }

            if (uiState.isLoading && uiState.records.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Google Drive から ${uiState.selectedYear}年 の台帳を読み込み中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Column
            }

            uiState.errorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { viewModel.loadYear() }) {
                            Text("再読み込み")
                        }
                    }
                }
            }

            when (selectedTabIndex) {
                0 -> SummaryOverviewTab(
                    summary = uiState.summary,
                    hasCsvFile = uiState.hasCsvFile,
                    onShareCsv = {
                        val intent = viewModel.createCsvShareIntent(context)
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "transaction_log.csv を共有"))
                        }
                    },
                    onCopyReport = {
                        copyToClipboard(context, "KeihiX 収支サマリ", uiState.summary.toPlainTextReport())
                        scope.launch {
                            snackbarHostState.showSnackbar("転記用サマリをクリップボードにコピーしました")
                        }
                    },
                    onOpenAiTab = { selectedTabIndex = 2 }
                )
                1 -> TransactionListTab(
                    records = uiState.records,
                    selectedFilter = uiState.selectedFilter,
                    isUpdating = uiState.isUpdatingCsv,
                    onSelectFilter = { viewModel.setFilter(it) },
                    onRequestDelete = { rec -> pendingDeleteRecords = listOf(rec) }
                )
                2 -> AiQuestionTab(
                    uiState = uiState,
                    isGeminiKeyConfigured = isGeminiKeyConfigured,
                    onQuestionChange = { viewModel.updateQuestionInput(it) },
                    onSendQuestion = { viewModel.askAi() },
                    onSelectPreset = { viewModel.askAi(it) },
                    onRetryLastQuestion = { viewModel.retryLastQuestion() },
                    onRestoreLastFailedQuestion = { viewModel.restoreLastFailedQuestionToInput() },
                    onRestoreTextToInput = { text -> viewModel.restoreTextToInput(text) },
                    onCopyText = { label, text, feedback ->
                        copyToClipboard(context, label, text)
                        scope.launch {
                            snackbarHostState.showSnackbar(feedback)
                        }
                    },
                    onCopyPromptWithData = { failedQ ->
                        val payload = viewModel.buildCopyablePromptWithData(failedQ)
                        copyToClipboard(context, "KeihiX 質問・集計データ", payload)
                        scope.launch {
                            snackbarHostState.showSnackbar("質問と集計データをコピーしました（他AIアプリへ貼り付けできます）")
                        }
                    },
                    onRequestDelete = { targets -> pendingDeleteRecords = targets },
                    onClearHistory = { viewModel.clearConversation() },
                    onNavigateToSettings = onNavigateToSettings
                )
            }
        }
    }
}

@Composable
private fun YearSelectorBar(
    year: Int,
    transactionCount: Int,
    isLoading: Boolean,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPreviousYear, enabled = !isLoading) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "前年")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${year}年分",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${transactionCount}件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLoading) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            IconButton(onClick = onNextYear, enabled = !isLoading) {
                Icon(Icons.Default.ChevronRight, contentDescription = "翌年")
            }
        }
    }
}

@Composable
private fun SummaryOverviewTab(
    summary: TransactionSummary,
    hasCsvFile: Boolean,
    onShareCsv: () -> Unit,
    onCopyReport: () -> Unit,
    onOpenAiTab: () -> Unit
) {
    if (!hasCsvFile || summary.transactionCount == 0) {
        EmptyStateCard(
            title = "${summary.year}年の取引データがありません",
            description = "ホーム画面でレシート撮影や PDF 共有から Google Drive に保存すると、ここに収支サマリとグラフが表示されます。",
            actionLabel = null,
            onAction = null
        )
        return
    }

    var showExpenseCategories by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. ヒーロー収支カード
        HeroBalanceCard(summary = summary)

        // 2. クイックアクション（CSV共有・転記コピー・AIに質問）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onShareCsv,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("CSV共有", maxLines = 1, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onCopyReport,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("サマリコピー", maxLines = 1, fontSize = 13.sp)
            }
        }

        // AI質問への導線カード
        FilledTonalButton(
            onClick = onOpenAiTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("このデータについて Gemini に質問する")
        }

        // 3. 勘定科目別内訳（スマホ縦画面で見やすい横棒グラフ）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "科目別内訳",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = showExpenseCategories,
                            onClick = { showExpenseCategories = true },
                            label = { Text("経費") }
                        )
                        FilterChip(
                            selected = !showExpenseCategories,
                            onClick = { showExpenseCategories = false },
                            label = { Text("収入") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val targetList = if (showExpenseCategories) summary.expenseByCategory else summary.incomeByCategory
                if (targetList.isEmpty()) {
                    Text(
                        text = if (showExpenseCategories) "経費データがありません" else "収入データがありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        targetList.forEach { cat ->
                            CategoryBarRow(
                                item = cat,
                                isExpense = showExpenseCategories
                            )
                        }
                    }
                }
            }
        }

        // 4. 月別推移（取引がある月をスマホで見やすく縦並び表示）
        if (summary.monthlyBreakdown.isNotEmpty()) {
            MonthlyBreakdownCard(months = summary.monthlyBreakdown)
        }

        // 免責フッター
        Text(
            text = "※ この集計は Google Drive 上の transaction_log.csv に基づく参考値です。確定申告の最終金額・勘定科目はご自身の責任でご確認ください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HeroBalanceCard(summary: TransactionSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "差引収支（収入 − 経費）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatYenSigned(summary.netBalance),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "収入合計 (${summary.incomeCount}件)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatYen(summary.totalIncome),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "経費合計 (${summary.expenseCount}件)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatYen(summary.totalExpense),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (summary.totalFee > 0L || summary.totalWithholdingTax > 0L) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (summary.totalFee > 0L) {
                        Text(
                            text = "手数料計: ${formatYen(summary.totalFee)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    if (summary.totalWithholdingTax > 0L) {
                        Text(
                            text = "源泉徴収税計: ${formatYen(summary.totalWithholdingTax)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBarRow(
    item: CategorySummary,
    isExpense: Boolean
) {
    val pct = (item.ratio * 100).toInt()
    val barColor = if (isExpense) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${item.category} (${item.count}件)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${formatYen(item.totalAmount)}  $pct%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(item.ratio.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun MonthlyBreakdownCard(months: List<MonthlySummary>) {
    val maxAmount = months.maxOfOrNull { maxOf(it.incomeAmount, it.expenseAmount) }?.coerceAtLeast(1L) ?: 1L

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "月別推移",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                months.forEach { m ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${m.month}月 (${m.count}件)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "収 ${formatYen(m.incomeAmount)} / 経 ${formatYen(m.expenseAmount)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (m.incomeAmount > 0L) {
                            MiniProgressBar(
                                fraction = m.incomeAmount.toFloat() / maxAmount.toFloat(),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                        }
                        if (m.expenseAmount > 0L) {
                            MiniProgressBar(
                                fraction = m.expenseAmount.toFloat() / maxAmount.toFloat(),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniProgressBar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

@Composable
private fun TransactionListTab(
    records: List<RecordedTransaction>,
    selectedFilter: TransactionFilter,
    isUpdating: Boolean,
    onSelectFilter: (TransactionFilter) -> Unit,
    onRequestDelete: (RecordedTransaction) -> Unit
) {
    val filtered = remember(records, selectedFilter) {
        when (selectedFilter) {
            TransactionFilter.ALL -> records
            TransactionFilter.EXPENSE -> records.filter { it.transaction.type == TransactionType.EXPENSE }
            TransactionFilter.INCOME -> records.filter { it.transaction.type == TransactionType.INCOME }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == TransactionFilter.ALL,
                onClick = { onSelectFilter(TransactionFilter.ALL) },
                label = { Text("すべて (${records.size})") }
            )
            FilterChip(
                selected = selectedFilter == TransactionFilter.EXPENSE,
                onClick = { onSelectFilter(TransactionFilter.EXPENSE) },
                label = { Text("経費") }
            )
            FilterChip(
                selected = selectedFilter == TransactionFilter.INCOME,
                onClick = { onSelectFilter(TransactionFilter.INCOME) },
                label = { Text("収入") }
            )
        }

        if (filtered.isEmpty()) {
            EmptyStateCard(
                title = "表示する明細がありません",
                description = "条件に一致する取引がまだ登録されていません。",
                actionLabel = null,
                onAction = null
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { "${it.rowIndex}_${it.transaction.date}_${it.transaction.amount}" }) { rec ->
                    TransactionRowCard(
                        record = rec,
                        isUpdating = isUpdating,
                        onDeleteClick = { onRequestDelete(rec) }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun TransactionRowCard(
    record: RecordedTransaction,
    isUpdating: Boolean,
    onDeleteClick: () -> Unit
) {
    val t = record.transaction
    val isIncome = t.type == TransactionType.INCOME

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = t.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = if (isIncome) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = t.category,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (isIncome) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isIncome) "+${formatYen(t.amount)}" else formatYen(t.amount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isIncome) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDeleteClick,
                        enabled = !isUpdating,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "この取引を削除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = t.vendor.ifBlank { "(取引先未設定)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!t.memo.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = t.memo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val metaParts = buildList {
                t.fee?.takeIf { it > 0 }?.let { add("手数料 ${formatYen(it)}") }
                t.withholdingTax?.takeIf { it > 0 }?.let { add("源泉税 ${formatYen(it)}") }
                t.invoiceNumber?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = metaParts.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiQuestionTab(
    uiState: SummaryUiState,
    isGeminiKeyConfigured: Boolean,
    onQuestionChange: (String) -> Unit,
    onSendQuestion: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onRetryLastQuestion: () -> Unit,
    onRestoreLastFailedQuestion: () -> Unit,
    onRestoreTextToInput: (String) -> Unit,
    onCopyText: (label: String, text: String, feedback: String) -> Unit,
    onCopyPromptWithData: (String) -> Unit,
    onRequestDelete: (List<RecordedTransaction>) -> Unit,
    onClearHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    if (!isGeminiKeyConfigured) {
        EmptyStateCard(
            title = "Gemini API キーが未設定です",
            description = "設定画面で Gemini API キー（BYOK）を登録すると、ご自身の台帳データに対して自然言語で集計・確認の質問ができます。",
            actionLabel = "設定画面を開く",
            onAction = onNavigateToSettings
        )
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(uiState.messages.size, uiState.isAskingAi) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // コンパクトな免責バー + リセット
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "※ データ整理補助です。税務判断は税務署・税理士へご確認ください。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (uiState.messages.isNotEmpty()) {
                    TextButton(
                        onClick = onClearHistory,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("履歴クリア", fontSize = 12.sp)
                    }
                }
            }
        }

        // プリセット質問チップ（スマホで入力の手間を減らす）
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(PRESET_AI_QUESTIONS) { preset ->
                SuggestionChip(
                    onClick = { onSelectPreset(preset) },
                    enabled = !uiState.isAskingAi && uiState.records.isNotEmpty(),
                    label = { Text(preset, fontSize = 12.sp) }
                )
            }
        }

        // チャット表示領域
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "${uiState.selectedYear}年の取引データ（${uiState.records.size}件）について質問できます",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "上の候補チップをタップするか、下の入力欄から「Amazonの合計は？」「旅費交通費の内訳を教えて」のように自由に聞いてみてください。\n通信はあなたの端末と Gemini API 間で直接行われます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                items(uiState.messages) { msg ->
                    val matchedTargets = remember(msg.suggestedDeleteRowIds, uiState.records) {
                        if (msg.suggestedDeleteRowIds.isEmpty()) {
                            emptyList()
                        } else {
                            val idSet = msg.suggestedDeleteRowIds.toSet()
                            uiState.records.filter { it.rowIndex in idSet }
                        }
                    }
                    ChatMessageBubble(
                        message = msg,
                        matchedDeleteTargets = matchedTargets,
                        isUpdatingCsv = uiState.isUpdatingCsv,
                        onCopyClick = {
                            onCopyText(
                                if (msg.isUser) "KeihiX プロンプト" else "KeihiX AI回答",
                                msg.text,
                                "テキストをコピーしました"
                            )
                        },
                        onReuseClick = if (msg.isUser) {
                            { onRestoreTextToInput(msg.text) }
                        } else {
                            null
                        },
                        onRequestDelete = onRequestDelete
                    )
                }
            }

            if (uiState.isAskingAi) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Gemini が台帳データを確認中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            uiState.aiErrorMessage?.let { err ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SelectionContainer {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            uiState.lastFailedQuestion?.let { failedQ ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onRetryLastQuestion,
                                        enabled = !uiState.isAskingAi,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("再送信", fontSize = 12.sp, maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = onRestoreLastFailedQuestion,
                                        enabled = !uiState.isAskingAi,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("入力欄に戻す", fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { onCopyPromptWithData(failedQ) },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "質問＋データをコピー（他AIへ貼り付け）",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 入力バー
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.questionInput,
                onValueChange = onQuestionChange,
                placeholder = { Text("例: 通信費の明細を教えて", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                enabled = !uiState.isAskingAi
            )
            FilledIconButton(
                onClick = onSendQuestion,
                enabled = !uiState.isAskingAi && uiState.questionInput.isNotBlank() && uiState.records.isNotEmpty()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: AdvisorMessage,
    matchedDeleteTargets: List<RecordedTransaction>,
    isUpdatingCsv: Boolean,
    onCopyClick: () -> Unit,
    onReuseClick: (() -> Unit)?,
    onRequestDelete: (List<RecordedTransaction>) -> Unit
) {
    val contentColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (message.isUser) 14.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 14.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp,
                        color = contentColor
                    )
                }

                if (!message.isUser && matchedDeleteTargets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "削除提案の対象:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    matchedDeleteTargets.forEach { rec ->
                        val t = rec.transaction
                        Text(
                            text = "・${t.date} ${t.vendor.ifBlank { "(取引先未設定)" }} ${formatYen(t.amount)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onRequestDelete(matchedDeleteTargets) },
                        enabled = !isUpdatingCsv,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "提案された ${matchedDeleteTargets.size} 件を台帳から削除",
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onReuseClick != null) {
                        TextButton(
                            onClick = onReuseClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = contentColor.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "入力欄へ",
                                fontSize = 11.sp,
                                color = contentColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                    TextButton(
                        onClick = onCopyClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = contentColor.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "コピー",
                            fontSize = 11.sp,
                            color = contentColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

private fun formatYen(amount: Long): String {
    val nf = NumberFormat.getNumberInstance(Locale.JAPAN)
    return "¥${nf.format(amount)}"
}

private fun formatYenSigned(amount: Long): String {
    val nf = NumberFormat.getNumberInstance(Locale.JAPAN)
    return when {
        amount > 0L -> "+¥${nf.format(amount)}"
        amount < 0L -> "-¥${nf.format(-amount)}"
        else -> "¥0"
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
