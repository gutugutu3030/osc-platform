# kotlin-quickstart-loopback

OscRuntime を Kotlin から最短で使う最小サンプルです。  
UDP ループバック（同一ポートへの自己送受信）で動作を確認します。

## 目的

- `SchemaLoader` で `.kts` スキーマを読み込む
- `UdpOscTransport` + `OscRuntime` を組み合わせる
- `runtime.on()` でハンドラを登録し、`runtime.send()` で送信する
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

## 期待される出力

```
[Setup] スキーマ読み込み中: .../schema.kts
[Setup] スキーマ読み込み完了: 1 メッセージ定義
[Runtime] 起動完了 UDP 127.0.0.1:19000
[Send]    /light/color r=255, g=0, b=128
[Received] /light/color  namedArgs={r=255, g=0, b=128}
[Done]    1 件受信確認。ランタイム停止。
```

## ポート

| ポート | 用途 |
|--------|------|
| 19000  | UDP ループバック（送受信共用）|

## ファイル構成

```
schema.kts          ← /light/color スキーマ定義 (Kotlin DSL)
src/main/kotlin/
  com/example/
    Main.kt         ← エントリポイント
```
