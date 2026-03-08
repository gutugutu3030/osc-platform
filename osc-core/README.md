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

### イベント種別

| イベント | 説明 |
|---|---|
| `OscRuntimeEvent.Received` | バリデーション済みパケットを受信 |
| `OscRuntimeEvent.ValidationError` | 受信パケットがスキーマに不適合 |
| `OscRuntimeEvent.TransportErrorEvent` | Transport 受信ループでエラー発生 |

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
