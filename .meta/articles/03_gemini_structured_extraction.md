# 【KeihiX開発日記#3】Geminiで領収書＆売上明細をパースする。Structured OutputsとBYOKの実装ログ（実測3.0秒）

個人開発者や副業エンジニアが直面する確定申告の壁。
「領収書の整理が面倒」「でも月額課金のクラウド会計ソフトを入れるほど取引件数は多くない」という課題を解決するため、サーバー代0円・個人情報保持ゼロのAndroidアプリ **「KeihiX」** を自作しています。

* **第1回:** 【構想・設計編】月額課金したくない個人開発者が、サーバー代0円・個人情報保持ゼロの「レシート＆収入管理アプリ」を設計した話
* **第2回:** 【電帳法＆免責編】高価なタイムスタンプは不要？個人事業主が電帳法を合法ハックする事務処理規程とCLI実機開発

第3回となる今回は、アプリのコア機能である **「Gemini を用いた領収書・売上明細の構造化データ抽出（AIパース）」** の設計と実装ログをまとめます。

---

## 1. なぜ「OCR ＋ 正規表現」ではなく「小型マルチモーダルLLM」なのか？

レシート読み取り機能を検討する際、一般的なアプローチは「オンデバイスOCR（ML Kit等）でテキストを抽出し、正規表現で日付や金額を取得する」という構成です。

しかし、プロトタイプ作成の過程で以下の技術的課題に直面しました。

* **表記揺れへの対応コスト:**
  店舗によって「合計」「小計」「お買上計」「TOTAL」「税込」など表記が異なります。また、「お預かり」や「お釣り」の金額を支払合計と誤認しやすい問題があります。
* **影や折り目による文字化け:**
  スマートフォンで斜めから撮影したレシートや感熱紙の折り目により、OCRエンジンでの誤認識が発生しやすく、補正処理のコードが複雑化します。
* **多様な「Web売上明細」への対応困難:**
  副業ではレシート（経費）だけでなく、note、ココナラ、Google AdSense、ランサーズなどの支払通知書（売上）の管理も必要です。フォーマットが異なるWeb明細から、売上総額・手数料・源泉徴収税を正規表現で網羅的に抽出するのは保守コストが高くなります。

そこで、コンテキストの解釈が可能な小型マルチモーダルLLM **Gemini** を採用しました。
画像やPDFを直接入力し、定義したJSONスキーマに沿って構造化データとして抽出します。

APIキーはユーザー自身のものを使用する **BYOK（Bring Your Own Key）** 方式を採用しているため、無料枠（1分あたり15リクエスト、1日1,500リクエストまで）の範囲であれば、個人利用の規模でAPI利用料は発生しません。

---

## 2. 経費と収入を統合したデータ設計

多くの会計ソフトでは支出と売上の入力導線が分かれていますが、副業の確定申告（白色申告や青色申告10万円控除）においては、最終的に **「1行1仕訳のCSV台帳」** が出力できれば要件を満たせます。

KeihiXでは、経費と収入を単一のデータクラス `ParsedTransaction` として定義しました。

```kotlin
@Serializable
data class ParsedTransaction(
    @SerialName("type")
    val type: TransactionType = TransactionType.EXPENSE, // expense または income

    @SerialName("date")
    val date: String, // YYYY-MM-DD

    @SerialName("amount")
    val amount: Long, // 税込総額（売上額または支払総額）

    @SerialName("vendor")
    val vendor: String, // 取引先・店舗名、または振込元プラットフォーム名

    @SerialName("category")
    val category: String, // 勘定科目（消耗品費、通信費、売上など）

    @SerialName("fee")
    val fee: Long? = null, // プラットフォーム手数料等（収入時）

    @SerialName("withholding_tax")
    val withholdingTax: Long? = null, // 源泉徴収税額（収入時）

    @SerialName("invoice_number")
    val invoiceNumber: String? = null, // 適格請求書番号 (T+13桁)

    @SerialName("memo")
    val memo: String? = null, // 摘要・購入品目

    @SerialName("confidence_note")
    val confidenceNote: String? = null // AIによる備考（推測箇所の補足）
)
```

### 設計上のポイント
1. **電帳法の検索3要件の充足:**
   `date`（日付）、 `amount`（金額）、 `vendor`（取引先）を抽出し、そのままGoogle Driveの保存ファイル名（例: `20261025_1980_Amazon.pdf` ）に変換します。
2. **売上・手数料・源泉徴収税の同時抽出:**
   `amount`（売上総額）、 `fee`（支払手数料）、 `withholding_tax`（源泉徴収税額）を個別に保持することで、手取り振込額から確定申告に必要な総額表示を復元できます。
3. **インボイス制度への対応:**
   レシート上に適格請求書発行事業者登録番号（T+13桁）が記載されている場合は自動抽出します。

---

## 3. Gemini へのプロンプト指示と構造化抽出の実装

Android公式の生成AI SDK（ `com.google.ai.client.generativeai` ）を用い、暗号化保存されたAPIキーから直接通信を行います。

```kotlin
class GeminiTransactionParser(
    private val secureKeyManager: SecureKeyManager,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun parseReceiptImage(bitmap: Bitmap): ParseResult = withContext(Dispatchers.IO) {
        val apiKey = secureKeyManager.getGeminiApiKey() ?: return@withContext ParseResult.Error("APIキー未設定")

        val generativeModel = GenerativeModel(
            modelName = "gemini-3.1-flash-lite",
            apiKey = apiKey,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.1f
            },
            systemInstruction = content { text(systemPrompt) }
        )

        val inputContent = content {
            image(bitmap)
            text("この画像から経費または収入の情報を抽出し、指定のJSONフォーマットで出力してください。")
        }

        val response = generativeModel.generateContent(inputContent)
        // ... JSON デシリアライズ処理 ...
    }
}
```

### SDK の機能: `blob` によるPDF直接送信
ブラウザやメーラーの共有インテント経由で受け取るファイルには、画像だけでなく領収書PDFも含まれます。
Android SDKの `content` ビルダーには `blob(mimeType, bytes)` 関数が用意されており、PDFのバイナリ（ `ByteArray` ）を直接送信できます。

端末側でPDFを画像に変換するレンダリング処理が不要となるため、アプリの依存関係と実装量を抑えられます。

---

## 4. UI設計：抽出結果の確認と手修正フォーム

確定申告における仕訳の正確性を担保するため、AIの解析結果を直接保存するのではなく、必ず **利用者が確認・手修正できるUIフロー** を設けています。

* **取引区分の切替:** 経費か売上かをチップで変更可能。
* **電帳法ファイル名のプレビュー:** 日付・金額・取引先を編集すると、連動して保存ファイル名（例: `20261025_1980_Amazon.pdf` ）が動的に更新されます。
* **AI備考（confidenceNote）の表示:** 金額の読み取りに推測を含む場合など、AIからの注記を表示します。

---

## 5. 本番API実測検証：実領収書PDFによるベンチマーク

実装したロジックの妥当性を確認するため、実際の領収書PDFおよび Google AI Studio の無料APIキーを用いてエンドポイントへの実測テストを実施しました。

### ① モデル世代交代の確認
当初設計では `gemini-1.5-flash` を想定していましたが、実際のAPI疎通時に以下のエラー（HTTP 404）が返却されました。

```text
HTTP 404: Model is not found: models/gemini-1.5-flash for api version v1beta
```

また、`gemini-2.5-flash` についても新規利用不可の通知と後継モデルへの移行案内が返却されました。
後継モデル群の中で、軽量かつ低遅エンで応答した **`gemini-3.1-flash-lite`** を採用し、アプリ側のモデル指定を更新しました。

### ② 実測ベンチマーク（実領収書PDF）
テストデータとして、実際の店舗領収書PDF（クレジットカード利用・約169KB）を投入しました。

* **入力データ:** 実領収書 PDF (169,462 bytes)
* **使用モデル:** `gemini-3.1-flash-lite`
* **API応答時間:** **3.03 秒**
* **消費トークン数:**
  * 入力プロンプト: 1,148 tokens
  * 出力JSON: 122 tokens
  * 合計: 1,270 tokens

### ③ 返却された生 JSON データ
```json
{
  "type": "expense",
  "date": "2026-10-24",
  "amount": 4499,
  "vendor": "業務スーパー南佐賀店",
  "category": "消耗品費",
  "fee": null,
  "withholding_tax": null,
  "invoice_number": "T2140001041812",
  "memo": "食料品等の購入",
  "confidence_note": null
}
```

### ④ 電帳法ファイル名への変換プレビュー
抽出されたJSONから生成されたファイル名は以下の通りです。

```text
20261024_4499_業務スーパー南佐賀店.pdf
```

* **抽出精度の考察:**
  * 印字されていた「2026年10月24日」が `2026-10-24` に正規化。
  * 金額表記「￥４，４９９－」から記号・カンマが除去され、整数の `4499` として抽出。
  * 店舗名「業務スーパー南佐賀店」を特定。
  * レシート下部に記載されていた適格請求書発行事業者登録番号 **`T2140001041812`** も欠損なく抽出されました。

> [!NOTE]
> **【サンプル数（n=1）についての留意点】**
> 本ベンチマークおよび抽出結果は、現時点では特定の領収書PDF 1件（n=1）による検証結果です。
> レイアウトや解像度、文字数の異なるサンプル（折り目の入った感熱紙レシート、手書き領収書、noteやAdSenseの売上明細PDFなど）における認識率や処理時間のばらつきについては、今後の開発過程で検証サンプルを増やし、継続してログを蓄積・検証していく予定です。

---

## 6. 技術的トレードオフと有料枠移行時のコスト試算

今回の実装と検証で確認されたメリット・課題（トレードオフ）および、有料枠（従量課金）への移行時におけるコスト試算を整理します。

### ① 開発・運用上のトレードオフ
* **メリット:**
  * **実装工数の削減:** OCRの画像前処理や、店舗ごとの正規表現ルールの個別実装が不要。
  * **無料枠の十分さ:** 1リクエストあたり約1,270トークンであり、無料枠（15 RPM / 1日1,500リクエスト）の範囲で個人の日常利用には十分な余裕があります。
* **課題:**
  * **通信遅延:** 端末完結の処理とは異なり、ネットワーク通信を伴うため実測で約3秒を要します（UI上でのインジケータ表示が必須）。
  * **モデル名の移行追従:** Geminiのモデル名は世代交代が行われるため、アプリ側でモデル名をハードコードせず、設定等で変更可能な設計にする必要があります。

### ② 無料枠を使い切った場合（有料枠）のコスト試算
「無料枠を超過した場合、またはプライバシー規約（入力データの学習利用回避）を考慮して有料版（Pay-as-you-go）のキーを使った場合、1回あたりのコストはいくらになるのか」を実測トークン数から試算しました。

* **前提条件:**
  * 入力: 1,148 tokens / 出力: 122 tokens（今回の n=1 実測値に基づく試算）
  * 為替レート: 1ドル＝150円換算
* **モデル別の試算結果:**
  * **`gemini-3.1-flash-lite`（今回採用のモデル）:**
    * 入力: $0.25 / 100万tokens、出力: $1.50 / 100万tokens
    * 1回あたりの費用: 約 $0.000470（**約 0.07 円 / 回**）
    * 100枚処理した場合: 約 7.1 円
  * **`gemini-3.8-flash`（標準 Flash モデル）:**
    * 入力: $0.75 / 100万tokens、出力: $3.75 / 100万tokens
    * 1回あたりの費用: 約 $0.001319（**約 0.20 円 / 回**）
    * 100枚処理した場合: 約 19.8 円

個人事業主や副業の年間取引件数が100〜300件程度であれば、仮に有料枠で全件処理したとしても年間コストは数十円〜百円未満にとどまります。
月額1,000円〜2,000円程度かかる一般的なクラウド会計ソフトの固定費と比較して、インフラ維持費を払わず実使用量のみを負担する BYOK 構成の経済的合理性が確認できます。

---

## まとめと今回のPR

今回は、KeihiXのコア機能である **Gemini による領収書・売上明細の構造化抽出ロジック** の実装と、実APIキー・実領収書PDFによる定量ベンチマーク（実測 3.03秒）の検証を行いました。

* **今回のプルリクエスト (#◯):** [feature/gemini-structured-extraction](https://github.com/tryxlab/keihix/tree/feature/gemini-structured-extraction)

次回（#4）は、抽出・確認したデータをユーザー自身のGoogle Driveへ自動保存し、`transaction_log.csv` へ追記する **「Google Drive 直結・サーバーレス台帳化」** の実装に進む予定です。

---

### 【免責事項】
* 本記事および本アプリは、税理士法に基づく税務相談や税務申告書類の作成を行うものではありません。
* 電子帳簿保存法への適合や確定申告における勘定科目の最終判断については、所轄の税務署または税理士へご相談の上、ご自身の責任においてご判断ください。
* 記事内で紹介したアーキテクチャやコードの利用により生じたいかなる損害についても、筆者は責任を負いかねます。
