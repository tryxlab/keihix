# KeihiX 開発日誌メモ: Phase 3 (Google Drive 直結・drive.file スコープ・CSV追記)

本ドキュメントは、note 連載記事（第4回）の執筆に向け、Android から Google Drive を自作ストレージ＆データベースとして活用する実装過程、技術選定の理由、直面した課題とトレードオフを記録する開発者ログ（DevLog）です。

---

## 2026-11-02: Phase 3 実装完了

### 1. 今回実装したスコープ
* **Google 認証・認可マネージャー (`GoogleAuthManager.kt`)**
  * 最小権限原則に基づく `drive.file` スコープ（`https://www.googleapis.com/auth/drive.file`）の認可。
  * `GoogleAccountCredential` を用いたアカウント選択 Intent 生成、サインイン状態の StateFlow 管理。
  * 認可済み `com.google.api.services.drive.Drive` サービスのオンデマンド生成。
* **Drive ストレージリポジトリ (`DriveStorageRepository.kt`, `GoogleDriveStorageRepository.kt`)**
  * ルートフォルダ `確定申告_{YYYY}`、サブフォルダ `receipts/`（経費）、`incomes/`（収入）の自動探索・生成。
  * フォルダ ID のメモリキャッシュによる重複 API コール抑制。
  * 電帳法検索3要件ファイル名（`{YYYYMMDD}_{金額}_{取引先}.{拡張子}`）による領収書・明細ファイルのアップロード。
  * `transaction_log.csv` への Read-Modify-Write 追記ロジック。
* **CSV レコーダー (`CsvTransactionRecorder.kt`)**
  * UTF-8 BOM（`0xEF, 0xBB, 0xBF`）の自動付与（Windows Excel で直接開いた際の日本語文字化け防止）。
  * ヘッダー付き初期バイト列の生成と、既存 CSV への改行コード整合性を考慮した行追記処理。
* **UI 連携 (`SettingsScreen.kt`, `ExtractionScreen.kt`)**
  * `SettingsScreen`: Google アカウント連携状態のリアルタイム表示、アカウント選択ランチャー起動、連携解除機能。
  * `ExtractionScreen`: 抽出結果手修正フォームからの「Google Drive に保存する」ボタン実行、保存中インジケータ、成否フィードバック表示。
* **単体テスト (`CsvTransactionRecorderTest.kt`, `GoogleDriveStorageRepositoryTest.kt`)**
  * BOM 付与・ヘッダー生成・複数件追記マージの整合性テスト。

---

## 2. 開発・設計のリアルな試行錯誤ログ（note 第4回のネタ帳）

### ① Google Drive REST API に「Append（行追加）API」が存在しない壁
* **直面した仕様:**
  * Google Cloud Storage や S3 などのオブジェクトストレージと同様に、Google Drive API v3 には「ファイル末尾にデータを追記する」エンドポイントが存在しない。
  * CSV を台帳（データベース）として使うには、**「既存ファイルをダウンロード → ローカルで追記 → 上書きアップロード（Update）」という Read-Modify-Write パターン** を取る必要がある。
* **トレードオフと割り切り:**
  * 複数ユーザーが秒単位で書き込むサービスであればレースコンディション（競合破壊）が致命傷になる。
  * しかし、副業・個人開発者の確定申告というユースケースでは「本人がスマホから1件ずつ確認して保存する（月数十件程度）」ため、単一端末からの逐次保存であれば極めてシンプルかつ堅牢に成立する。
  * 通信断やリトライへの備えは、将来の WorkManager 連携（Phase 5）で担保する。

### ② なぜフル権限 (`drive`) ではなく `drive.file` スコープなのか？
* **セキュリティと心理的ハードル:**
  * `https://www.googleapis.com/auth/drive` を要求すると、Google の同意画面で「Google ドライブ内のすべてのファイルの表示、編集、作成、削除」という強い警告が出る。
  * ユーザーのプライベートな Drive 全体にアクセス権を持つアプリは、オープンソースであっても心理的抵抗が大きい。
* **`drive.file` の境界:**
  * 「本アプリが作成したファイル・フォルダのみ」に操作を限定できる。
  * 他の個人的なファイルや写真は一切見えない・触れないため、「ゼロ・インフラ＆プライバシー保護」の思想に最も適合する。
* **潜む落とし穴（note で言及すべきリアルな弱点）:**
  * アプリをアンインストール・再インストールした場合、同じ Google アカウントであっても「別クライアント」と判定され、以前作成した `確定申告_2026` フォルダへのアクセス権が引き継がれない場合がある。この挙動の割り切りと対策（別名作成や手動共有）を開示する。

### ③ Excel 文字化け問題と UTF-8 BOM
* **国税庁コーナー・Excel での利用を想定:**
  * 日本の確定申告現場では、出力された `transaction_log.csv` を Windows の Excel で開いて検算・集計するケースが圧倒的に多い。
  * 通常の UTF-8 で CSV を出力すると、Excel でダブルクリックした際に日本語（店名や勘定科目）が「文字化け」する。
  * 先頭に `0xEF, 0xBB, 0xBF`（UTF-8 BOM）を付与することで、Excel でも文字化けせず、そのままスムーズに閲覧・加工できる。

---

## 3. 次のステップ（note 第4回執筆 & PR作成）
1. 本実装差分をコミットし、PR を作成する。
2. note 記事（`04_google_drive_storage_backend.md`）のドラフトを作成する。
