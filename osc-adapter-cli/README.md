# osc-adapter-cli

CLI コマンド (`run` / `send` / `doc` / `list` / `validate` / `gen` / `version`) を実装するアダプタモジュールです。  
エンドユーザーは [`osc-cli`](../osc-cli/README.md) 経由で利用します。このモジュールを直接実行することは想定していません。

## 提供するコマンド

各サブコマンドは `--help` を受け付け、入力エラー時は `error: ...` と該当コマンドの usage を表示します。

### `run` — OSC サーバ起動

スキーマを読み込み、UDP で OSC パケットを待ち受けます。受信イベントを標準出力に表示します。

```
osc run [schemaPath] [--schema <path>] [--host <bindHost>] [--port <bindPort>] [--webui] [--webui-port <port>]
```

| オプション | デフォルト | 説明 |
|---|---|---|
| `--schema <path>` | カレントディレクトリ自動探索 | スキーマファイルパス |
| `--host <host>` | `0.0.0.0` | バインドホスト |
| `--port <port>` | `9000` | バインドポート |
| `--webui` | `false` | 受信イベントを監視する Web UI を併用起動 |
| `--webui-port <port>` | `8080` | Web UI の HTTP ポート |

例:

```bash
osc run schema.yaml --webui --webui-port 19080
```

### `send` — OSC メッセージ送信

スキーマを読み込み、指定先へ OSC メッセージを 1 件送信します。

```
osc send [messageRef] [--schema <path>] [--host <targetHost>] [--port <targetPort>] [--webui] [--webui-port <port>] --<arg> <value> ...
```

| オプション | 説明 |
|---|---|
| `<messageRef>` | `light.color` または `/light/color` |
| `--host <host>` | 送信先ホスト。通常送信では必須、`--webui` 時は既定値 `127.0.0.1` |
| `--port <port>` | 送信先ポート。通常送信では必須、`--webui` 時は既定値 `9000` |
| `--webui` | 即送信せず、送信フォーム付き Web UI を起動 |
| `--webui-port <port>` | Web UI の HTTP ポート |
| `--<argName> <value>` | 引数。JSON 形式 `{...}` / `[...]` も指定可能 |

例:

```bash
osc send light.color --host 127.0.0.1 --port 9000 --r 255 --g 0 --b 0
osc send mesh.points --host 127.0.0.1 --port 9000 --pointCount 2 --points '[{"x":1,"y":2,"z":3.0}]'
osc send light.color --host 127.0.0.1 --port 9000 --webui
```

`--webui` を付けると CLI は即送信しません。`messageRef` と引数は UI の初期値として使われます。

### `doc` — スキーマ仕様書生成

スキーマから HTML または Markdown の仕様書を生成します。

```
osc doc [schemaPath] [--schema <path>] [--out <path>] [--format html|markdown] [--title <text>]
```

| オプション | デフォルト | 説明 |
|---|---|---|
| `--out <path>` | `build/docs/osc-schema/index.html` | 出力先ファイルまたはディレクトリ |
| `--format <fmt>` | `html` | `html` または `markdown` |
| `--title <text>` | — | ドキュメントタイトル |

例:

```bash
osc doc --schema schema.kts --out build/docs/osc-schema/index.html
osc doc --schema schema.kts --format markdown --out docs/spec
```

### `list` — スキーマ一覧表示

解決したスキーマからメッセージ名、OSC パス、引数概要、bundle 一覧を表示します。

```
osc list [schemaPath] [--schema <path>]
```

例:

```bash
osc list schema.yaml
osc list --schema sample/kotlin-structured-bundle/schema.yaml
```

### `validate` — スキーマ検証

スキーマを読み込み、構文・参照整合性を検証します。正常時は件数サマリを表示して終了します。

```
osc validate [schemaPath] [--schema <path>]
```

例:

```bash
osc validate schema.kts
osc validate --schema sample/kotlin-structured-bundle/schema.yaml
```

### `gen` — Kotlin 型安全クラス生成

スキーマから Kotlin の型安全クラスを生成します。

```
osc gen [schemaPath] [--schema <path>] --package <packageName> [--lang kotlin] [--out <path>]
```

| オプション | デフォルト | 説明 |
|---|---|---|
| `--schema <path>` | カレントディレクトリ自動探索 | スキーマファイルパス |
| `--package <packageName>` | — | 生成コードの package 名（必須） |
| `--lang <lang>` | `kotlin` | 現在は `kotlin` のみ対応 |
| `--out <path>` | `build/generated/sources/osc` | 出力ルートディレクトリ |

例:

```bash
osc gen --schema schema.yaml --package com.example.osc.generated
osc gen schema.kts --package com.example.osc.generated --out build/generated/sources/osc
```

### `version` — バージョン表示

CLI バージョンを表示します。

```
osc version
osc --version
```

## 依存関係

```
osc-adapter-cli
├── osc-core
├── osc-adapter-webui
├── osc-transport-udp
└── kotlinx-coroutines-core
```
