# osc-core

osc-platform のコアライブラリです。スキーマモデル・DSL・ローダ・Runtime・Transport インターフェースを提供します。  
他のモジュール（transport / adapter）はすべてこのモジュールに依存します。

## 提供する機能

| 機能 | パッケージ |
|---|---|
| スキーマモデル | `com.oscplatform.core.schema` |
| Kotlin DSL | `com.oscplatform.core.schema.dsl` |
| YAML / KTS ローダ | `com.oscplatform.core.schema.loader` |
| Runtime（バリデーション・ディスパッチ・送受信） | `com.oscplatform.core.runtime` |
| Transport インターフェース | `com.oscplatform.core.transport` |

## スキーマモデル

### OscSchema

```kotlin
data class OscSchema(
    val messages: List<OscMessageSpec>,
    val bundles: List<OscBundleSpec> = emptyList(),
)
```

- `messages`: パス・名前・引数ノードを持つメッセージ定義の一覧
- `bundles`: 複数メッセージをまとめて送信するバンドル定義の一覧

### 引数ノード

| 種別 | クラス | 説明 |
|---|---|---|
| スカラー | `ScalarArgNode` | `int / float / string / bool / blob` 単一値 |
| 配列 | `ArrayArgNode` | 固定長 (`length`) または長さ参照 (`lengthFrom`) の繰り返し |
| タプル | `TupleArgNode` | 名前付きフィールドの構造体 |

## Kotlin DSL

`schema.kts` で宣言的にスキーマを記述できます。

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

## スキーマローダ

`SchemaLoader` は `.kts` / `.yaml` / `.yml` を自動判別してロードします。

```kotlin
val schema = SchemaLoader().load(Paths.get("schema.kts"))
```

`--schema` 未指定時のファイル探索順序は以下のとおりです（Runtime / CLI / MCP で共通）：

1. `schema.kts`
2. `schema.yaml`
3. `schema.yml`
4. `schema*.(kts|yaml|yml)` の先頭ファイル

## Runtime

`OscRuntime` は送受信・バリデーション・イベント配信を担います。

> **[破壊的変更 v0.3.0]** `runtime.on(String, handler)` オーバーロードは廃止されました。  
> 受信ハンドラ登録には `OscMessageSpec` を渡す `runtime.on(spec, handler)` を使用してください。  
> 詳細は [ルートの README](../README.md#破壊的変更--v030-breaking-changes) を参照してください。

### 低レベル API（スキーマ直接操作）

```kotlin
val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 9000)
val runtime = OscRuntime(schema = schema, transport = transport)
val lightColorSpec = schema.resolveMessage("light.color") ?: error("missing schema message")

// 受信ハンドラ登録
runtime.on(lightColorSpec) { event ->
    println("received: ${event.namedArgs}")
}

runtime.start()

// 送信
runtime.send(
    messageRef = "light.color",
    rawArgs = mapOf("r" to 255, "g" to 0, "b" to 128),
    target = OscTarget("127.0.0.1", 9000),
)

runtime.stop()
```

### 高レベル API（codegen 生成クラスを利用）

`osc-codegen` が生成したクラスを使うと、spec 解決・手動シリアライズ・unsafe キャストをすべて省略できます。

```kotlin
// on: 生成クラスの companion を渡すだけ。ハンドラ引数が型付きになる
runtime.on(LightColor) { color ->
    println("r=${color.r}, g=${color.g}, b=${color.b}")
}

// send: companion + インスタンスを渡す
runtime.send(
    companion = LightColor,
    msg = LightColor(r = 255, g = 0, b = 128),
    target = OscTarget("127.0.0.1", 9000),
)

// sendBundle: 生成バンドルクラスをそのまま渡す
runtime.sendBundle(
    bundle = SetSceneBundle(
        meshPoints = MeshPoints(points = listOf(MeshPoints.Point(x = 1, y = 2, z = 3.0f))),
        deviceFlag = DeviceFlag(enabled = true),
    ),
    target = OscTarget("127.0.0.1", 9000),
)
```

生成クラスが実装するインターフェースは以下のとおりです：

| インターフェース | 実装対象 | 役割 |
|---|---|---|
| `OscMessage` | メッセージクラス本体 | `toNamedArgs()` を提供 |
| `OscMessageCompanion<T>` | メッセージクラスの companion object | `NAME`・`PATH`・`fromNamedArgs()` を提供 |
| `OscBundle` | バンドルクラス本体 | `toMessages()` で各メッセージの namedArgs に変換 |
| `OscBundleCompanion<T>` | バンドルクラスの companion object | `NAME` を提供 |

### イベント種別

| イベント | 説明 |
|---|---|
| `OscRuntimeEvent.Received` | バリデーション済みパケットを受信 |
| `OscRuntimeEvent.ValidationError` | 受信パケットがスキーマに不適合 |
| `OscRuntimeEvent.TransportErrorEvent` | Transport 受信ループでエラー発生 |

### namedArgs の型安全キャスト

低レベル API で `event.namedArgs` から値を取り出す際は `OscNamedArgs.kt` のヘルパーを使うと詳細なエラーメッセージが得られます：

```kotlin
val r = event.namedArgs.oscTyped<Int>("r", "light.color")   // 型不一致時に詳細エラー
```

## Transport インターフェース

独自 Transport を実装する場合は `OscTransport` インターフェースを実装します。

```kotlin
interface OscTransport {
    val incomingPackets: Flow<OscPacket>
    val errors: Flow<TransportError>
    suspend fun start()
    suspend fun stop()
    suspend fun send(packet: OscPacket, target: OscTarget)
}
```

標準実装は [`osc-transport-udp`](../osc-transport-udp/README.md) を参照してください。

## 依存関係

```
osc-core
├── kotlinx-coroutines-core
├── jackson-databind
├── jackson-module-kotlin
├── jackson-dataformat-yaml
└── kotlin-scripting-jsr223
```
