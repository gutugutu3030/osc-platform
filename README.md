# osc-platform

OSC (Open Sound Control) を扱うための、スキーマ駆動開発プラットフォームです。

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
        arg("r", INT)
        arg("g", INT)
        arg("b", INT)
    }
}
```

### 2. YAML (補助フォーマット)

`schema.yaml` 例:

```yaml
messages:
  - path: /light/color
    description: set RGB color
    args:
      - name: r
        type: int
      - name: g
        type: int
      - name: b
        type: int
```

### 現在の対応型

- `int`
- `float`
- `string`

`bool` は次フェーズで対応予定です。

## 命名ルール

- メッセージ名 (CLI向け)
  - `/light/color` -> `light.color`
- MCPツール名
  - `/light/color` -> `set_light_color`

補足:

- schema に `name` を明示すれば上書き可能

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

ポイント:

- `messageRef` は `light.color` 形式でも `/light/color` 形式でも指定可能
- `send` は送信先必須
  - `--host` 未指定: エラー
  - `--port` 未指定: エラー

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
  - 型変換とバリデーション
  - Transport へ送信
- 受信時
  - path と引数数/型を schema で検証
  - `Received` または `ValidationError` を event stream に流す
  - 登録ハンドラに dispatch

## Transport と Bundle 対応

現在の実運用は OSC Message 中心ですが、Bundle 拡張が可能な設計です。

- `OscPacket` は `OscMessagePacket` / `OscBundlePacket` を定義済み
- Runtime は Bundle を再帰処理可能
- UDP codec は Bundle encode/decode 構造を実装済み

## 実装上の制約 (現フェーズ)

- OSC型は `int/float/string` のみ
- Bundleは構造対応済みだが、高度な時刻同期ユースケースは未整備
- MCPは `tools` 中心で、resources/prompts は未対応

## 開発コマンド

```bash
gradle build --no-daemon
gradle :osc-cli:run --args='help'
```

ローカル検証例:

```bash
./osc-cli/build/install/osc-cli/bin/osc-cli run --port 9010
./osc-cli/build/install/osc-cli/bin/osc-cli send light.color --host 127.0.0.1 --port 9010 --r 100 --g 20 --b 5
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
- テスト拡充 (codec/runtime/adapter)
