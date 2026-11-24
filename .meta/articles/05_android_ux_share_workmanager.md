# 【KeihiX開発日記#5】503エラーと横向きレシートに実機で直面して分かった、共有インテント・WorkManager・「あとで解析」退避のUX設計

個人開発者や副業エンジニアが直面する確定申告の壁。
「領収書の整理が面倒」「でも月額課金のクラウド会計ソフトを入れるほど取引件数は多くない」という課題を解決するため、サーバー代0円・個人情報保持ゼロのAndroidアプリ **「KeihiX」** を自作しています。

* **第1回:** 【構想・設計編】月額課金したくない個人開発者が、サーバー代0円・個人情報保持ゼロの「レシート＆収入管理アプリ」を設計した話
* **第2回:** 【電帳法＆免責編】高価なタイムスタンプは不要？個人事業主が電帳法を合法ハックする事務処理規程とCLI実機開発
* **第3回:** 【AI抽出・BYOK編】Geminiで領収書＆売上明細をパースする。Structured OutputsとBYOKの実装ログ（実測3.0秒）
* **第4回:** 【Google Drive直結編】Google Driveを自作DBにする。drive.fileスコープとCSV追記（Read-Modify-Write）の実装ログ

前回（#4）は、Google Drive API（`drive.file` スコープ）と Read-Modify-Write による `transaction_log.csv` の追記基盤を実装しました。

第5回となる今回は、実際の運用を想定した **「Android UX の磨き込み」** の開発ログです。
具体的には、実機（Pixel 9a）でテスト中に遭遇した生々しい失敗（Gemini の 503 エラーと SDK の例外露出、写真の横向き問題）を起点に、 **共有インテント（`ACTION_SEND`）接続・`WorkManager` によるオフライン耐性・未解析ファイルの Drive 一時退避（「あとで解析」）** を実装した過程をまとめます。

---

## 1. 実機テストで踏んだ 2 つのリアルな課題

機能追加の前に、Pixel 9a に以前インストールしていたアプリを立ち上げてレシートを撮影したところ、2つの明確な UX 上の課題に直面しました。

![実機で発生した Gemini 503 エラーと横向きレシートプレビュー](../../Docs/images/ui_error_503_before.png)
*▲ 改善前の実機スクリーンショット。レシート画像が横向きで表示され、下部に 503 エラーの生 JSON と `MissingFieldException` がそのまま露出している*

1. **Gemini API の 503 エラー時に SDK がクラッシュ風メッセージを出す問題:**
   Gemini 側が一時的に高負荷となり `503 UNAVAILABLE` を返した際、Google AI SDK（`com.google.ai.client.generativeai`）内部のレスポンスデシリアライズが `GRpcError` の `details` フィールド欠落で `MissingFieldException` を起こし、画面に生の JSON と英語の例外名がそのまま表示されてしまいました。しかもその場に **「再解析」も「手入力へ進む」ボタンもない** ため、せっかく撮ったレシートがそこで行き止まりになってしまいます。
2. **カメラ撮影画像の EXIF 回転が反映されない問題:**
   Android のカメラアプリで縦向きに撮ったレシート画像でも、JPEG 自体は横向きに記録されており EXIF の `TAG_ORIENTATION` で回転角が指定されているケースがあります。`BitmapFactory.decodeStream` でそのまま読み込むと、プレビュー上でレシートが90度横倒しになっていました。

机上のユニットテストだけでは気づけなかった、実機ならではの課題です。

---

## 2. 「解析できないときは先に Drive へ退避、あとで解析」という割り切り

さらに現実の生活動線を考えると、レジの前や移動中にレシートを撮ったタイミングで **「AI が 503 で混雑している」「地下で電波が不安定」** という場面は普通に起こります。

そこで今回、エラー時の導線を以下のように整理しました。

* **エラー種別の人間向け翻訳（`GeminiTransactionParser.mapExceptionToError`）:**
  `503 / UNAVAILABLE / high demand` や `429 / RESOURCE_EXHAUSTED`、ネットワークエラー等を判定し、「現在 Gemini API が混雑しています（503）。少し待ってから再解析するか、手入力で登録してください」といった案内と、リトライ可否フラグ（`isRetryable`）を返すようにしました。
* **3つの逃げ道を用意:**
  * **再解析:** 一時エラーならワンタップで同じ画像・PDFを再送信。
  * **手入力で進む:** AI を待たずにその場で手入力フォーム（`DatePicker` ＋ 勘定科目チップ付き）を開いて登録。
  * **先に Drive に退避（あとで解析）:** Google Drive の `確定申告_{年}/pending/` フォルダへ画像・PDFだけを先に退避しておき、帰宅後や通信が安定したタイミングでトップ画面の **「未解析ファイル (Drive)」** リストから呼び出して Gemini 解析・確定保存（完了後に `pending/` からは自動削除）できるようにしました。

![改善後の実機スクリーンショット（EXIF正立＋503エラー翻訳＋3つの導線）](../../Docs/images/ui_error_503_after.png)
*▲ 改善後の実機スクリーンショット。レシートが正立表示され、503 エラー時にも「再解析」「手入力で進む」「先に Drive に退避（あとで解析）」の 3 つの導線が提示される*

この際、未解析の状態では `transaction_log.csv` に不完全な行を書き込まないようにしているため、台帳のクリーンさも保たれます。

---

## 3. `ACTION_SEND`（共有インテント）の接続で入力を最短化

Web 領収書やメールで届いた請求書 PDF を毎回端末にダウンロードしてピッカーから探すのは手間がかかります。

`AndroidManifest.xml` にはすでに `ACTION_SEND`（`image/*` と `application/pdf`）の `intent-filter` を定義していましたが、今回は `MainActivity` に `android:launchMode="singleTop"` を設定したうえで、以下のパイプラインを接続しました。

```text
[他アプリ（Chrome / Gmail / PDFビューア）で「共有」]
       │ (ACTION_SEND + EXTRA_STREAM / clipData)
       ▼
[MainActivity (onCreate / onNewIntent)]
       │ KeihiXApplication.pendingSharedUri (StateFlow) へ格納
       ▼
[KeihiXNavHost]
       │ 免責同意済みなら Home へ遷移して自動消費
       ▼
[ExtractionViewModel.processUri()]
       └─ EXIF回転補正（画像）またはバイト列読込（PDF）→ Gemini 自動解析
```

これにより、ブラウザやメーラーで明細を開いた状態から「共有 → KeihiX」を選ぶだけで、即座に抽出フォームまでたどり着けるようになりました。

---

## 4. `WorkManager` による非同期アップロード＆オフライン耐性

第4回の課題として残っていた「保存時のネットワークエラー対策」として、`UploadWorker`（`CoroutineWorker`）を導入しました。

### 設計上のポイント: URI 権限失効への対策
共有インテントやピッカーで取得した `content://` URI の読み取り権限は一時的なものがあり、オフライン状態でアプリを閉じたあとにバックグラウンドで再実行されると `SecurityException` で読めなくなる恐れがあります。

そこで `UploadWorker.enqueue()` では、以下の手順を踏む設計にしました。

1. **ローカル退避:** 対象ファイル（EXIF補正後の JPEG または PDF バイト列）を端末内アプリ専用領域（`filesDir/pending_uploads/`）へ一時書き出しする。
2. **制約付きキュー登録:** `NetworkType.CONNECTED` 制約と指数バックオフ（30秒〜、最大5回リトライ）を付けた `OneTimeWorkRequest` を `WorkManager` に登録する。
3. **完了時クリーンアップ:** Google Drive へのアップロードと `transaction_log.csv` 追記が成功したら、ローカルの一時ファイル（および `pending/` 由来であれば Drive 上の pending ファイル）を削除する。

これにより、電波のない場所で「Google Drive に保存する」を押しても、画面上には「送信キューに追加しました」と表示され、オンライン復帰後に自動で Drive へ同期されるようになりました。

---

## 5. Compose UI の細かな手触り改善とテスト結果

日々の確認・手修正フォームの入力コストを下げるため、以下の UI 改善もあわせて行いました。

* **EXIF 回転補正:** `android.media.ExifInterface` で向きを読み取り、プレビューおよびアップロード用 JPEG を正立状態に自動補正。
* **日付ピッカー（`DatePickerDialog`）:** `YYYY-MM-DD` の手打ちだけでなく、カレンダーアイコンから日付を選べるように改善。
* **勘定科目クイックチップ:** `消耗品費`、`通信費`、`旅費交通費`、`会議費` などのよく使う科目を横スクロールの `FilterChip` で 1 タップ選択可能に。
* **PDF・ファイルピッカー & 手入力ボタン:** フォトピッカーに加えて `OpenDocument` による PDF 直接選択と、AI を通さない手入力起動ボタンを追加。

### ビルド・テスト結果
今回の実装に対して、以下のユニットテスト（`GeminiErrorMappingTest` / `UploadWorkerTest` を含む全14件）およびデバッグ APK ビルドを実行し、すべて通過することを確認しました。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
# 14 tests completed, 0 failed / BUILD SUCCESSFUL in 45s
```

*(※ 実機 Pixel 9a へのインストールも完了しています。今後、実機での共有インテント動作や `pending/` 退避のスクリーンショットも追記予定です。)*

---

## まとめと今回のPR

今回は、実機テストで見つかった 503 エラー・画像回転の課題をきっかけに、 **「エラー時の再解析・手入力・Drive `pending/` 退避（あとで解析）」「`ACTION_SEND` 共有インテント」「`WorkManager` 非同期保存」** を一気に実装しました。

* **今回作成した PR (#3) はこちら:** *(PR作成後にリンクを反映予定: `feature/android-ux-share-workmanager`)*

次回（#6・最終回予定）は、蓄積された `transaction_log.csv` を使った集計・エクスポート機能と、確定申告書等作成コーナーへの転記フロー、およびオープンソース公開・配布周りのリアルをまとめる予定です。

---

### 【免責事項】
* 本記事および本アプリは、税理士法に基づく税務相談や税務申告書類の作成を行うものではありません。
* 電子帳簿保存法への適合や確定申告における勘定科目の最終判断については、所轄の税務署または税理士へご相談の上、ご自身の責任においてご判断ください。
* 記事内で紹介したアーキテクチャやコードの利用により生じたいかなる損害についても、筆者は責任を負いかねます。
