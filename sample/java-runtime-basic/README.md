# java-runtime-basic

Java ユーザー向けの実用的な導入サンプルです。  
Kotlin ラッパー（`OscBlockingClient`）を経由して `OscRuntime` を Java から操作します。

## 目的

- Java から Kotlin の suspend 関数を含むライブラリを使う現実的な方法を示す
- `OscBlockingClient`（Kotlin で実装）が `runBlocking` で suspend 呼び出しをブロッキング化し、
  Java から普通のメソッド呼び出しとして使えるようにするパターンを習得する

## Java から Kotlin ラッパー経由が現実的な理由

`OscRuntime` の主要 API（`start()`, `send()`, `stop()`）はすべて Kotlin の `suspend` 関数です。  
Java はコルーチンを直接扱えないため、`CompletableFuture` などへの変換も必要です。  
代わりに **Kotlin で `runBlocking {}` を使う薄いラッパーを作る**ことで、  
Java 側は suspend を意識せずに同期的なメソッドとして呼び出せます。  
この方式が Java ユーザーにとって最も導入コストが低く、実用的なアプローチです。

## 前提

- JDK 21

## 実行手順

```bash
./gradlew -p sample/java-runtime-basic run
```

## 期待される出力

```
[OscBlockingClient] 起動完了  target=127.0.0.1:19020
[OscBlockingClient] 送信: /device/flag  enabled=true
[OscBlockingClient] 送信: /device/flag  enabled=false
[OscBlockingClient] 停止
[App] 正常終了
```

> **Note:** 送信先 `127.0.0.1:19020` には実際の受信側がいないため，UDP パケットは破棄されます。  
> これは UDP の性質（コネクションレス）によるもので，エラーにはなりません。

## ポート

| ポート | 用途 |
|--------|------|
| 0 (OS 割当) | OscBlockingClient の受信バインドポート（エフェメラル）|
| 19020  | 送信先ターゲットポート |

## ファイル構成

```
schema.yaml                         ← /device/flag スキーマ定義
src/
  main/
    kotlin/com/example/
      OscBlockingClient.kt          ← runBlocking ラッパー (Kotlin)
    java/com/example/
      App.java                      ← Java エントリポイント
```
