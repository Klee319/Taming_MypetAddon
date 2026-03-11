## Agent B Review (Codex CLI)

### Critical Issues
- [Severity: Critical] [design-draft.md:24] — LevelledMobs連携がPDCキー直読み前提 (`NamespacedKey(lm, "level")`) で、キー名/型変更時に即破綻し、しかも `0` フォールバックで静かに全体レアリティ分布を壊します。API境界として非常に脆く、運用で気づきにくいです。
- [Severity: Critical] [design-draft.md:8] — Paper 1.21.x + Java 21 を前提にしている一方で、MyPet側の対応バージョン/互換マトリクスが設計にありません。MyPetは内部API変更の影響を受けやすく、起動不可または実行時例外のリスクが高いです。
- [Severity: Critical] [design-draft.md:12] — SQLite利用方針に「メインスレッドで絶対にDB I/Oしない」設計規約がなく、テイム/戦闘/GUI更新頻度を考えるとTPS劣化やサーバ停止要因になります。Bukkit API呼び出しとのスレッド境界設計が未定義です。

### Important Issues
- [Severity: Important] [design-draft.md:253] — `pet_data` が JSON-in-TEXT 多用で、整合性制約・検索性・部分更新が弱いです。装備/スキルの条件検索や将来マイグレーションでコストが急増します。
- [Severity: Important] [design-draft.md:269] — `encyclopedia` に索引設計が不足しています（`owner_uuid` 単独インデックス等）。プレイヤー単位GUI表示で全件走査寄りになり、同時接続増で遅延が出ます。
- [Severity: Important] [design-draft.md:292] — 「全てconfig化」の方針は良いが、スキーマ検証・範囲検証・不正値時フェイルセーフが定義されていません。reload時に壊れた設定で戦闘ロジック全体が不整合化します。
- [Severity: Important] [design-draft.md:120] — Bondの増減イベントに冪等性設計がなく、再ログイン/再召喚/イベント重複発火時の二重加算を防げません。進化・報酬開放の誤判定につながります。
- [Severity: Important] [design-draft.md:146] — 進化時のステータス継承規則が曖昧で、rarity/personality/equipment/skill slot との競合解決順序が未定義です。将来的に回帰バグの温床になります。
- [Severity: Important] [mypet_legacy.js:417] — 既存実装は `/named` で `args[0]` 未検証・状態マップ管理が脆弱で、設計移行時に同種の入力検証/状態整合バグを持ち込みやすいです（名前空文字、期限切れ、参照不整合）。
- [Severity: Important] [mypet_legacy.js:345] — 旧実装に旧式文字列サウンドキーが残っており、1.21系互換で壊れやすい点が示唆されています。新設計でも `Sound` enumベースに統一しないと互換事故が再発します。

### Suggestions
- [design-draft.md:23] — LevelledMobsは可能なら公式API経由、PDCは最終フォールバックにし、キー存在/型不一致をメトリクス・警告ログ化してください。
- [design-draft.md:8] — `plugin.yml` で依存バージョン範囲を明示し、起動時に MyPet/LevelledMobs/Paper の互換チェックを実施して fail-fast させてください。
- [design-draft.md:12] — 「DBは非同期、Bukkitエンティティ操作は同期」の2層実行モデルを明文化し、キュー/バッチ書き込み（例: 1-2秒集約）を導入してください。
- [design-draft.md:253] — ハイブリッド案（頻出検索項目は正規化列、可変属性のみJSON）にし、`CHECK(json_valid(...))` と必要インデックスを追加してください。
- [design-draft.md:292] — ConfigにJSON Schema相当の検証を入れ、`/petadmin reload` は検証成功時のみアトミック反映する設計にしてください。
- [design-draft.md:146] — 効果適用順序を仕様化してください（例: base -> rarity -> personality -> bond -> equipment -> temporary buffs）。
- [design-draft.md:279] — コマンドごとに権限ノード、レート制限、監査ログを定義し、管理系コマンドの誤用対策を入れてください。

### Positive Observations
- 機能分割（rarity/personality/bond/evolution/skills/equipment/encyclopedia）が明確で、段階導入（Phase 1-3）も実装計画として妥当です。
- バランス値の外部化方針は運用面で強く、ゲームデザイン調整をコード変更なしで回せる設計意図は良いです。
- Legacy移行対象（テイム方式・基礎ステータス）を明示しており、既存プレイ体験の連続性を意識できています。

### Summary
全体像はよく整理されていますが、現状は依存互換性・LevelledMobs連携の堅牢性・スレッド境界/DB実行モデルが未確定で、本番運用には危険です。上記Criticalを先に潰せば、拡張性の高い設計として成立可能です。