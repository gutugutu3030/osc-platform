# kotlin-structured-bundle

構造化引数（`lengthFrom` + タプル配列）と `sendBundle` を体験するサンプルです。

## 目的

- `kind: array` + `kind: tuple` フィールドを持つメッセージスキーマの定義方法を習得する
- `pointCount`（LENGTH ロール）を省略し、配列長から自動導出させる
- `sendBundle` で複数メッセージをアトミックに Bundle 送信する
- `runtime.events` を購読して `Received` / `ValidationError` イベントをログ出力する

## 前提

- JDK 21

## 実行手順

```bash
./gradlew -p sample/kotlin-structured-bundle run
```

## 期待される出力

```
[Setup] スキーマ: 2 メッセージ, 1 バンドル
[Runtime] 起動完了 UDP 127.0.0.1:19010

--- Step 1: /mesh/points 送信（pointCount 自動導出） ---
  ※ rawArgs に pointCount を渡さず points のリストだけ渡す
[Event.Received]         path=/mesh/points  namedArgs={pointCount=3, points=[...]}

--- Step 2: bundle (set_scene) を sendBundle で送信 ---
[Event.Received]         path=/mesh/points  namedArgs={pointCount=1, points=[...]}
[Event.Received]         path=/device/flag  namedArgs={enabled=true}

--- Step 3: 不正パスのパケットを直接送信（ValidationError を確認） ---
  ※ スキーマに存在しない '/invalid/path' を生パケットで送信
[Event.ValidationError]  addr=/invalid/path  reason=Unknown OSC path

[Done] デモ完了。ランタイム停止。
```

> **Note:** `Step 3` の ValidationError は、スキーマにない OSC パスが届いたときに `OscRuntime` が発行するイベントです。

## ポート

| ポート | 用途 |
|--------|------|
| 19010  | UDP ループバック（送受信共用）|

## スキーマ設計のポイント

```yaml
# pointCount に role: length を付けると、
# runtime.send() 時に points の要素数から自動導出される。
- name: pointCount
  kind: scalar
  type: int
  role: length
- name: points
  kind: array
  lengthFrom: pointCount   # ← この指定で LENGTH フィールドと紐付く
  items:
    kind: tuple
    fields:
      - { name: x, type: int }
      - { name: y, type: int }
      - { name: z, type: float }
```

## ファイル構成

```
schema.yaml          ← /mesh/points, /device/flag, bundle set_scene
src/main/kotlin/
  com/example/
    Main.kt          ← デモエントリポイント
```
