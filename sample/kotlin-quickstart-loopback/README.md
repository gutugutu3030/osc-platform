# kotlin-quickstart-loopback

OscRuntime を Kotlin から最短で使う最小サンプルです。  
UDP ループバック（同一ポートへの自己送受信）で動作を確認します。

**v0.4.0 以降:** `osc-gradle-plugin` による Kotlin クラス自動生成に対応しました。  
`schema.yaml` から `LightColor` クラスが生成され、型安全な送受信が可能です。

## 目的

- `SchemaLoader` で `.kts` スキーマを読み込む
- `UdpOscTransport` + `OscRuntime` を組み合わせる
- **生成型 `LightColor` を使って** 型安全に `runtime.send()` / `fromNamedArgs()` する
- 1 件受信後に `stop()` する基本パターンを習得する

## 前提

- JDK 21
- 親リポジトリ（`osc-platform`）のルートクローン済み

## 実行手順

リポジトリルートから：

```bash
./gradlew -p sample/kotlin-quickstart-loopback run
```

または sample ディレクトリ内から：

```bash
cd sample/kotlin-quickstart-loopback
../../gradlew run
```

`run` 実行前に `generateOscSources` タスクが自動で走り、
`build/generated/sources/osc/main/kotlin/com/example/osc/generated/LightColor.kt`
が生成されます。

## 期待される出力

```
[Setup] スキーマ読み込み中: .../schema.kts
[Setup] スキーマ読み込み完了: 1 メッセージ定義
[Runtime] 起動完了 UDP 127.0.0.1:19000
[Send]    /light/color r=255, g=0, b=128
[Received] /light/color  r=255, g=0, b=128
[Done]    1 件受信確認。ランタイム停止。
```

## ポート

| ポート | 用途 |
|--------|------|
| 19000  | UDP ループバック（送受信共用）|

## ファイル構成

```
schema.kts          ← /light/color スキーマ定義 (Kotlin DSL, ランタイム用)
schema.yaml         ← /light/color スキーマ定義 (YAML, コード生成用)
src/main/kotlin/
  com/example/
    Main.kt         ← エントリポイント (LightColor 生成型を使用)
build/generated/sources/osc/main/kotlin/
  com/example/osc/generated/
    LightColor.kt   ← generateOscSources タスクが自動生成 (コミット不要)
```

## 移行ガイド: Map ベース API → 生成型

### Before (v0.3 以前)

```kotlin
// 文字列キーの typo がコンパイル時に検出されない
runtime.send(
    messageRef = "light.color",
    rawArgs = mapOf("r" to 255, "g" to 0, "b" to 128),
    target = OscTarget("127.0.0.1", 19000),
)

runtime.on(lightColorSpec) { event ->
    val r = event.namedArgs["r"] as Int  // キャストが必要
    println("r=$r")
}
```

### After (v0.4 以降 — 生成型使用)

```kotlin
// コンパイル時に型チェックが効く
runtime.send(
    companion = LightColor,
    msg = LightColor(r = 255, g = 0, b = 128),
    target = OscTarget("127.0.0.1", 19000),
)

runtime.on(LightColor) { color ->
    println("r=${color.r}")          // プロパティ補完が効く
}
```

### 移行手順

1. `schema.yaml` (または `schema.kts`) でスキーマ定義済みであること
2. `settings.gradle.kts` に `pluginManagement { includeBuild("../..") }` を追加
3. `build.gradle.kts` に `id("com.oscplatform.schema-codegen")` と `oscSchemaCodegen { ... }` を追加
4. `generateOscSources` タスク実行（または `run` / `compileKotlin` 経由で自動実行）
5. 生成クラスを import して Map ベース API を置き換える

