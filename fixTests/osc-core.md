# osc-core

## 対象

- スキーマモデル、DSL、ローダ、Runtime、namedArgs ヘルパー、timeTag

## 現状

既存テスト:

- DSL: `OscSchemaDslBundleTest`, `OscSchemaDslStructuredArgsTest`, `OscSchemaDslMarkerTest`
- YAML ローダ: `YamlSchemaLoaderFullSchemaTest`, `YamlSchemaLoaderStructuredArgsTest`, `YamlSchemaLoaderBundleTest`
- パス解決: `SchemaPathResolverTest`
- バリデータ: `OscArgNodeValidatorTest`
- Runtime: `OscRuntimeBoolBlobTest`, `OscRuntimeHandlerRegistrationTest`, `OscRuntimeBundleSendTest`, `OscRuntimeStructuredArgsTest`
- 依存境界: `JarIsolationTest`

既に守れている範囲:

- YAML スキーマの代表的な正常系
- structured args の送受信
- bundle 送信と `OscTimeTag` の一部
- DSL の基本構築

不足している範囲:

- `KotlinScriptSchemaLoader` の正常系と異常系
- `SchemaLoader` の拡張子振り分け
- `OscNamedArgs` の型安全ヘルパー群
- `SchemaPathResolver` の明示パス、未検出、YAML 単独などの分岐
- Runtime の start/stop の境界条件

不適切または弱い既存テスト:

- `JarIsolationTest` はテスト classpath に依存しており、配布物や依存境界の保証としては弱い
- `OscRuntimeBundleSendTest` の `OscTimeTag` 検証は別責務が混在している

重複整理候補:

- `osc-cli` の `JarIsolationTest` と責務が近い
- `OscTimeTag` の単体検証を bundle 送信テストから分離可能

## 追加するテスト

1. `KotlinScriptSchemaLoaderTest` を追加する。
- 正常系: `.kts` が `OscSchema` を返す。
- 異常系: `OscSchema` を返さないスクリプトで失敗する。
- 異常系: スクリプト評価例外がそのまま観測できる。

2. `SchemaLoaderTest` を追加する。
- 正常系: `.yaml` を YAML ローダへ委譲する。
- 正常系: `.yml` を YAML ローダへ委譲する。
- 正常系: `.kts` を script ローダへ委譲する。
- 異常系: 未対応拡張子で失敗する。

3. `OscNamedArgsTest` を追加する。
- 正常系: `oscTyped`, `oscTypedList`, `oscTypedMapList` が正しい型を返す。
- 異常系: 必須キー欠落。
- 異常系: スカラー型不一致。
- 異常系: list 期待に対して scalar を渡す。
- 異常系: map list に map 以外が混ざる。

4. `SchemaPathResolverTest` を拡張する。
- 正常系: 明示パス優先。
- 正常系: YAML のみ存在時に YAML を選ぶ。
- 異常系: 候補が存在しないとき失敗する。

5. `OscRuntimeLifecycleTest` を追加する。
- 正常系: `start()` で transport start が呼ばれる。
- 正常系: `stop()` で transport stop が呼ばれる。
- 境界: 複数回 stop が安全か確認する。

## 修正する既存テスト

1. `JarIsolationTest` は削除ではなく扱いを変更する。
- 単体テストとして残す場合は「テスト classpath のスモーク確認」と明記する。
- 依存境界の保証は別レイヤーで担保する前提に変更する。

2. `OscRuntimeBundleSendTest` の `OscTimeTag` 検証を独立ファイルへ移す。
- bundle 送信の責務と timeTag 変換責務を分ける。

## 重複整理

1. `JarIsolationTest` の責務を `osc-cli` 側と二重管理しない。
2. `OscTimeTag` 単体検証を runtime bundle 送信の成功ケースと混ぜない。

## 推奨検証コマンド

- `./gradlew :osc-core:test --no-daemon`

## 要レポート条件

- `KotlinScriptSchemaLoader` の不具合が `src/main` 修正なしでは再現も回避もできない。
- Runtime ライフサイクルの期待挙動が公開契約として曖昧で、テスト期待値を確定できない。