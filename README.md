# KeihiX (経費X)

> **ゼロ・インフラ ＋ BYOK（Bring Your Own Key）で実現する、確定申告・電帳法対応のレシート＆収入管理Androidアプリ**

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)
![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)

---

## 概要

**KeihiX** は、副業や個人開発で少額の経費・売上が発生するエンジニア・個人事業主向けのクライアント完結型レシート＆収入管理ツールです。

* **サーバー代・DB運用費 完全0円:** 開発者側でサーバーやデータベースを一切持ちません。
* **個人情報保持ゼロ（Zero Knowledge）:** 領収書ファイルや取引金額データは、すべてユーザー自身の Google Drive 内にのみ保存されます。
* **Gemini による自動抽出 ＆ 台帳 AI 質問:** Google AI Studio の無料APIキー（BYOK）を利用し、画像・PDFから日付・金額・取引先・科目を一撃で構造化抽出します（`Gemini 3.1 Flash-Lite` を基本とし、503混雑時は `Gemini 2.5 Flash` へ自動フォールバック）。
* **スマホ完結の収支サマリ・CSV共有・安全削除:** Google Drive 上の `transaction_log.csv` を読み込んで年次・科目別・月別サマリを表示し、確定申告転記用の共有・重複チェック＆安全削除も行えます。
* **電帳法の合法クリア:** 高価なタイムスタンプAPIを使わず、国税庁ひな形の「事務処理規程」の備え付けと「ファイル命名規則＋CSV台帳」で法定要件を満たします。

---

## 全体アーキテクチャ

```text
[ユーザー操作: カメラ撮影 / PDF・画像共有インテント]
       │
       ▼
[Android アプリ (端末内完結)]
  ├─ 1. Gemini API (BYOK: Gemini 3.1 Flash-Lite / 2.5 Flash)
  │       └─ 端末から直接通信。画像/PDFからメタデータを構造化抽出 & 台帳データQ&A
  ├─ 2. ローカル検証・UI修正・収支サマリ
  │       └─ 抽出結果の確認と手修正、科目別・月別集計、CSV共有
  └─ 3. Google Drive API (OAuth 2.0: scope 'drive.file')
          └─ WorkManagerによる非同期アップロード & 圏外時ローカル退避
       │
       ▼
[保存先: ユーザー本人の Google Drive]
  └── 確定申告_2026/
        ├── receipts/           (経費の画像・PDF)
        ├── incomes/            (収入の支払明細・請求書控えPDF)
        ├── pending/            (未解析の一時退避ファイル)
        └── transaction_log.csv (統合トランザクション台帳)
```

### なぜ Android ネイティブアプリなのか？
OSの **「共有メニュー（Share Target: `ACTION_SEND`）」** と連携することで、ブラウザやPDFビューアで領収書を開いた状態から **「共有 → KeihiX」の1ステップ** で直接解析・保存できます。日々の入力摩擦をゼロにすることで、確定申告前の溜め込みを防止します。

---

## 電帳法（電子帳簿保存法）への対応アプローチ

1. **改ざん防止措置（タイムスタンプ不要論）:**
   * 国税庁が公開している「電子取引データの訂正及び削除の防止に関する事務処理規程（個人事業主用）」を手元に備え付けることで、有償のタイムスタンプAPIを使わずに法要件をクリアします。
2. **検索3要件の充足:**
   * **ファイル命名規則:** `YYYYMMDD_金額_取引先.拡張子`
   * **同一フォルダ内のCSV台帳:** 日時・金額・取引先・科目を記録した `transaction_log.csv` を自動更新。

---

## ドキュメント一覧

* 📖 [利用ガイド (user_guide.md)](Docs/user_guide.md) - 初めての方向けの初期設定・日常の使い方・APIキー設定トラブル対応
* 🛒 [ストア掲載・購入前注意喚起ガイド (store_listing_and_setup_guide.md)](Docs/store_listing_and_setup_guide.md) - Google Play 配布時の事前確認文・サポート案内
* 📄 [全体仕様書 (original_spec.md)](Docs/original_spec.md) - システム要件・データスキーマ・電帳法設計
* 📋 [実装計画書 (implementation_plan.md)](Docs/implementation_plan.md) - フェーズ別の開発ロードマップ
* 📝 [開発・執筆ガイドライン (GEMINI.md)](GEMINI.md) - プロジェクト開発方針・トーン＆マナー
* 📚 [開発日記 (note連載ドラフト)](.meta/articles/) - 開発プロセスや試行錯誤を記録した開発日誌

---

## 開発日誌（note連載）

実際の開発プロセスやハマりどころを、noteにて「開発日記」として順次公開しています。

* **#1:** [【KeihiX開発日記#1】月額課金したくない個人開発者が、サーバー代0円・個人情報保持ゼロの「レシート＆収入管理アプリ」を考え始めた話](.meta/articles/01_concept_and_architecture.md)
* **#2:** [【KeihiX開発日記#2】Android Studioすら不要だった？高価なタイムスタンプを使わず電帳法をクリアする事務処理規程と、CLIだけで実機（Pixel 9a）を動かした話](.meta/articles/02_compliance_and_cli_setup.md)
* **#3:** [【KeihiX開発日記#3】Geminiで領収書＆売上明細をパースする。Structured OutputsとBYOKの実装ログ（実測3.0秒）](.meta/articles/03_gemini_structured_extraction.md)
* **#4:** [【KeihiX開発日記#4】Google Driveを自作DBにする。drive.fileスコープとCSV追記（Read-Modify-Write）の実装ログ](.meta/articles/04_google_drive_storage_backend.md)
* **#5:** [【KeihiX開発日記#5】実機の503エラー・OAuth同意・重複保存の罠を越えて。共有インテントとWorkManager、「あとで解析」のUX実装ログ](.meta/articles/05_android_ux_share_workmanager.md)
* **#6:** [【KeihiX開発日記#6】DriveのCSVからスマホ向け収支サマリ・AI質問・重複削除まで。503対策と実機検証の実装ログ](.meta/articles/06_summary_export_ai_qa.md)

---

## 免責事項 (Disclaimer)

* 本ソフトウェアおよび関連ドキュメントは、税理士法に基づく税務相談や税務申告書類の作成を行うものではありません。
* AI（Gemini）による抽出精度および電帳法・確定申告における法令適合性について、開発者は一切の保証を行いません。
* 最終的な税務判断、勘定科目の決定、および確定申告書類の内容確認は、すべてユーザー自身の責任において行ってください。
* 本ソフトウェアの利用により生じたいかなる不利益・損害についても、開発者は責任を負いかねます。

---

## ライセンス

[MIT License](LICENSE)
