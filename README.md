# osc-platform

OSC (Open Sound Control) を扱うための、スキーマ駆動開発プラットフォームです。

## 破壊的変更 — v0.3.0 (Breaking Changes)

### `runtime.on(String, handler)` の廃止

v0.3.0 から `OscRuntime.on(String, handler)` オーバーロードは **削除** されました。  
受信ハンドラを登録する際は `OscMessageSpec` を渡してください。

**Before (v0.2.x まで — コンパイル不可)**

```kotlin
// NG: String オーバーロードは廃止済み
runtime.on("/light/color") { event ->
    println(event.namedArgs)
}
```

**After (v0.3.0 以降)**

```kotlin
val lightColorSpec = schema.resolveMessage("light.color")
    ?: error("スキーマに '/light/color' が見つかりません")

runtime.on(lightColorSpec) { event ->
    println(event.namedArgs)
}
```

> **移行手順**: `on(path: String, ...)` の呼び出し箇所を  
> `schema.resolveMessage("<ref>")` で `OscMessageSpec` を取得してから  
> `on(spec, ...)` に差し替えてください。

## Presentation

OSC → schema の価値と本プロジェクトのアーキテクチャを解説するスライドを公開しています。

- 🌐 **Web Slides**: [https://gutugutu3030.github.io/osc-platform/](https://gutugutu3030.github.io/osc-platform/)
- 📄 **PDF**: [https://gutugutu3030.github.io/osc-platform/slides.pdf](https://gutugutu3030.github.io/osc-platform/slides.pdf)

本プロジェクトは `Small Core + Adapter` を前提に設計されており、
Core を最小に保ちながら CLI / MCP / 将来の REST / Web UI へ拡張しやすい構成を目指します。

## 目的

- スキーマレスになりがちな OSC を、明示スキーマで扱えるようにする
- スキーマ定義から実行系・ツール系を自動的に接続できる基盤を作る
- LLM は「1つの Adapter」として扱い、Core を汚染しない

## 設計方針

- Core の責務は以下のみ
  - OSC Schema
  - OSC Runtime
  - OSC Transport (interface)
- CLI / MCP / REST / Web UI はすべて Adapter として分離
- 依存方向は `adapter -> runtime(core) -> transport`
- 非同期処理は coroutine ベース

## モジュール構成

| モジュール | 概要 | README |
|---|---|---|
| `osc-core` | スキーマモデル・Kotlin DSL・YAML/KTS ローダ・Runtime・Transport インターフェース | [osc-core/README.md](osc-core/README.md) |
| `osc-transport-udp` | UDP Transport 実装・OSC Codec | [osc-transport-udp/README.md](osc-transport-udp/README.md) |
| `osc-adapter-cli` | `run` / `send` / `doc` / `gen` コマンド実装 | [osc-adapter-cli/README.md](osc-adapter-cli/README.md) |
| `osc-adapter-mcp` | スキーマ → MCP tools 自動生成・MCP stdio サーバ | [osc-adapter-mcp/README.md](osc-adapter-mcp/README.md) |
| `osc-cli` | 実行エントリポイント（全コマンドを束ねる） | [osc-cli/README.md](osc-cli/README.md) |
| `osc-codegen` | OscSchema → Kotlin data class コード生成ライブラリ | — |
| `osc-gradle-plugin` | `com.oscplatform.schema-codegen` Gradle プラグイン | — |
| `sample/` | 各機能のサンプルプロジェクト集 | [sample/README.md](sample/README.md) |

## 技術スタック

- Kotlin `2.1.20`
- JVM Toolchain `21`
- Coroutines: `kotlinx-coroutines-core:1.10.2`
- YAML/JSON: Jackson
  - `jackson-databind:2.18.3`
  - `jackson-module-kotlin:2.18.3`
  - `jackson-dataformat-yaml:2.18.3`
- KTS loader: `kotlin-scripting-jsr223:2.1.20`
- Build: Gradle Kotlin DSL

## スキーマ定義

### 1. Kotlin DSL (最優先)

`schema.kts` 例:

```kotlin
oscSchema {
    message("/light/color") {
        description("set RGB color")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    message("/mesh/points") {
        description("set xyz points")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }
}
```

補足:

- `arg(name, type)` は `scalar(name, type)` のショートハンドとして引き続き利用可能

### 2. YAML (補助フォーマット)

`schema.yaml` 例:

```yaml
messages:
  - path: /light/color
    description: set RGB color
    args:
      - name: r
        kind: scalar
        type: int
      - name: g
        kind: scalar
        type: int
      - name: b
        kind: scalar
        type: int
  - path: /mesh/points
    description: set xyz points
    args:
      - name: pointCount
        kind: scalar
        type: int
        role: length
      - name: points
        kind: array
        lengthFrom: pointCount
        items:
          kind: tuple
          fields:
            - name: x
              type: int
            - name: y
              type: int
            - name: z
              type: float
```

### 引数ノード

- `scalar`
  - 通常値
  - `role: length` を付けると配列長フィールドとして利用可能
- `array`
  - `length` (固定長) または `lengthFrom` (他フィールド参照) を指定
  - `items.kind` は `scalar` または `tuple`

### 現在の対応型

| トークン               | OSC type tag | Kotlin 型                         |
|--------------------|--------------|----------------------------------|
| `int` / `integer`  | `i`          | `Int`                            |
| `float`            | `f`          | `Float`                          |
| `string` / `str`   | `s`          | `String`                         |
| `bool` / `boolean` | `T` / `F`    | `Boolean`                        |
| `blob` / `bytes`   | `b`          | `ByteArray` (MCP 経由は base64 文字列) |

## 命名ルール

- メッセージ名 (CLI向け)
  - `/light/color` -> `light.color`
- MCPツール名
  - `/light/color` -> `set_light_color`

補足:

- schema に `name` を明示すれば上書き可能
- バンドルツール名: `OscBundleSpec.name` → `bundle_<name>`

## スキーマ探索ルール (`--schema` 未指定時)

`run` / `send` / `doc` / `mcp` すべて同じルールです。

1. カレントディレクトリで `schema.kts` を探す
2. なければ `schema.yaml` を探す
3. なければ `schema.yml` を探す
4. なければ `schema*.(kts|yaml|yml)` の先頭を使用
5. それでも見つからなければエラー

補足:

- `schema.kts` と `schema.yaml` (または `schema.yml`) が同時に存在する場合は warning を出し、`schema.kts` を優先

## クイックスタート

### 前提

- JDK 21
- Gradle 9.x

### ビルド

```bash
gradle build :osc-cli:installDist --no-daemon
```

### 実行ファイル

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli
```

## CLI 使い方

### 1) サーバ起動

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli run schema.yaml
```

または

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli run schema.kts
```

オプション:

- `--schema <path>`
- `--host <bindHost>` (default: `0.0.0.0`)
- `--port <bindPort>` (default: `9000`)

例:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli run --host 0.0.0.0 --port 9010
```

### 2) メッセージ送信

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli send light.color --host 127.0.0.1 --port 9000 --r 255 --g 0 --b 0
```

構造化引数の送信例:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli send mesh.points --host 127.0.0.1 --port 9000 --pointCount 2 --points '[{"x":1,"y":2,"z":3.0},{"x":4,"y":5,"z":6.5}]'
```

ポイント:

- `messageRef` は `light.color` 形式でも `/light/color` 形式でも指定可能
- `send` は送信先必須
  - `--host` 未指定: エラー
  - `--port` 未指定: エラー
- `--arg` の値が `{...}` / `[...]` の場合はJSONとして解釈
  - array/tuple 引数は JSON 形式で渡す

### 3) スキーマ仕様書 (HTML / Markdown) 生成

人間向けの HTML 仕様書を生成できます。

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli doc --schema schema.kts --out build/docs/osc-schema/index.html
```

Markdown 出力:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli doc --schema schema.kts --format markdown --out build/docs/osc-schema/index.md
```

`--out` にディレクトリを指定した場合は `index.html` を生成します。

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli doc --schema schema.yaml --out docs/spec
```

`--format markdown` かつ `--out` がディレクトリの場合は `index.md` を生成します。

オプション:

- `--schema <path>`
- `--out <path>` (default: `build/docs/osc-schema/index.html` / `--format markdown` 時は `build/docs/osc-schema/index.md`)
- `--format <html|markdown>` (default: `html`)
- `--title <text>`

出力ファイルは静的ファイルなので、公開先は任意です（ローカル閲覧、社内サーバ、GitHub Pages など）。

### 4) Kotlin 型安全クラスの生成

`osc gen` コマンドで `OscSchema` から Kotlin の data class を生成できます。

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli gen \
    --schema schema.yaml \
    --package com.example.osc.generated \
    --lang kotlin \
    --out build/generated/sources/osc
```

オプション:

- `--schema <path>` (default: CWD の `schema.kts` → `schema.yaml` 順で探索)
- `--package <packageName>` **必須**
- `--lang <kotlin>` (default: `kotlin`)
- `--out <path>` (default: `build/generated/sources/osc`)

生成例 (`/light/color` メッセージ → `LightColor.kt`):

```kotlin
data class LightColor(
    val r: Int,
    val g: Int,
    val b: Int,
) {
    fun toNamedArgs(): Map<String, Any?> = mapOf("r" to r, "g" to g, "b" to b)

    companion object {
        const val PATH: String = "/light/color"
        const val NAME: String = "light.color"

        fun fromNamedArgs(args: Map<String, Any?>): LightColor =
            LightColor(r = args["r"] as Int, g = args["g"] as Int, b = args["b"] as Int)
    }
}
```

## Gradle プラグイン (osc-gradle-plugin)

外部 Gradle プロジェクトで `generateOscSources` タスクを自動実行できます。

### 設定方法

**`settings.gradle.kts`** — `pluginManagement` でプラグインを解決:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
    }
}
```

ローカル開発時はコンポジットビルドで解決できます:

```kotlin
pluginManagement {
    includeBuild("path/to/osc-platform") // osc-gradle-plugin を含む
}
```

**`build.gradle.kts`**:

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("com.oscplatform.schema-codegen") version "0.4.0"
}

dependencies {
    implementation("com.oscplatform:osc-core:0.4.0")
    implementation("com.oscplatform:osc-transport-udp:0.4.0")
}

oscSchemaCodegen {
    schema.set(layout.projectDirectory.file("schema.yaml"))
    packageName.set("com.example.osc.generated")
    language.set("kotlin")   // default: "kotlin"
    // outputDir は省略可 (default: build/generated/sources/osc/main/kotlin)
}
```

設定後は通常の手書きクラスと同様に IDE 補完が効きます。`compileKotlin` が実行される前に
`generateOscSources` が自動で走るため、追加操作は不要です。

### 生成規則

| スキーマ要素 | 生成物 |
|---|---|
| `scalar(name, type)` | コンストラクタパラメータ `val name: Type` |
| `scalar(name, INT, role=LENGTH)` | computed プロパティ `val name: Int get() = arrayField.size` |
| `array(name, ...) { scalar(type) }` | `val name: List<Type>` |
| `array(name, ...) { tuple { ... } }` | `val name: List<NestedClass>` + nested data class |
| PATH / NAME 定数 | `companion object` 内の `const val` |
| `toNamedArgs()` | `Map<String, Any?>` への変換 |
| `fromNamedArgs()` | `Map<String, Any?>` からの復元 |

## MCP Adapter 使い方


stdio で起動:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli mcp schema.yaml --host 127.0.0.1 --port 9000
```

または `--schema` 省略でカレント探索:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli mcp --host 127.0.0.1 --port 9000
```

対応メソッド:

- `initialize`
- `tools/list`
- `tools/call`
- `shutdown`
- `exit`

`tools/list` では schema から自動生成された tool が返ります。

例:

- OSC path `/light/color`
- MCP tool `set_light_color`

## Runtime の挙動

- 送信時
  - `messageRef` を schema で解決
  - 引数名の整合性チェック
  - schemaノードに従って平坦化（scalar/array/tuple）
  - 型変換とバリデーション
  - `lengthFrom` 参照時は長さ整合性を検証
  - Transport へ送信
- 受信時
  - path を schema で解決
  - フラット引数列を schemaノードに従って復元
  - 長さ制約（`length` / `lengthFrom`）と型を検証
  - `Received` または `ValidationError` を event stream に流す
  - 登録ハンドラに dispatch

## Transport と Bundle 対応

現在の実運用は OSC Message 中心ですが、Bundle 拡張が可能な設計です。

- `OscPacket` は `OscMessagePacket` / `OscBundlePacket` を定義済み
- Runtime は Bundle を再帰処理可能
- UDP codec は Bundle encode/decode 構造を実装済み

## 実装上の制約 (現フェーズ)

- OSCプリミティブ型は `int/float/string/bool/blob` をサポート
- 構造化引数（array/tuple）は schema で宣言し、ワイヤ上は平坦化して送受信
- `lengthFrom` は前方の `role: length` な `int` scalar を参照する必要がある
- Bundleは構造対応済みだが、高度な時刻同期ユースケースは未整備
- MCPは `tools` 中心で、resources/prompts は未対応
- BOOL の文字列変換は `true/false/1/0/yes/no` のみ受け付け、それ以外はエラー

## 開発コマンド

```bash
gradle build --no-daemon
gradle :osc-cli:run --args='help'
```

## テストコマンド

```bash
./gradlew :osc-core:test
./gradlew :osc-adapter-cli:test
./gradlew :osc-adapter-mcp:test
./gradlew build --no-daemon
```

テスト対象の要点:

- `osc-core`
  - `OscArgNode` バリデーション
  - YAMLローダ
  - DSL
  - Runtimeのstructured args処理
- `osc-adapter-cli`
  - 動的JSON引数パーサ
  - CLIコマンド分岐
- `osc-adapter-mcp`
  - structured `inputSchema` 生成
  - JSON値変換

ローカル検証例:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli run --port 9010
./osc-cli/build/install/osc-cli/bin/osc-cli send light.color --host 127.0.0.1 --port 9010 --r 100 --g 20 --b 5
./osc-cli/build/install/osc-cli/bin/osc-cli send mesh.points --host 127.0.0.1 --port 9010 --pointCount 2 --points '[{"x":1,"y":2,"z":3.0},{"x":4,"y":5,"z":6.5}]'
```

## ディレクトリ概要

```text
osc-platform/
  osc-core/
  osc-transport-udp/
  osc-adapter-cli/
  osc-adapter-mcp/
  osc-cli/
  schema.kts
  schema.yaml
```
