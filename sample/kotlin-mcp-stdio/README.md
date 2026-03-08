# kotlin-mcp-stdio

MCP アダプタの最小 E2E テストサンプルです。  
stdio フレーム（`Content-Length` ヘッダ付き JSON-RPC）を使って `McpAdapter` を直接テストします。

## 目的

- `Content-Length` + CRLF + JSON ペイロードという MCP stdio フレーム形式を理解する
- `System.setIn` / `System.setOut` を一時差し替えることで MCP の公開 API をテスト駆動する方法を習得する
- `tools/list` によるツール一覧取得と、`bundle_set_scene` ツールコールの成功検証を行う

## 前提

- JDK 21

## 実行手順

```bash
./gradlew -p sample/kotlin-mcp-stdio test
```

テスト結果レポートを HTML で確認する場合：

```bash
open sample/kotlin-mcp-stdio/build/reports/tests/test/index.html
```

## 期待される動作

| テスト | 内容 |
|--------|------|
| `toolsListContainsBundleSetScene` | `tools/list` の応答に `bundle_set_scene` が含まれること |
| `toolsCallBundleSetSceneReturnsSuccess` | `tools/call bundle_set_scene` が JSON-RPC 成功応答を返すこと |

## MCP stdio フレーム形式

```
Content-Length: <バイト数>\r\n
\r\n
<JSON ペイロード>
```

例：
```
Content-Length: 52\r\n
\r\n
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

## テスト設計のポイント

1. **入力フレーム作成**: `List<String>` (JSON-RPC リクエスト) を `buildFrame()` でエンコード
2. **サーバ駆動**: `System.setIn` で差し替えた入力が尽きると McpAdapter が EOF を検知して終了
3. **出力キャプチャ**: `System.setOut` で差し替えた `ByteArrayOutputStream` にレスポンスが書き込まれる
4. **応答パース**: `parseFrames()` で Content-Length を読み、各レスポンスを `ObjectNode` に変換
5. **復元保証**: `try/finally` で `System.in` / `System.out` を必ず元に戻す

## ファイル構成

```
schema.yaml                          ← /light/color, /device/flag, bundle set_scene
src/
  test/
    kotlin/com/example/
      McpStdioTest.kt                ← E2E テスト本体
```
