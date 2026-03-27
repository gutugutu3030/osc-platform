# osc-adapter-mcp

## 対象

- MCP adapter の CLI 分岐、stdio / streamable HTTP 統合、tool schema 生成、bundle tool、Web UI 連携イベント

## 現状

既存テスト:

- `McpSchemaJsonSupportTest`
- `McpAdapterExecuteTest`
- `McpToolsCallIntegrationTest`
- `McpBundleToolsIntegrationTest`
- `McpStreamableHttpIntegrationTest`
- `McpIntegrationTestSupport`

既に守れている範囲:

- tool schema の基本生成
- stdio の tools/call 成功、unknown tool、transport failure
- bundle tool の list / call 基本動作
- streamable HTTP の基本成功 / failure
- CLI の主要起動分岐

不足している範囲:

- CLI 引数の不正組み合わせ全体
- tools/call の引数型不一致や必須不足の詳細エラー
- Web UI 向け追加イベントの payload
- `tools/list` の順序や description 欠落時の扱い
- 並列 request や連続 request の安定性
- streamable HTTP の接続失敗や初期化失敗

不適切または弱い既存テスト:

- 一部のフェイク transport が各テストファイルに分散していて、責務が読みにくい
- CLI execute テストと transport 統合テストの境界が曖昧な箇所がある

重複整理候補:

- `McpToolsCallIntegrationTest` と `McpStreamableHttpIntegrationTest` の成功 / failure 行列が近い
- `sample/kotlin-mcp-stdio` の E2E と bundle 系成功ケースが近い

## 追加するテスト

1. `McpAdapterArgumentValidationTest` を追加する。
- 異常系: schema 未指定。
- 異常系: port 値不正。
- 異常系: `--streamable-http-port` と `--listen-host` の組み合わせ境界。
- 異常系: 未知オプション。

2. `McpToolsCallValidationTest` を追加する。
- 異常系: 引数の型不一致。
- 異常系: required 引数欠落。
- 異常系: array / tuple 引数の JSON 形状不正。

3. `McpWebUiEventBridgeTest` を追加する。
- 正常系: `tools/list` が Web UI の追加イベントへ流れる。
- 正常系: `tools/call` success / failure がイベント化される。

4. `McpStreamableHttpIntegrationTest` を補強する。
- 異常系: クライアント接続前後の起動失敗。
- 境界: 複数 request の順序保持。

## 修正する既存テスト

1. 共通の fake transport / schema helper を `McpIntegrationTestSupport` へ寄せる。
2. transport 別テストでは transport 差分のみを確認し、tool 成功メッセージ内容の重複確認を減らす。

## 重複整理

1. stdio と streamable HTTP で同じ行列を全部繰り返さず、共通契約と transport 差分を分ける。
2. `sample/kotlin-mcp-stdio` と重複する bundle 成功ケースは、本体側を仕様検証、sample 側を最小 E2E に役割分担する。

## 推奨検証コマンド

- `./gradlew :osc-adapter-mcp:test --no-daemon`

## 要レポート条件

- Web UI 連携イベントの期待仕様が `src/main` の実装解釈に依存し、テスト側だけで確定できない。
- MCP SDK 側の挙動に起因する非決定性があり、テスト補助コードだけでは安定化できない。