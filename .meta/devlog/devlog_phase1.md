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

#### ④ Android Studio すらいらない！？ 超軽量 CLI による実機直結ビルドの衝撃
* **「Android開発＝数GBの重厚な Android Studio が必須」という固定観念の打破:**
  * 通常のチュートリアルでは「まず Android Studio をダウンロードして数十分待つ」のが定番だが、実はコアである **Gradle** と **JDK 17**、そして最小限の **Android Command-line Tools (adb)** さえあれば、ターミナルから実機ビルド・転送・スクショ保存まで全自動で完結する。
  * 実際に `Microsoft.OpenJDK.17` ＋ `Google.PlatformTools` ＋ `cmdline-tools` (SDK 35) のみで、`./gradlew.bat assembleDebug` を実行し、**わずか 17秒 で本番用 debug APK（23.5MB）の生成に成功！**
  * 重たい IDE を立ち上げずに CLI だけで完結するため、PC 動作も極めて軽快。
* **note記事としてのバリュー:**
  * 「環境構築で挫折する人」や「PCのディスク容量を食われたくない個人開発者」にとって、この『超軽量 CLI 実機ビルドハック』は最高に実用的で面白いトピックになる。



---

### 3. 生成・保存したビジュアル素材（実機キャプチャ）
- [Docs/images/real_pixel9a_disclaimer.png](file:///d:/userdata/documents/dev/keihix/Docs/images/real_pixel9a_disclaimer.png)（実機 Pixel 9a で動作する初回免責事項画面）
- [Docs/images/real_pixel9a_settings.png](file:///d:/userdata/documents/dev/keihix/Docs/images/real_pixel9a_settings.png)（実機 Pixel 9a で動作する設定・BYOK管理画面）
- [Docs/images/ui_disclaimer.jpg](file:///d:/userdata/documents/dev/keihix/Docs/images/ui_disclaimer.jpg)（初期UIモックアップ）
- [Docs/images/ui_settings_byok.jpg](file:///d:/userdata/documents/dev/keihix/Docs/images/ui_settings_byok.jpg)（初期UIモックアップ）

---

### 4. 今回の実機検証で得られた note 記事のコア・ハイライト
1. **「Android Studio 不要」の衝撃:**
   * 重厚な IDE を使わず、JDK 17 + Android SDK 35 + adb だけで 15秒 ビルド＆実機転送を達成。
2. **免責同意によるブロックゲートが実機で完璧に機能:**
   * 3つのチェックボックスを全て ON にしないと「同意してはじめる」ボタンが押せないガードが実機で動作。
   * 同意完了後に Preference が更新され、設定・BYOK画面へのスムーズな画面遷移を確認。
3. **実機インセット対応のリアル:**
   * Pixel 9a のカメラホール・時計との被りを `statusBarsPadding()` で解消したリアルなチューニング過程。

