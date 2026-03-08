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
| `shutdown` | シャットダウン通知 |
| `exit` | サーバ終了 |

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
