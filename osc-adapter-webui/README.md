# osc-adapter-webui

`run` / `send` / `mcp` に埋め込まれる Web UI サーバと、スキーマエディタを提供するアダプタモジュールです。  
単独コマンド `osc webui` も互換目的で残っていますが、現在は deprecated です。

フロントエンドの JavaScript は [frontend/package.json](frontend/package.json) と [frontend/src](frontend/src) 配下の TypeScript を正として管理し、Gradle の `processResources` で esbuild バンドルを自動生成します。

フロントエンド UI の回帰確認は [frontend/test](frontend/test) 配下の Vitest + jsdom で行い、`./gradlew :osc-adapter-webui:check --no-daemon` から自動実行されます。

### ビルド前提条件（Node.js / npm）

このモジュール（および依存している `osc-cli` など）は、`processResources` 実行時にフロントエンドのビルドを行うため、Node.js と npm がインストールされていることを前提としています。

- 必須: Node.js と npm（推奨: Node.js 18 以降、npm 9 以降。CI ではこれらのバージョンで検証しています）
- `./gradlew :osc-adapter-webui:build --no-daemon` や `./gradlew build --no-daemon` の実行時に、`frontend` ディレクトリで `npm ci` が自動実行されます
- Node.js / npm がインストールされていない環境では、`esbuild` の実行に失敗し、Gradle ビルドがエラー終了します

Node.js / npm 周りでビルドが失敗した場合は、まず Node.js / npm がインストールされていることと、`frontend` ディレクトリで手動実行した `npm ci` / `npm test` が成功することを確認してください。
## 役割

- `osc run --webui`
  - 受信した OSC イベントをブラウザで監視する monitor mode
- `osc send --webui`
  - 送信先とメッセージをブラウザから操作する sender mode
- `osc mcp --webui`
  - MCP request / success / failure を見ながら OSC 送信テストを行う mcp mode
- `osc editor`
  - ブラウザ上で Kotlin DSL スキーマを記述し、リアルタイムでスキーマ構造を確認できるエディタ

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

## 画面イメージと基本的な使い方

### sender mode の画面例

![osc send --webui の画面例](https://github.com/user-attachments/assets/f20cb3dd-a09a-40b9-a4e6-e8263bf3fe15)

`osc send --webui` と `osc mcp --webui` は、左にメッセージ一覧、中央に入力フォーム、下部に Event Log を持つ共通レイアウトです。  
`osc run --webui` も同じ導線で確認できますが、monitor mode では送信機能だけが無効になります。

### `osc send --webui`

最も分かりやすい入口は sender mode です。

```bash
osc send light.color --schema sample/kotlin-quickstart-loopback/schema.kts --host 127.0.0.1 --port 9000 --webui --webui-port 19080 --r 255 --g 0 --b 128
```

1. コマンド実行後に表示される `Web UI: http://localhost:19080` をブラウザで開きます。
2. 左の **Messages** から送信したい message を選びます。
3. 中央のフォームで引数と `Target Host` / `Target Port` を確認・編集します。
4. **Send** を押して送信し、下部の **Event Log** で `sending` / `sent` / `failed` を確認します。

先頭の `light.color` のような message 指定と、`--r 255 --g 0 --b 128` のような CLI 引数は、画面の初期値としてそのまま反映されます。

### `osc run --webui`

```bash
osc run sample/kotlin-quickstart-loopback/schema.kts --webui --webui-port 19080
```

1. 起動ログに表示された URL をブラウザで開きます。
2. 左の **Messages** で、受信対象として定義されている message を確認します。
3. 別ターミナルや別プロセスから OSC を送ると、下部の **Event Log** に `received` イベントが追加されます。
4. monitor mode では送信フォームは確認用で、送信 API は無効です。

「受信側を `osc run --webui` で監視しながら、送信側を `osc send --webui` で操作する」という使い方をすると、ローカル検証がしやすくなります。

### `osc mcp --webui`

```bash
osc mcp sample/kotlin-mcp-stdio/schema.yaml --host 127.0.0.1 --port 9000 --webui --webui-port 18082
```

1. MCP クライアントを接続すると、`initialize` / `tools/list` / `tools/call` が **Event Log** に流れます。
2. `tools/call` の成功・失敗が色付きで表示されるため、MCP ツール呼び出しと OSC 送信結果を同じ画面で追えます。
3. 必要に応じて中央の送信フォームから手動で OSC を送り、MCP 経由の動作と見比べます。

## HTTP API

### `GET /`

Ktor CIO 上で生成した HTML を返します。

HTML 本体は Kotlin / kotlinx.html で生成し、CSS / JavaScript は classpath リソースの `/assets/webui/*` から配信します。

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

## Schema Editor (`osc editor`)

Kotlin DSL でスキーマを記述し、リアルタイムで構造を確認できる Web エディタです。

バックエンドは Ktor CIO を使用し、エディタ HTML は kotlinx.html で生成します。
CSS / JavaScript は `/assets/editor/editor.css` と `/assets/editor/editor.js` から配信します。

### 画面イメージ

![osc editor の補完ポップアップとプレビュー](https://github.com/user-attachments/assets/08fb89be-6ee1-42fb-b736-949be44ff228)

### 起動方法

```bash
osc editor                # デフォルトポート 3000 で起動
osc editor --port 8080    # ポートを指定して起動
```

### 基本的な使い方

1. `osc editor` を起動してブラウザで開きます。
2. 左の **Kotlin DSL Editor** に `oscSchema { ... }` を入力するか、**サンプルを挿入** で雛形を読み込みます。
3. 入力内容は自動評価され、右の **Schema Preview** に message / args / bundle がリアルタイム表示されます。
4. 補完候補を見たい位置で入力を始めるか `Ctrl+Space` を押すと、コンテキストに応じた候補が表示されます。
5. 候補は ↑↓ で選択し、Tab または Enter で確定します。構文エラーがある場合はプレビュー領域にエラーが表示されます。
6. `{` / `(` / `[` / `"` を入力すると対応する閉じ文字が自動挿入されます。
7. インデントが崩れた場合は **フォーマット** ボタンまたは `Ctrl+Shift+F` で整形できます。

### 機能

- 左ペイン: Kotlin DSL エディタ（`oscSchema { ... }` を直接入力）
- 右ペイン: スキーマプレビュー（メッセージ・引数・バンドルをリアルタイム表示）
- 入力時にデバウンスしてバックエンドで DSL を評価
- コンテキスト対応のコード補完（入力中に候補が自動表示、Ctrl+Space で明示起動）
  - スコープに応じた関数候補（`oscSchema` / `message` / `scalar` / `array` / `tuple` / `bundle` 等）
  - 括弧内では型定数（`INT` / `FLOAT` / `STRING` / `BOOL` / `BLOB`）・ロール（`LENGTH` / `VALUE`）・名前付きパラメータを候補表示
  - ↑↓ で選択、Tab/Enter で確定、Esc で閉じる
- **括弧ペア補完**: `{` / `(` / `[` / `"` を入力すると対応する閉じ文字が自動挿入され、カーソルがペアの内側に配置される
  - 閉じ文字がカーソル直後にある場合はスキップ（二重入力を防止）
  - 空ペア（`{}`, `()`, `[]`, `""`）を Backspace で一括削除
  - テキスト選択中に括弧キーを押すと選択範囲を囲む
- **自動インデント**: Enter キーで改行時に前の行のインデントを維持し、`{` 直後は一段深くする
- **コードフォーマット**: ブレースのネストに基づいてインデントを自動整形する
  - **フォーマット** ボタンまたは `Ctrl+Shift+F`（macOS: `Cmd+Shift+F`）で実行
- エラー時はエラーメッセージを表示
- サンプルテンプレートを挿入可能

### 技術構成

| 層 | 技術 |
|---|---|
| HTTP サーバー | Ktor CIO |
| monitor / sender / mcp UI | kotlinx.html + 外部 CSS / JavaScript（`src/main/resources/assets/webui/*`） |
| エディタ UI | kotlinx.html + 外部 CSS / JavaScript（`src/main/resources/assets/editor/*`） |
| DSL 評価 | `kotlin-scripting-jsr223` |

### HTTP API

#### `GET /`

エディタ HTML を kotlinx.html で生成して返します。

#### `POST /api/evaluate`

DSL テキストを評価してスキーマ JSON を返します。

リクエスト例:

```json
{
  "dsl": "oscSchema {\n    message(\"/light/color\") {\n        scalar(\"r\", INT)\n    }\n}"
}
```

成功例:

```json
{
  "success": true,
  "schema": {
    "messages": [
      {
        "path": "/light/color",
        "name": "light.color",
        "description": "",
        "args": [{ "name": "r", "kind": "scalar", "type": "int", "role": "value", "typeLabel": "r: int" }]
      }
    ],
    "bundles": []
  }
}
```

エラー例:

```json
{
  "success": false,
  "error": "error message"
}
```

### 制約

- スキーマの記述・可視化のみを行います（OSC 送受信は対象外）
- 将来の拡張で送受信機能を追加可能な設計です

## 依存関係

```
osc-adapter-webui
├── osc-core
└── kotlin-scripting-jsr223
```

## 制約

- UI は単一 HTML に埋め込んだ最小構成です
- 認証やアクセス制御はありません
- 開発・検証用途を主目的にしています
- `osc webui` は後方互換のため残していますが、新規利用は `--webui` を使ってください
