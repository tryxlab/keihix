# 【KeihiX開発日記#3】Geminiで領収書＆売上明細を一撃パース！Structured OutputsとBYOKの安全な実装（実測3秒の衝撃）

個人開発者や副業エンジニアが直面する確定申告の壁。
「領収書の整理が面倒」「でも月額課金のクラウド会計ソフトを入れるほど取引件数は多くない」という課題を解決するため、サーバー代0円・個人情報保持ゼロのAndroidアプリ **「KeihiX」** を自作しています。

* **第1回:** 【構想・設計編】月額課金したくない個人開発者が、サーバー代0円・個人情報保持ゼロの「レシート＆収入管理アプリ」を設計した話
* **第2回:** 【電帳法＆免責編】高価なタイムスタンプは不要？個人事業主が電帳法を合法ハックする事務処理規程とCLI実機開発

第3回となる今回は、アプリの頭脳となる **「Gemini を使った領収書・売上明細の構造化データ抽出（AIパース）」** の設計と実装ログをお届けします。

---

## 1. なぜ「OCR ＋ 正規表現」ではなく「小型マルチモーダルLLM」なのか？

レシート読み取りアプリを自作しようと考えたとき、最初に思い浮かぶのは「Google ML Kit などのオンデバイスOCRで文字を読み取り、正規表現で日付や金額を引っこ抜く」という構成です。

しかし、実際にプロトタイプを作ろうとすると、すぐに以下の壁にぶつかります。

* **表記揺れの無限地獄:**
  店舗によって「合計」「小計」「お買上計」「TOTAL」「税込」など表記がバラバラ。さらに「お預かり」「お釣り」の金額を合計金額と誤認するバグが頻発します。
* **影や折り目による誤認識:**
  スマホで斜めから撮ったレシートや、財布の中で折れ曲がった感熱紙のノイズは、単純なOCRエンジンでは文字化けしやすく、前処理の画像補正コードが肥大化します。
* **「プラットフォーム売上明細」に対応できない:**
  副業エンジニアにとって本当に面倒なのは、レシート（経費）よりも **「note、ココナラ、Google AdSense、ランサーズ等の支払通知書（売上）」** です。レイアウトが多種多様なWeb明細から、売上総額・プラットフォーム手数料・源泉所得税を正規表現で正確に抜き出すのは現実的ではありません。

そこで今回は、Googleの小型マルチモーダルLLM **Gemini** を採用しました。
画像やPDFを丸ごと入力し、文脈（コンテキスト）を理解させた上で、あらかじめ定義したJSONスキーマに沿って一発で構造化データを吐き出させます。

APIキーはユーザー自身のものを使う **BYOK（Bring Your Own Key）** 方式のため、無料枠（1分あたり15リクエスト、1日1,500リクエストまで）の範囲内であれば、個人利用の規模で利用料が発生することはありません。

---

## 2. 経費と収入を「単一スキーマ」に統合したデータ設計

一般的な会計ソフトは「支出の登録」と「売上の登録」で画面やDBテーブルが完全に分かれています。しかし、副業の確定申告（白色申告や青色申告10万円控除）を見据えた場合、最終的に欲しいのは **「1行1仕訳のCSV台帳」** です。

そこでKeihiXでは、経費と収入を単一のデータクラス `ParsedTransaction` にまとめました。

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

### このスキーマのポイント
1. **電帳法の検索3要件を自然に網羅:**
   `date`（日付）、 `amount`（金額）、 `vendor`（取引先）がそのままGoogle Driveの保存ファイル名（例: `20261025_1980_Amazon.pdf` ）に変換されます。
2. **副業売上の3点セットを一撃分解:**
   `amount`（売上総額）、 `fee`（支払手数料）、 `withholding_tax`（源泉徴収税額）を個別に抽出できるようにしたことで、プラットフォームから手取り額しか振り込まれない場合でも、確定申告に必要な「総額表示」を正確に復元できます。
3. **インボイス制度への対応:**
   レシート上に `T1234567890123` などの適格請求書発行事業者登録番号があれば自動抽出します。

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

### SDK の隠れた便利機能: `blob` によるPDF直接送信
ブラウザやメーラーの共有インテント経由で受け取るファイルは、画像だけでなく「領収書PDF」も多く含まれます。
Android SDKの `content` ビルダーには `blob(mimeType, bytes)` 関数が用意されており、PDFのバイナリ（ `ByteArray` ）を直接渡すことができます。

端末側でPDFを画像にレンダリングする重たいネイティブライブラリを組み込む必要がなく、アプリのAPKサイズを最小限に抑えられます。

---

## 4. UI設計：抽出結果の確認と手修正フォーム

AIの抽出精度がどれだけ高くても、確定申告は自己責任です。「AIが誤認したまま保存されてしまった」という事故を防ぐため、KeihiXでは解析結果をそのまま自動保存せず、必ず **人間の確認・手修正を挟むUIフロー** にしています。

* **取引区分の1タップ切替:** 経費か売上かをチップで即座に反転可能。
* **電帳法ファイル名のリアルタイムプレビュー:** 日付・金額・取引先を書き換えると、連動して保存ファイル名プレビュー（例: `20261025_1980_Amazon.pdf` ）が動的に更新されます。
* **AI備考（confidenceNote）の表示:** 金額が不鮮明で推測した場合など、AIからの注記がカード内に表示されます。

---

## 5. 本番API実測検証：実領収書PDFでベンチマークを計測

机上のコード解説にとどまらず、実際に手元の領収書PDFと Google AI Studio の無料APIキーを用いてエンドポイントへリクエストを送り、定量性能を測定しました。

### ① 開発中に直面したハプニング：モデル世代交代の壁
当初の構想では `gemini-1.5-flash` を指定していましたが、実際にAPIを叩いたところ、以下のエラー（HTTP 404）が返却されました。

```text
HTTP 404: Model is not found: models/gemini-1.5-flash for api version v1beta
```

さらに `gemini-2.5-flash` を試したところ、Googleから「`models/gemini-2.5-flash` は新規ユーザーには利用できません。最新の `models/gemini-3.8-flash` 等にコードを更新してください」との明確なレスポンスが返ってきました。
実際に試したところ、軽量モデルである **`gemini-3.1-flash-lite`** が最も安定して高速に応答したため、アプリの呼び出しモデルもこちらに更新しました。こうした「ドキュメントの記述と最新APIの世代差」をリアルタイムに検知できたのは、モックで終わらせず実通信を行った大きな収穫です。

### ② 実測ベンチマーク（業務スーパーの実領収書PDF）
テスト素材として、実際のスーパーの領収証PDF（クレジットカード利用・約169KB）を投入しました。

* **入力データ:** 実領収書 PDF (169,462 bytes)
* **使用モデル:** `gemini-3.1-flash-lite`
* **API応答時間:** **3.03 秒**
* **消費トークン数:**
  * 入力プロンプト: 1,148 tokens
  * 出力JSON: 122 tokens
  * 合計: 1,270 tokens

### ③ 実際に返却された生 JSON データ
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
このJSONから、電帳法準拠のファイル名が自動生成されます。

```text
20261024_4499_業務スーパー南佐賀店.pdf
```

* **抽出精度の考察:**
  * 領収書に印字されていた「2026年10月24日」が、正規表現を書くことなく `2026-10-24` に正規化。
  * 金額表記「￥４，４９９－」から記号とカンマを除去し、整数の `4499` として抽出。
  * 店舗名「業務スーパー南佐賀店」を正確に特定。
  * 最も驚いたのは、レシート下部に小さく印字されていた適格請求書発行事業者登録番号 **`T2140001041812` を欠損なく一撃で抽出** できた点です。OCRと正規表現の組み合わせでは検出ルールの調整に何時間もかかる部分が、わずか 3秒 で完了しました。

---

## 6. 技術的トレードオフの開示

今回の検証で得られたメリットと、今後の課題（トレードオフ）を整理します。

* **メリット:**
  * **圧倒的な開発速度:** OCRエンジンや画像前処理、正規表現の複雑な正規化コードが一切不要。
  * **無料枠の十分さ:** 1リクエストあたり約1,270トークン。無料枠（15 RPM / 1日1,500リクエスト）の範囲内で日常利用には十分すぎる余裕があります。
* **トレードオフ / 割り切り:**
  * **通信遅延:** 端末内OCRであれば数百ミリ秒で終わるところ、ネットワーク通信を伴うため実測で約3秒かかります（ローディング表示の工夫が必須）。
  * **モデル名の寿命:** 今回の検証で判明した通り、Geminiのモデル名は数ヶ月〜数年スパンで世代交代します。アプリ側ではモデル名をハードコードせず、設定画面やRemote Config等で柔軟に変更できる設計が望ましいです。

---

## まとめと今回のPR

今回は、KeihiXのコア機能である **Gemini による領収書・売上明細の構造化抽出ロジック** の実装と、実APIキー・実領収書PDFによる定量ベンチマーク（実測 3.03秒）の検証を行いました。

* **今回のプルリクエスト (#◯):** [feature/gemini-structured-extraction](https://github.com/tryxlab/keihix/tree/feature/gemini-structured-extraction)

次回（#4）は、抽出・確認したデータをユーザー本人のGoogle Driveへ自動バックアップし、`transaction_log.csv` へ追記する **「Google Drive 直結・サーバーレス台帳化」** の実装に進む予定です。

---

### 【免責事項】
* 本記事および本アプリは、税理士法に基づく税務相談や税務申告書類の作成を行うものではありません。
* 電子帳簿保存法への適合や確定申告における勘定科目の最終判断については、所轄の税務署または税理士へご相談の上、ご自身の責任においてご判断ください。
* 記事内で紹介したアーキテクチャやコードの利用により生じたいかなる損害についても、筆者は責任を負いかねます。

