# osc-adapter-mcp

OSC スキーマから MCP (Model Context Protocol) ツールを自動生成し、stdio または Streamable HTTP サーバとして動作するアダプタモジュールです。  
エンドユーザーは [`osc-cli`](../osc-cli/README.md) 経由で利用します。このモジュールを直接実行することは想定していません。

## 機能概要

- スキーマのメッセージ定義から MCP ツールを自動生成
- バンドル定義に対応したバンドルツールも生成
- MCP stdio と Streamable HTTP の両 transport に対応
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
osc mcp [schemaPath] [--schema <path>] --host <targetHost> --port <targetPort> [--streamable-http-port <port>] [--listen-host <host>] [--webui] [--webui-port <port>]
```

| オプション | 説明 |
|---|---|
| `--schema <path>` | スキーマファイルパス（省略時はカレントディレクトリ自動探索） |
| `--host <host>` | OSC 送信先ホスト（必須） |
| `--port <port>` | OSC 送信先ポート（必須） |
| `--streamable-http-port <port>` | 指定すると Streamable HTTP モードで待受。省略時は stdio モード |
| `--listen-host <host>` | Streamable HTTP の待受ホスト。既定値 `127.0.0.1` |
| `--webui` | MCP request / success / failure と OSC 送信テストを表示する Web UI を起動 |
| `--webui-port <port>` | Web UI の HTTP ポート（既定値 `8080`） |

例:

```bash
osc mcp schema.yaml --host 127.0.0.1 --port 9000
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --streamable-http-port 8081
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --streamable-http-port 8081 --listen-host 0.0.0.0
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --webui --webui-port 18082
```

## transport モード

- stdio モード: `--streamable-http-port` を付けない場合に有効。Claude Desktop などのローカル MCP クライアント向け
- Streamable HTTP モード: `--streamable-http-port` を付けると有効。エンドポイントは `/mcp`

注意:

- `--host` と `--port` は OSC 送信先です。HTTP の待受先ではありません
- `--listen-host` は Streamable HTTP モードでのみ利用できます
- `--webui-port` と `--streamable-http-port` は同じ値にできません
- `127.0.0.1` のままでは同一マシンからのみ接続できます。他の PC から接続する場合は `0.0.0.0` か具体的な NIC の IP を指定してください

## 対応 MCP メソッド

| メソッド | 説明 |
|---|---|
| `initialize` | 初期化ハンドシェイク |
| `tools/list` | スキーマから生成されたツール一覧を返す |
| `tools/call` | 指定ツールを呼び出し OSC 送信 |
| `shutdown` | シャットダウン通知 |
| `exit` | サーバ終了 |

## LLM クライアントとの連携例（Claude Desktop / stdio）

`claude_desktop_config.json` に以下を追加します:

```json
{
  "mcpServers": {
    "osc": {
      "command": "/path/to/osc-cli/build/install/osc-cli/bin/osc-cli",
      "args": ["mcp", "--schema", "/path/to/schema.yaml", "--host", "127.0.0.1", "--port", "9000", "--webui", "--webui-port", "18082"]
    }
  }
}
```

## Streamable HTTP クライアント接続例

MCP Inspector や独自クライアントからは、次の URL に接続します。

```text
http://127.0.0.1:8081/mcp
```

起動例:

```bash
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --streamable-http-port 8081
```

## 依存関係

```
osc-adapter-mcp
├── osc-core
├── osc-adapter-webui
├── osc-transport-udp
└── kotlinx-coroutines-core
```
