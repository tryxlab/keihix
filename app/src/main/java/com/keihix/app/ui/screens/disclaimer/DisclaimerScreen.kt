package com.keihix.app.ui.screens.disclaimer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keihix.app.ui.theme.DarkBackground
import com.keihix.app.ui.theme.DarkBorder
import com.keihix.app.ui.theme.DarkSurface
import com.keihix.app.ui.theme.DarkSurfaceVariant
import com.keihix.app.ui.theme.PrimaryEmerald
import com.keihix.app.ui.theme.PrimaryEmeraldLight
import com.keihix.app.ui.theme.SecondaryTeal
import com.keihix.app.ui.theme.TextMuted
import com.keihix.app.ui.theme.TextPrimary
import com.keihix.app.ui.theme.TextSecondary
import com.keihix.app.ui.theme.WarningAmber

@Composable
fun DisclaimerScreen(
    viewModel: DisclaimerViewModel,
    onProceed: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryEmerald),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "K",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "KeihiX 免責事項の確認",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "電子帳簿保存法・税理士法に関する重要事項",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Text(
                text = "KeihiXをご利用になる前に、以下の免責事項および設計思想をご確認のうえ同意してください。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Scrollable Cards
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DisclaimerCard(
                    icon = Icons.Default.Warning,
                    iconTint = WarningAmber,
                    title = "税務助言非該当の確認",
                    description = "本アプリは税理士等の助言を提供するものではなく、税務上の責任は負いません。仕訳の最終確定・確定申告書類の提出はご自身の責任で行ってください。"
                )

                DisclaimerCard(
                    icon = Icons.Default.Article,
                    iconTint = SecondaryTeal,
                    title = "電帳法・事務処理規程の備え付け",
                    description = "電子帳簿保存法の電子取引データ保存要件を満たすため、国税庁ひな形に準拠した「事務処理規程」の備え付けとGoogle Drive命名規則・CSVログにより適法に運用します。"
                )

                DisclaimerCard(
                    icon = Icons.Default.Lock,
                    iconTint = PrimaryEmeraldLight,
                    title = "ゼロ・インフラとBYOKの仕組み",
                    description = "開発者サーバーは一切存在せず、データは端末ローカルとご自身のGoogle Driveのみに保存されます。Gemini APIキーも端末の暗号化領域（EncryptedSharedPreferences）に安全に保持されます。"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { viewModel.showKiteiDialog() }) {
                        Text(
                            text = "事務処理規程（ひな形）の全文を確認する",
                            color = SecondaryTeal,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Checkboxes
                CheckboxRow(
                    checked = uiState.isTaxAdviceUnderstood,
                    onCheckedChange = { viewModel.toggleTaxAdvice() },
                    text = "税務助言を行わないツールであり、申告は自己責任であることを確認しました。"
                )

                CheckboxRow(
                    checked = uiState.isKiteiUnderstood,
                    onCheckedChange = { viewModel.toggleKitei() },
                    text = "電帳法対応として「事務処理規程」を備え付けて運用することを理解しました。"
                )

                CheckboxRow(
                    checked = uiState.isSelfResponsibilityAccepted,
                    onCheckedChange = { viewModel.toggleSelfResponsibility() },
                    text = "ゼロ・インフラ・BYOKの仕組みを理解し、自己責任で利用することに同意します。"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CTA Button
            Button(
                onClick = { viewModel.acceptDisclaimerAndProceed(onProceed) },
                enabled = uiState.canProceed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryEmerald,
                    disabledContainerColor = DarkSurfaceVariant,
                    disabledContentColor = TextMuted
                )
            ) {
                Text(
                    text = "同意してはじめる",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.canProceed) Color.White else TextMuted
                )
            }
        }
    }

    // 事務処理規程ダイアログ
    if (uiState.showKiteiDialog) {
        KiteiPreviewDialog(onDismiss = { viewModel.hideKiteiDialog() })
    }
}

@Composable
private fun DisclaimerCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = CheckboxDefaults.colors(
                checkedColor = PrimaryEmerald,
                uncheckedColor = TextMuted,
                checkmarkColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) TextPrimary else TextSecondary,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun KiteiPreviewDialog(onDismiss: () -> Unit) {
    val kiteiText = """
        【電子取引データの訂正及び削除の防止に関する事務処理規程（抜粋）】
        
        第1条（目的）
        本規程は、電子帳簿保存法第7条に規定する電子取引データの保存義務を適正に履行するため、その訂正及び削除の防止に関する手順を定める。
        
        第3条（管理責任者）
        管理責任者は事業主本人をもって充てる。
        
        第4条（訂正及び削除の原則禁止）
        授受した電子取引データは速やかに指定フォルダへ格納し、正当な理由がない限り訂正及び削除を行ってはならない。
        
        第5条（訂正及び削除の手順）
        やむを得ず訂正・削除を行う場合は、「取引データ訂正・削除管理簿」に年月日、理由、内容等を記録して保存する。
    """.trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = PrimaryEmeraldLight)
            }
        },
        title = {
            Text("事務処理規程（国税庁ひな形準拠）", color = TextPrimary)
        },
        text = {
            Text(
                text = kiteiText,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 18.sp
            )
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(16.dp)
    )
}
