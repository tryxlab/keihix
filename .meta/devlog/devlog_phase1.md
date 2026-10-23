# KeihiX 開発日誌メモ: Phase 0 & Phase 1 (環境構築・免責同意・BYOK設定)

本ドキュメントは、note 連載記事（第2回・第3回）の執筆に向け、開発中の試行錯誤、技術的選定の理由、直面した課題と解決策を時系列で記録する開発者ログ（DevLog）です。

---

## 2026-10-23: Phase 0 & Phase 1 実装完了

### 1. 今回実装したスコープ
- **Phase 0: Android プロジェクト基盤の構築**
  - バージョンカタログ (`gradle/libs.versions.toml`) による依存関係一元管理
  - Jetpack Compose + Material 3 によるフィンテック向けダークテーマ構築
  - Proguard ルール、AndroidManifest（共有インテント受付準備）
- **Phase 1: 初回免責事項同意 & BYOK 暗号化ストレージ**
  - 初回起動時ブロック型免責同意画面 (`DisclaimerScreen.kt`, `DisclaimerViewModel.kt`)
  - 端末内限定 `EncryptedSharedPreferences` (`SecureKeyManager.kt`)
  - 設定・キー検証画面 (`SettingsScreen.kt`, `SettingsViewModel.kt`)
  - 国税庁ひな形「事務処理規程」アセット同梱 (`app/src/main/assets/kitei_kojin.md`)

---

### 2. 開発・設計のリアルな試行錯誤ログ（noteネタ帳）

#### ① 免責事項画面（Disclaimer）をなぜ「初回強制」にしたのか？
* **背景・課題:**
  個人開発者が税務・確定申告に関わるアプリを出すとき、最大の法的地雷は「税理士法」と「電子帳簿保存法の要件不備」。
  「AIが自動で仕訳してくれたから大丈夫だと思ったのに、税務調査で否認された」と言われたらひとたまりもない。
* **設計の割り切り:**
  * 初回起動時に「税務助言を行わないこと」「仕訳確定は自己責任であること」「電帳法は事務処理規程の備え付けで運用すること」の3点すべてにチェックを入れないと、メイン画面に進めないブロックゲートを作った。
  * 「事務処理規程」のPDF/テキストをユーザーがわざわざ国税庁HPから探すのは面倒なので、アプリ内のアセットとして最初から同梱し、1タップで全文確認・共有できるようにした。

#### ② BYOK（Bring Your Own Key）のキー管理で悩んだこと
* **なぜ EncryptedSharedPreferences なのか？**
  * 通常の `SharedPreferences` は平文保存されるため、Root化端末やバックアップからキーが漏洩するリスクがある。
  * AndroidX Security の `EncryptedSharedPreferences` は Android Keystore を使ってハードウェアレベルでキーを暗号化（AES-256 GCM / SIV）するため、ゼロ・インフラの思想（開発者もユーザーのキーを知らない・持たない）をクライアント完結で実現できる。
* **接続テストの工夫:**
  * 単にローカルで保存するだけでなく、入力直後に `models/gemini-1.5-flash` の軽量エンドポイントへ疎通テストを行い、キーの有効性（打ち間違いや権限エラー）を即座にUIへフィードバックする設計にした。

#### ③ UIデザイン（スクショ）のこだわり
* フィンテックらしい信頼感を持たせるため、深いネイビーブラック（`#0D1117`）を基調に、エメラルドグリーン（`#2EA043`）とティール（`#58A6FF`）をアクセントカラーに採用。
* note記事で読者の目を惹くため、Material 3に準拠したスマホ画面モックアップ画像（免責画面・BYOK設定画面）を作成し、`Docs/images/` に保存。

---

### 3. 生成・保存したビジュアル素材
- [Docs/images/ui_disclaimer.jpg](file:///d:/userdata/documents/dev/keihix/Docs/images/ui_disclaimer.jpg)（免責事項・電帳法同意画面）
- [Docs/images/ui_settings_byok.jpg](file:///d:/userdata/documents/dev/keihix/Docs/images/ui_settings_byok.jpg)（BYOK APIキー設定・Google Drive連携画面）

---

### 4. 次のステップ
- **Phase 2:** Credential Manager による Google アカウント認証 & Google Drive フォルダ自動生成・`transaction_log.csv` 追記マネージャーの実装
- **note第2回の執筆:** 本ログを元に「電帳法＆免責編」のドラフトを作成
