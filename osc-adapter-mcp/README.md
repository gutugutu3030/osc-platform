# osc-adapter-mcp

OSC スキーマから MCP (Model Context Protocol) ツールを自動生成し、stdio サーバとして動作するアダプタモジュールです。  
エンドユーザーは [`osc-cli`](../osc-cli/README.md) 経由で利用します。このモジュールを直接実行することは想定していません。

## 機能概要

- スキーマのメッセージ定義から MCP ツールを自動生成
- バンドル定義に対応したバンドルツールも生成
- MCP stdio プロトコルで LLM クライアントからの呼び出しを受け付け
- 受け取った引数を検証して OSC メッセージとして UDP 送信

## ツール名の変換ルール

| OSC パス | MCP ツール名 |
|---|---|
| `/light/color` | `set_light_color` |
| `/mesh/points` | `set_mesh_points` |

バンドルのツール名: `bundle_<bundleName>`  
スキーマの `name` フィールドで上書き可能です。

## 起動方法

```
osc mcp [schemaPath] [--schema <path>] --host <targetHost> --port <targetPort>
```

| オプション | 説明 |
|---|---|
| `--schema <path>` | スキーマファイルパス（省略時はカレントディレクトリ自動探索） |
| `--host <host>` | OSC 送信先ホスト（必須） |
| `--port <port>` | OSC 送信先ポート（必須） |

例:

```bash
osc mcp schema.yaml --host 127.0.0.1 --port 9000
```

## 対応 MCP メソッド

| メソッド | 説明 |
|---|---|
| `initialize` | 初期化ハンドシェイク |
| `tools/list` | スキーマから生成されたツール一覧を返す |
| `tools/call` | 指定ツールを呼び出し OSC 送信 |
| `prompts/list` | 利用可能なプロンプト一覧を返す |
| `prompts/get` | 指定プロンプトの内容を返す |
| `shutdown` | シャットダウン通知 |
| `exit` | サーバ終了 |

## ツール強制ルーター（tool_force_router プロンプト）

このサーバは MCP `prompts` 機能を通じて **`tool_force_router`** というシステムプロンプトを公開しています。

LLM クライアントがこのプロンプトを取得することで、以下のルーティング動作が有効になります。

### 判定対象キーワード

**時間依存・最新性依存**（必ず現在時刻ツールを使う）:
- 最近, 最新, 今, 現在, 直近, さっき, 先ほど, いま, 今日, 昨日, 今週, 先週, 直近数日, この前

**記憶・履歴依存**（記憶系 MCP ツールへルーティング）:
- 最近の思い出, 直近の会話, 以前の出来事, これまでの履歴, 写真検索, 過去の行動や記録

### プロンプト取得例

```json
{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"tool_force_router"}}
```

### ルーティング方針

1. 時間表現や最新性が関係する場合 → current time 系ツールを呼ぶ
2. 最新情報が必要な場合 → web search 系ツールを呼ぶ
3. 記憶・履歴に関する場合 → memory 系ツールへルーティング
4. 複数意図がある場合 → 必要なツールを順番に組み合わせる
5. ツールを呼ばずに推測で答えない（禁止）

## LLM クライアントとの連携例（Claude Desktop）

`claude_desktop_config.json` に以下を追加します:

```json
{
  "mcpServers": {
    "osc": {
      "command": "/path/to/osc-cli/build/install/osc-cli/bin/osc-cli",
      "args": ["mcp", "--schema", "/path/to/schema.yaml", "--host", "127.0.0.1", "--port", "9000"]
    }
  }
}
```

## 依存関係

```
osc-adapter-mcp
├── osc-core
├── osc-transport-udp
└── kotlinx-coroutines-core
```
