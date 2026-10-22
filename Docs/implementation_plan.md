# 実装計画書: 確定申告・電帳法対応レシート＆収入管理アプリ (keihix)

本書は [original_spec.md](file:///c:/Users/sts3886/Documents/dev/keihix/Docs/original_spec.md) に基づき、ゼロ・インフラ＆BYOKアーキテクチャによるクライアント完結型Androidアプリの実装手順・技術選定・マイルストーンを定義した実装計画書です。

---

## 1. 前提と技術スタック選定

### 1.1 推奨技術スタック
仕様書の「Androidクライアント」「EncryptedSharedPreferences」「共有インテント」に準拠し、最新のAndroid公式ベストプラクティスを採用します。

| レイヤー | 選定技術 | 選定理由 |
|---|---|---|
| **言語・SDK** | Kotlin (Java 17 / 21) / Android 14+ (Min SDK 26 / Target SDK 35) | 長期サポートと最新API (Photo Picker, Credential Manager) 活用 |
| **UIフレームワーク** | Jetpack Compose + Material 3 | モダンで宣言的なUI、高速なフォーム実装、ダークモード対応 |
| **アーキテクチャ** | MVVM + Clean Architecture (Repository Pattern) | 外部API (Drive, Gemini) とUIの疎結合化、テスト容易性 |
| **非同期処理** | Kotlin Coroutines + Flow, WorkManager | 非同期APIコールと信頼性のあるバックグラウンドDriveアップロード |
| **セキュアストレージ** | EncryptedSharedPreferences (AndroidX Security Crypto) または Encrypted DataStore | BYOK (Gemini APIキー) の端末内安全保持 |
| **認証** | Credential Manager (Google Sign-In) | `drive.file` スコープでの最小権限OAuth 2.0認可 |
| **Google Drive連携** | Google Drive API Client Library for Java (REST) または OkHttp直叩き | 軽量性と `drive.file` 専用フォルダ・CSV操作の制御 |
| **AI抽出** | Google Gen AI SDK for Kotlin / REST API (`generativelanguage.googleapis.com`) | Gemini 1.5 FlashのStructured Outputs (JSON Schema) 直接呼び出し |

---

## 2. システム構成とデータフロー

```text
[ユーザー操作: 共有インテント / カメラ撮影 / ファイル選択]
       │ (URI取得)
       ▼
[Entry Screen / Preview Screen]
       │ (画像/PDFバイト列取得)
       ▼
[Gemini Extraction Service] ─── (BYOK: Gemini 1.5 Flash)
       │ (Structured JSON: 区分/日付/金額/取引先/科目等)
       ▼
[Edit & Verification Screen] (手修正・科目選択)
       │ (ユーザー確認完了)
       ▼
[Sync Manager (WorkManager)]
       ├─ Google Drive: {年}/receipts|incomes/{ファイル名} アップロード
       └─ Google Drive: {年}/transaction_log.csv 追記/更新
```

---

## 3. 実装フェーズ（段階的ロードマップ）

### Phase 0: プロジェクト初期化・環境構築
- [ ] **Androidプロジェクト作成**
  - Jetpack Compose Empty Activity テンプレート作成
  - バージョンカタログ (`libs.versions.toml`) 整備
- [ ] **依存ライブラリの導入**
  - Navigation Compose, ViewModel, Coroutines
  - AndroidX Security Crypto, WorkManager
  - Google Drive API クライアント, Google Sign-In / Credential Manager
  - Google Gen AI SDK / OkHttp, Kotlinx Serialization
- [ ] **基本テーマ & リソース定義**
  - Material 3 カラースキーム、タイポグラフィ
  - アプリアイコン・マニフェスト設定

---

### Phase 1: 免責事項 & セキュア設定管理 (BYOK)
仕様書 第2章・第4章に基づく、アプリの基盤設定。
- [ ] **初回起動時の免責事項 (Disclaimer) ダイアログ / 画面**
  - 税務助言非該当、AI抽出精度非保証、自己責任の同意チェック
  - 同意状態をPreferenceに永続化（同意前はメイン機能への遷移ブロック）
- [ ] **設定画面 (SettingsScreen)**
  - Gemini APIキーの入力・マスク表示・検証機能
  - 免責事項・事務処理規程の再確認リンク
  - Googleアカウント連携ステータス表示
- [ ] **セキュアキーマネージャー**
  - `EncryptedSharedPreferences` を用いたAPIキーの安全な保存・取得・削除ロジック

---

### Phase 2: Google認証 & Google Driveストレージ基盤
仕様書 第5章に基づく、Drive連携ロジックの実装。
- [ ] **OAuth 2.0 / Googleアカウント連携**
  - Credential Manager APIを利用したGoogleアカウント選択
  - スコープ `https://www.googleapis.com/auth/drive.file` の認可取得
- [ ] **Google Drive操作クライアント (DriveRepository)**
  - ルートフォルダ `確定申告_{年}` の存在確認・自動作成
  - サブフォルダ `receipts/`, `incomes/` の作成・管理
  - ファイルアップロード処理 (`{YYYYMMDD}_{金額}_{取引先}.{拡張子}`)
- [ ] **CSV台帳マネージャー (CsvLogManager)**
  - `transaction_log.csv` の存在確認、初期ヘッダー作成
  - 新規レコードの行追記 (Append) ロジック
  - 競合防止または冪等性の考慮

---

### Phase 3: レシート・明細キャプチャ & 入力インターフェース
仕様書 第6章 (6.1) に基づく、入力トリガーの実装。
- [ ] **共有インテント (Share Target) 対応**
  - `AndroidManifest.xml` に `ACTION_SEND` (image/*, application/pdf) の `intent-filter` 追加
  - 他アプリ（ブラウザ、メーラー、ファイルマネージャー）からの共有受信ハンドラ
- [ ] **アプリ内キャプチャ機能**
  - カメラ撮影 (ActivityResultContracts.TakePicture または CameraX)
  - ファイルピッカー (Photo Picker / OpenDocument で画像・PDFを選択)
- [ ] **プレビューUI**
  - 選択された画像またはPDF 1ページ目のサムネイル表示
  - 再選択・キャンセル機能

---

### Phase 4: Gemini 1.5 Flash 構造化抽出エンジン (BYOK)
仕様書 第4章に基づく、AI抽出ロジック。
- [ ] **データモデル定義 (`TransactionMetadata`)**
  - `type` (expense / income), `date`, `amount`, `vendor`, `category`, `fee`, `withholding_tax`, `memo`
  - Kotlinx Serialization を用いたモデルクラス
- [ ] **Gemini API クライアント (GeminiExtractor)**
  - 指定されたJSONスキーマ（Structured Outputs）の定義
  - 画像（JPEG/PNG/WEBP）およびPDFのBase64エンコード / インラインデータ送信
  - システムプロンプト（確定申告・インボイス・レシート解析に最適化した指示）
- [ ] **エラーハンドリング & フォールバック**
  - APIキー未設定 / 不正時の案内
  - レートリミット (429) や通信エラー時のフォールバック（手動入力フォームへそのまま誘導）

---

### Phase 5: 確認・編集UI & 保存・アップロードフロー
仕様書 第3章・第6章に基づく、コアUXの実装。
- [ ] **編集・確認画面 (EditTransactionScreen)**
  - 区分トグル（経費 `expense` ⇄ 収入 `income`）
  - 日付入力（DatePicker）、金額入力（テンキー最適化）
  - 取引先入力、勘定科目選択（ドロップダウン / 候補チップ）
  - 収入時の追加項目アコーディオン（手数料、源泉徴収税）
  - 摘要メモ入力
- [ ] **バックグラウンド保存キュー (UploadWorker)**
  - WorkManager による非同期アップロード（ネットワーク切断時の自動リトライ）
  - 1. ファイルをGoogle Driveの該当フォルダへアップロード
  - 2. Drive上のファイルID/パスを取得
  - 3. `transaction_log.csv` へレコード追記
- [ ] **保存完了フィードバック & ローカル一時管理**
  - 保存完了スナックバー・通知
  - 直近の処理状況（完了・送信中）の簡易ステータス表示

---

### Phase 6: 電帳法・確定申告対応 & エクスポート
仕様書 第2章・第5章に基づく機能整備。
- [ ] **トランザクション履歴・エクスポート画面**
  - 当年分 `transaction_log.csv` のダウンロード・共有 (Intent.ACTION_SEND)
  - 簡易集計表示（経費合計、収入合計、科目別小計）
- [ ] **電帳法ドキュメント整備**
  - 国税庁ひな形ベース「電子取引データの訂正及び削除の防止に関する事務処理規程（個人事業主用）」のMarkdownファイル同梱
  - アプリ内設定画面から規程テキストの閲覧・共有機能

---

### Phase 7: 配布・公開準備 (GitHub & Google Play)
仕様書 第6章 (6.2) に基づく。
- [ ] **リポジトリ公開用ドキュメント整備**
  - README.md（アーキテクチャ、免責事項、BYOK手順、セルフビルド方法）
  - LICENSE (MIT / Apache-2.0等)
- [ ] **リリースビルド & CI/CD**
  - ProGuard / R8 難読化・シュリンク設定
  - GitHub Actions による野良APK自動ビルド・Release作成
- [ ] **Google Play ストア申請準備**
  - プライバシーポリシー作成（データ非保持、Drive直結を明記）
  - ストア掲載情報・スクリーンショット準備

---

## 4. リスク管理と検証ポイント

| リスク要因 | 影響 | 対策 |
|---|---|---|
| **Google Drive APIのレート制限 / 認証失効** | アップロード失敗、CSV不整合 | WorkManager による指数バックオフリトライ。CSV追記前にDrive上の存在とヘッダー整合性を再検証。 |
| **PDFのマルチモーダル解析制限** | Gemini APIでの複数ページPDFエラー | 領収書・請求書は通常1〜2ページのため、1ページ目を優先処理。ファイルサイズ上限チェック。 |
| **オフライン環境** | AI解析・Drive保存不可 | オフライン時はキューとしてローカル保存し、オンライン復帰時にWorkManagerで解析・アップロードを実行可能にする。 |
| **Google Play審査でのOAuthスコープ制限** | ストア公開の遅延・却下 | `drive.file` は制限付きスコープ（Restricted scope）ではないが、検証動画やプライバシーポリシーの事前準備を徹底。万一の審査遅延時はGitHub APK配布に即座にフォールバック。 |

---

## 5. 次のステップ（実装着手順）

1. **ステップ 1:** Androidプロジェクトの初期化（Compose、Gradle設定、マニフェスト）
2. **ステップ 2:** Phase 1（免責事項ダイアログ & Gemini APIキーのセキュア保存）の実装
3. **ステップ 3:** Phase 2（Google Drive API接続確認 & フォルダ/CSV初期化ロジック）の実装
4. **ステップ 4:** Phase 3 & 4（共有インテント受付 & Gemini Flashによる構造化抽出）の実装
5. **ステップ 5:** Phase 5（確認編集UI & 保存フロー結合）の実装
