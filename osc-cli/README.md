# osc-cli

osc-platform の実行エントリポイントです。`osc-adapter-cli` と `osc-adapter-mcp` を束ねて、統一された `osc` コマンドとして提供します。

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

## コマンド一覧

```
osc run   [schemaPath] [--schema <path>] [--host <host>] [--port <port>]
osc send  <messageRef> [--schema <path>] --host <host> --port <port> --<arg> <value> ...
osc doc   [schemaPath] [--schema <path>] [--out <path>] [--format html|markdown] [--title <text>]
osc mcp   [schemaPath] [--schema <path>] --host <host> --port <port>
osc help
```

### `run` — OSC サーバ起動

```bash
osc run schema.yaml
osc run --host 0.0.0.0 --port 9010
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#run--osc-サーバ起動)

### `send` — OSC メッセージ送信

```bash
osc send light.color --host 127.0.0.1 --port 9000 --r 255 --g 0 --b 0
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#send--osc-メッセージ送信)

### `doc` — スキーマ仕様書生成

```bash
osc doc --schema schema.kts --out build/docs/osc-schema/index.html
```

詳細: [`osc-adapter-cli`](../osc-adapter-cli/README.md#doc--スキーマ仕様書生成)

### `mcp` — MCP stdio サーバ起動

```bash
osc mcp schema.yaml --host 127.0.0.1 --port 9000
```

詳細: [`osc-adapter-mcp`](../osc-adapter-mcp/README.md)

## 依存関係

```
osc-cli
├── osc-adapter-cli
└── osc-adapter-mcp
```
