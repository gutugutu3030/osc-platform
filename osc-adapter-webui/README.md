# osc-adapter-webui

`run` / `send` / `mcp` に埋め込まれる Web UI サーバを提供するアダプタモジュールです。  
単独コマンド `osc webui` も互換目的で残っていますが、現在は deprecated です。

## 役割

- `osc run --webui`
  - 受信した OSC イベントをブラウザで監視する monitor mode
- `osc send --webui`
  - 送信先とメッセージをブラウザから操作する sender mode
- `osc mcp --webui`
  - MCP request / success / failure を見ながら OSC 送信テストを行う mcp mode

## 起動契約

このモジュールは通常、上位コマンドから次のように利用されます。

```bash
osc run schema.yaml --webui --webui-port 19080
osc send light.color --host 127.0.0.1 --port 9000 --webui
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --webui --webui-port 18082
```

補足:

- `send --webui` は即送信しません
- `messageRef` を指定した場合は UI の初期選択値になります
- `--webui-port` の既定値は `8080` です

## モード

### monitor

`osc run --webui` で起動します。

- Event Log で受信内容を確認できます
- スキーマ一覧は見られます
- `POST /api/send` は 403 を返し、送信機能は無効です

### sender

`osc send --webui` または deprecated な `osc webui` で起動します。

- メッセージ一覧から送信対象を選べます
- target host / port をブラウザから変更できます
- CLI 引数で渡した `messageRef` と引数を初期値として注入できます

### mcp

`osc mcp --webui` で起動します。

- MCP の `initialize` / `tools/list` / `tools/call` などの request を Event Log に表示します
- `tools/call` の success / failure を表示します
- sender mode と同じ送信フォームを使って OSC の手動送信テストも行えます

## HTTP API

### `GET /`

組み込み HTML を返します。

### `GET /api/schema`

読み込んだスキーマの message 一覧を JSON で返します。

レスポンス例:

```json
{
  "messages": [
    {
      "path": "/light/color",
      "name": "light.color",
      "description": "set RGB color",
      "args": [
        {
          "name": "r",
          "kind": "scalar",
          "type": "int",
          "typeLabel": "r: int",
          "inputType": "number",
          "placeholder": "0"
        }
      ]
    }
  ]
}
```

### `GET /api/events`

SSE でイベントを配信します。

主なイベント:

- `connected`
- `received`
- `send_started`
- `send_succeeded`
- `send_failed`
- `validation_error`
- `transport_error`
- `mcp_request`
- `mcp_success`
- `mcp_failure`

### `POST /api/send`

OSC 送信を要求します。monitor mode では無効です。

リクエスト例:

```json
{
  "messageRef": "light.color",
  "host": "127.0.0.1",
  "port": 9000,
  "args": {
    "r": 255,
    "g": 0,
    "b": 0
  }
}
```

成功例:

```json
{
  "success": true
}
```

monitor mode での失敗例:

```json
{
  "success": false,
  "error": "send is disabled in monitor mode"
}
```

## 依存関係

```
osc-adapter-webui
└── osc-core
```

## 制約

- UI は単一 HTML に埋め込んだ最小構成です
- 認証やアクセス制御はありません
- 開発・検証用途を主目的にしています
- `osc webui` は後方互換のため残していますが、新規利用は `--webui` を使ってください
