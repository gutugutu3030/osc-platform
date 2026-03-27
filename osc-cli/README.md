# osc-cli

osc-platform の実行エントリポイントです。`osc-adapter-cli` と `osc-adapter-mcp` を束ねて、統一された `osc` コマンドとして提供します。

補足:

- help や usage では論理コマンド名として `osc` と表記します
- `installDist` で生成される実体のバイナリ名は `osc-cli` です

## ビルド

```bash
# リポジトリルートから実行
./gradlew :osc-cli:installDist
```

実行ファイルの生成先:

```
osc-cli/build/install/osc-cli/bin/osc-cli
```

単一の executable jar を作る場合:

```bash
# リポジトリルートから実行
./gradlew :osc-cli:shadowJar
```

出力先:

```
osc-cli/build/libs/osc-cli-<version>.jar
```

実行例:

```bash
java -jar osc-cli/build/libs/osc-cli-<version>.jar help
java -jar osc-cli/build/libs/osc-cli-<version>.jar doc sample/kotlin-quickstart-loopback/schema.kts --format markdown --out build/osc-schema.md
```

補足:

- `shadowJar` は依存ライブラリ込みの fat jar を生成します
- `META-INF/services` も結合するため、`.kts` スキーマ読込で必要な `kotlin-scripting-jsr223` も `java -jar` で利用できます

## GitHub Releases

GitHub Release では次の 2 種類の配布物を公開します。

- osc-cli-<version>.jar
  依存込みの fat jar です。`java -jar` でそのまま実行できます。
- osc-cli-<version>.zip
  application plugin の配布物です。展開後に `bin/osc-cli` と `lib/` を含みます。

zip 配布物の利用例:

```bash
unzip osc-cli-<version>.zip
./osc-cli-<version>/bin/osc-cli help
```

macOS でダウンロードした zip や展開後の実行ファイルに quarantine 属性が付いて起動できない場合は、必要に応じて次を実行してください。

```bash
sudo xattr -dr com.apple.quarantine "path"
```

例:

```bash
sudo xattr -dr com.apple.quarantine "./osc-cli-<version>"
```

## コマンド一覧

```
osc run   [schemaPath] [--schema <path>] [--host <host>] [--port <port>] [--webui] [--webui-port <port>]
osc send  [messageRef] [--schema <path>] [--host <host>] [--port <port>] [--webui] [--webui-port <port>] --<arg> <value> ...
osc doc   [schemaPath] [--schema <path>] [--out <path>] [--format html|markdown] [--title <text>]
osc list  [schemaPath] [--schema <path>]
osc validate [schemaPath] [--schema <path>]
osc gen   [schemaPath] [--schema <path>] --package <packageName> [--lang kotlin] [--out <path>]
osc mcp   [schemaPath] [--schema <path>] --host <host> --port <port> [--streamable-http-port <port>] [--listen-host <host>] [--webui] [--webui-port <port>]
osc version
osc --version
osc help
```

各サブコマンドは `--help` を受け付けます。

### `run` — OSC サーバ起動

```bash
osc run schema.yaml
osc run --host 0.0.0.0 --port 9010
osc run --webui --webui-port 19080
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#run--osc-サーバ起動)

### `send` — OSC メッセージ送信

```bash
osc send light.color --host 127.0.0.1 --port 9000 --r 255 --g 0 --b 0
osc send light.color --host 127.0.0.1 --port 9000 --webui
```

`--webui` を付けた場合、CLI は即送信せずブラウザ UI を起動します。`messageRef` を指定すると UI の初期選択値になります。

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#send--osc-メッセージ送信)

### `doc` — スキーマ仕様書生成

```bash
osc doc --schema schema.kts --out build/docs/osc-schema/index.html
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#doc--スキーマ仕様書生成)

### `list` — スキーマ一覧表示

```bash
osc list schema.yaml
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#list--スキーマ一覧表示)

### `validate` — スキーマ検証

```bash
osc validate schema.yaml
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#validate--スキーマ検証)

### `gen` — Kotlin 型安全クラス生成

```bash
osc gen schema.yaml --package com.example.osc.generated --out build/generated/sources/osc
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#gen--kotlin-型安全クラス生成)

### `mcp` — MCP stdio / Streamable HTTP サーバ起動

```bash
osc mcp schema.yaml --host 127.0.0.1 --port 9000
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --streamable-http-port 8081
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --streamable-http-port 8081 --listen-host 0.0.0.0
osc mcp schema.yaml --host 127.0.0.1 --port 9000 --webui --webui-port 18082
```

補足:

- `--streamable-http-port` を付けると `/mcp` エンドポイントで Streamable HTTP を待ち受けます
- `--listen-host` の既定値は `127.0.0.1` です
- `--host` / `--port` は OSC の送信先であり、HTTP の待受先ではありません

詳細: [`osc-adapter-mcp`](../osc-adapter-mcp/README.md)

### `version` — バージョン表示

```bash
osc version
osc --version
```

## 依存関係

```
osc-cli
├── osc-adapter-cli
└── osc-adapter-mcp
```
