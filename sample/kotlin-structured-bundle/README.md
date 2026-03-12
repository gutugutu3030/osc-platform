# kotlin-structured-bundle

構造化引数（`lengthFrom` + タプル配列）と `sendBundle` を体験するサンプルです。

`osc-gradle-plugin` によるコード生成を利用し、型安全な Bundle ファサードクラス（`SetSceneBundle`）で複数メッセージをアトミックに送信します。

## 目的

- `kind: array` + `kind: tuple` フィールドを持つメッセージスキーマの定義方法を習得する
- `pointCount`（LENGTH ロール）を省略し、配列長から自動導出させる
- 生成 Bundle クラス（`SetSceneBundle`）で複数メッセージを型安全にアトミック送信する
- `runtime.events` を購読して `Received` / `ValidationError` イベントをログ出力する

## 前提

- JDK 21

## 実行手順

```bash
gradle -p sample/kotlin-structured-bundle run
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
build.gradle.kts     ← com.oscplatform.schema-codegen プラグイン設定
src/main/kotlin/
  com/example/
    Main.kt          ← デモエントリポイント
build/generated/sources/osc/main/kotlin/
  com/example/osc/generated/
    MeshPoints.kt      ← 自動生成（コミット不要）
    DeviceFlag.kt      ← 自動生成（コミット不要）
    SetSceneBundle.kt  ← 自動生成（コミット不要）
```

## コード生成の仕組み

`schema.yaml` の `messages`・`bundles` 両方からクラスが生成されます。

```
generateOscSources  ← schema.yaml を入力に自動実行
        ┃
        ┃→ MeshPoints.kt     (メッセージクラス: OscMessage 実装)
        ┃→ DeviceFlag.kt     (メッセージクラス: OscMessage 実装)
        ┗→ SetSceneBundle.kt (バンドルクラス: OscBundle 実装)
```

`SetSceneBundle` は内部で `MeshPoints.toNamedArgs()` / `DeviceFlag.toNamedArgs()` を呼び出すため、  
呼び元が生マップを意識することなく型安全に送信できます。

```kotlin
runtime.sendBundle(
    bundle = SetSceneBundle(
        meshPoints = MeshPoints(
            points = listOf(MeshPoints.Point(x = 1, y = 2, z = 3.0f))
        ),
        deviceFlag = DeviceFlag(enabled = true),
    ),
    target = target,
)
```
