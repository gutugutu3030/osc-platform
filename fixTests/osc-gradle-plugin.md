# osc-gradle-plugin

## 対象

- Gradle plugin 適用、拡張設定、generate task、worker isolation、出力連携

## 現状

既存テスト:

- なし

既に守れている範囲:

- README に動作説明はある
- 他モジュール経由で間接的に存在しているだけで、plugin 自体の自動検証はない

不足している範囲:

- plugin apply 時の extension / task 登録
- Kotlin / Java plugin への sourceSet 連携
- `GenerateOscSourcesTask` の入力と出力
- `GenerateOscSourcesWorkAction` の出力書き込み
- `collectWorkerClasspath()` の fallback
- build cache / incremental 的な契約

不適切または弱い既存テスト:

- 該当なし

重複整理候補:

- 該当なし

## 追加するテスト

1. `OscSchemaCodegenPluginTest` を追加する。
- 正常系: plugin apply で extension が作られる。
- 正常系: `generateOscSources` task が登録される。
- 正常系: Kotlin plugin 適用時に sourceSet へ出力が追加される。
- 正常系: Java plugin 適用時に sourceSet へ出力が追加される。

2. `GenerateOscSourcesTaskTest` を追加する。
- 正常系: worker へ schema、packageName、language、outputDirectory が渡る。
- 正常系: workerClasspath が isolation に設定される。

3. `GenerateOscSourcesWorkActionTest` を追加する。
- 正常系: 生成されたファイルが出力ディレクトリに書かれる。
- 正常系: 既存出力が削除されてから再生成される。
- 異常系: codegen 例外時に失敗する。

4. Gradle TestKit による機能テストを追加する。
- 正常系: `schema.yaml` から `generateOscSources` が成功する。
- 正常系: `schema.kts` から `generateOscSources` が成功する。
- 正常系: `build` で生成コードが compile 対象になる。
- 異常系: unsupported language で失敗する。

5. cache / up-to-date テストを追加する。
- 正常系: 2 回目実行で task が UP-TO-DATE または FROM-CACHE になる。

## 修正する既存テスト

- 該当なし。新規追加を優先する。

## 重複整理

1. 単体テストは task / worker の局所責務に限定する。
2. TestKit は plugin 全体の統合確認に限定する。

## 推奨検証コマンド

- `./gradlew :osc-gradle-plugin:test --no-daemon`

## 要レポート条件

- plugin の可観測性が不足し、`src/main` を変更しないと worker parameter や classpath 収集結果を検証できない。