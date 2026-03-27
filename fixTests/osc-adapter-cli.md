# osc-adapter-cli

## 対象

- CLI コマンド解析、schema/list/validate/doc/gen/send の振り分け、動的値パーサ、ドキュメントレンダラー

## 現状

既存テスト:

- `CliAdapterExecuteTest`
- `CliAdapterSchemaCommandTest`
- `CliDynamicValueParserTest`
- `CliAdapterSendIntegrationTest`
- `CliAdapterDocCommandTest`

既に守れている範囲:

- help / version / unknown command の基本分岐
- list / validate の基本出力
- dynamic value parser の基本動作
- send の loopback 成功ケース
- doc コマンドの基本生成

不足している範囲:

- `gen` コマンドの正常系と異常系
- `doc` の renderer 単体検証
- schema path 省略時の解決挙動
- `run` / `send` / `doc` / `gen` のオプション組み合わせ異常系
- doc 出力のエスケープ、空 schema、bundle 表示、tuple array 表示

不適切または弱い既存テスト:

- `CliAdapterDocCommandTest` は CLI 経由の substring 確認のみで renderer の責務を直接見ていない
- `CliAdapterExecuteTest` は usage 文字列に偏っており、実際の設定解釈の粒度が不足している

重複整理候補:

- temp YAML の生成処理が複数テストに重複
- usage 系の smoke test が他モジュールと同型

## 追加するテスト

1. `CliAdapterGenCommandTest` を追加する。
- 正常系: YAML 入力から出力ディレクトリへ生成される。
- 異常系: `--package` 欠落。
- 異常系: 未対応言語。
- 異常系: schema パス不正。

2. `SchemaHtmlDocRendererTest` を追加する。
- 正常系: title、messages、bundles が出る。
- 境界: 空 schema。
- 境界: description に改行や記号が入る。
- 境界: tuple array と fixed length array が表示される。

3. `SchemaMarkdownDocRendererTest` を追加する。
- 正常系: markdown table が出る。
- 境界: `|`、改行、バッククォートのエスケープ。
- 境界: 空 schema。

4. `CliAdapterSchemaResolutionTest` を追加する。
- 正常系: 明示 schema 指定。
- 正常系: デフォルト schema 解決。
- 異常系: 解決不能時のエラー表示。

5. `CliAdapterExecuteTest` を補強する。
- 異常系: `doc --format` 不正値。
- 異常系: `gen` の未知オプション。
- 異常系: `send` の port 値不正。

## 修正する既存テスト

1. `CliAdapterDocCommandTest` は E2E の最小確認に寄せる。
- renderer の表示責務は単体テストへ移す。
- CLI テストではファイル生成、拡張子推論、終了コードに集中する。

2. temp file 作成と adapter 構築を helper 化し、失敗時の読みやすさを上げる。

## 重複整理

1. renderer の内容確認は CLI E2E から減らし、単体テストへ集約する。
2. schema 生成 helper を各テストで再定義しない。

## 推奨検証コマンド

- `./gradlew :osc-adapter-cli:test --no-daemon`

## 要レポート条件

- `gen` コマンドの期待挙動が実装契約として不足しており、テスト側だけで終了コードやエラーメッセージを確定できない。