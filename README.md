# osc-platform

OSC (Open Sound Control) を扱うための、スキーマ駆動開発プラットフォームです。

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

- `osc-core`
  - スキーマモデル
  - Kotlin DSL
  - YAML / KTS ローダ
  - Runtime (validation, dispatch, send/receive)
  - Transport インターフェース
- `osc-transport-udp`
  - UDP Transport 実装
  - OSC Codec (Message中心 + Bundle対応可能な構造)
- `osc-adapter-cli`
  - `run` / `send` コマンド
- `osc-adapter-mcp`
  - schema -> MCP tools 自動生成
  - MCP stdio サーバ
- `osc-cli`
  - 実行エントリポイント

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

| トークン | OSC type tag | Kotlin 型 |
|---------|-------------|----------|
| `int` / `integer` | `i` | `Int` |
| `float` | `f` | `Float` |
| `string` / `str` | `s` | `String` |
| `bool` / `boolean` | `T` / `F` | `Boolean` |
| `blob` / `bytes` | `b` | `ByteArray` (MCP 経由は base64 文字列) |

## 命名ルール

- メッセージ名 (CLI向け)
  - `/light/color` -> `light.color`
- MCPツール名
  - `/light/color` -> `set_light_color`

補足:

- schema に `name` を明示すれば上書き可能
- バンドルツール名: `OscBundleSpec.name` → `bundle_<name>`

## スキーマ探索ルール (`--schema` 未指定時)

`run` / `send` / `mcp` すべて同じルールです。

1. カレントディレクトリで `schema.kts` を探す
2. なければ `schema.yaml` を探す
3. なければ `schema.yml` を探す
4. なければ `schema*.(kts|yaml|yml)` の先頭を使用
5. それでも見つからなければエラー

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

## ロードマップ

- `bool` / 追加OSC型サポート
- Bundle運用機能の強化 (timetag戦略)
- REST adapter
- Web UI adapter
- 統合テスト拡充 (MCP stdio `tools/call` / CLI `send` / codec境界ケース)
