# osc-codegen

`schema.yaml` / `schema.kts` に記述した OSC スキーマ定義から、型安全な Kotlin クラスを生成するコード生成ライブラリです。

`osc-gradle-plugin` の内部実装として使われますが、Gradle に依存しないため CLI やカスタムビルドツールから直接呼び出すこともできます。

## 提供する機能

| クラス / オブジェクト | 役割 |
|---|---|
| `OscCodegen` | スキーマファイルパスからコード生成するファサード |
| `KotlinCodeGenerator` | `OscMessageSpec` / `OscBundleSpec` → Kotlin ソースへの変換ロジック |
| `CodeGenOptions` | 生成先パッケージ名・言語などのオプション |

## 生成されるクラスの構造

スキーマで定義した 1 メッセージにつき 1 つの `data class` が生成されます。

### 入力スキーマ例 (`schema.yaml`)

```yaml
messages:
  - path: /light/color
    name: light.color
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
```

### 生成される Kotlin クラス

```kotlin
data class LightColor(
    val r: Int,
    val g: Int,
    val b: Int,
) : OscMessage {

    override fun toNamedArgs(): Map<String, Any?> = mapOf(
        "r" to r,
        "g" to g,
        "b" to b,
    )

    companion object : OscMessageCompanion<LightColor> {
        override val PATH: String = "/light/color"
        override val NAME: String = "light.color"

        override fun fromNamedArgs(args: Map<String, Any?>): LightColor =
            LightColor(
                r = args.oscTyped<Int>("r", NAME),
                g = args.oscTyped<Int>("g", NAME),
                b = args.oscTyped<Int>("b", NAME),
            )
    }
}
```

`fromNamedArgs` 内の `oscTyped<T>()` は `osc-core` の型安全キャストヘルパーで、型不一致があればメッセージ名・キー名入りの詳細なエラーになります：

```
[light.color] Type mismatch for 'r': expected Int, got String (value=hello)
```

### 生成規則

| スキーマの要素 | 生成されるもの |
|---|---|
| `kind: scalar, role: value`（デフォルト） | コンストラクタパラメータ |
| `kind: scalar, role: length` | 配列サイズの computed property（コンストラクタには含まれない） |
| `kind: array` （スカラー要素） | `List<T>` コンストラクタパラメータ |
| `kind: array` （タプル要素） | `List<NestedDataClass>` コンストラクタパラメータ + nested data class |
| `bundles` エントリ | 各メッセージクラスをフィールドに持つ Bundle ファサードクラス |

### インターフェース実装

生成クラスは `osc-core` が定義する以下のインターフェースを実装します。

| インターフェース | 実装対象 | 用途 |
|---|---|---|
| `OscMessage` | メッセージクラス本体 | `OscRuntime.send(companion, msg, target)` に渡せる |
| `OscMessageCompanion<T>` | companion object | `OscRuntime.on(companion) { msg -> }` に渡せる |
| `OscBundle` | バンドルクラス本体 | `OscRuntime.sendBundle(bundle, target)` に渡せる |
| `OscBundleCompanion<T>` | バンドル companion object | `NAME` を提供 |

インターフェースにより `OscRuntime` の高レベル API を使うと、spec 解決・手動デシリアライズ・unsafe キャストが不要になります。

```kotlin
// on: spec 解決不要・手動 fromNamedArgs 不要
runtime.on(LightColor) { color ->
    println("r=${color.r}, g=${color.g}, b=${color.b}")
}

// send: NAME ・ toNamedArgs() を自動解決
runtime.send(
    companion = LightColor,
    msg = LightColor(r = 255, g = 0, b = 128),
    target = OscTarget("127.0.0.1", 9000),
)

// sendBundle: 各メッセージの変換をバンドルクラス内部に隐蔾
runtime.sendBundle(
    bundle = SetSceneBundle(
        meshPoints = MeshPoints(points = listOf(MeshPoints.Point(x = 1, y = 2, z = 3.0f))),
        deviceFlag = DeviceFlag(enabled = true),
    ),
    target = OscTarget("127.0.0.1", 9000),
)
```

## バンドルクラスの生成

`schema.yaml` の `bundles` エントリに対して、各メッセージクラスをフィールドに持つ Bundle ファサードクラスが生成されます。

### 入力スキーマ例

```yaml
bundles:
  - name: set_scene
    description: ポイント群とデバイスフラグをアトミックに設定する
    messages:
      - ref: /mesh/points
      - ref: /device/flag
```

### 生成される Bundle クラス

```kotlin
data class SetSceneBundle(
    val meshPoints: MeshPoints,
    val deviceFlag: DeviceFlag,
) : OscBundle {

    override fun toMessages(): List<Pair<String, Map<String, Any?>>> = listOf(
        MeshPoints.NAME to meshPoints.toNamedArgs(),
        DeviceFlag.NAME to deviceFlag.toNamedArgs(),
    )

    companion object : OscBundleCompanion<SetSceneBundle> {
        override val NAME: String = "set_scene"
    }
}
```

### バンドルクラス名の導出ルール

| 変換元 | 変換後 | 例 |
|---|---|---|
| アンダースコア / ドット / ハイフン 区切り | 各セグメントを PascalCase で連結し、末尾に `Bundle` | `set_scene` → `SetSceneBundle` |
| ドット区切り | 同上 | `light.setup` → `LightSetupBundle` |

## sealed interface によるメッセージ集約（オプション）

`CodeGenOptions.sealedInterfaceName` を指定すると、スキーマ内の全メッセージクラスを束ねる `sealed interface` が追加生成されます。
各メッセージクラスは `OscMessage` の代わりにこの sealed interface を実装するようになります（sealed interface 自体が `OscMessage` を継承するため、既存 API との互換性は維持されます）。

### 有効化方法

```kotlin
val options = CodeGenOptions(
    packageName = "com.example.osc.generated",
    sealedInterfaceName = "OscMessages",  // この指定で sealed interface が生成される
)
```

未指定（デフォルト `null`）の場合、従来どおりの `OscMessage` 直接実装が生成されます。

### 生成される sealed interface

```kotlin
sealed interface OscMessages : OscMessage
```

### 生成されるメッセージクラス（sealed 有効時）

```kotlin
data class LightColor(
    val r: Int,
    val g: Int,
    val b: Int,
) : OscMessages {
    // toNamedArgs(), companion object 等は従来と同一
}
```

### 利点: `when` 式の網羅性チェック

Kotlin の `when` 式で全メッセージ種別を網羅的に処理でき、分岐漏れをコンパイル時に検出できます。

```kotlin
fun handleMessage(msg: OscMessages): String = when (msg) {
    is LightColor -> "color: r=${msg.r}, g=${msg.g}, b=${msg.b}"
    is SensorValue -> "sensor: v=${msg.v}"
    // 新しいメッセージを追加した場合、ここに分岐を追加しないとコンパイルエラーになる
}
```

### Java 11 互換性

| 項目 | 影響 |
|---|---|
| バイトコード互換 | Kotlin の `sealed interface` は通常の Java interface としてコンパイルされるため、Java 11 のバイトコードで問題なく動作する |
| `instanceof` チェック | Java 側から `instanceof LightColor` 等で個別メッセージ型を判定可能 |
| `when` 網羅性チェック | Kotlin コンパイラの機能であり、Java 側では利用不可（if-else チェーンで代替） |
| 既存 API | sealed interface は `OscMessage` を継承するため、`OscRuntime.on()` / `send()` はそのまま利用可能 |

### 既存ユーザーへの影響

- `sealedInterfaceName` を指定しない限り、生成コードは従来とまったく同一
- 指定した場合でも `OscMessage` / `OscMessageCompanion` への互換性は sealed interface の継承で維持される
- `OscRuntime.on()` / `send()` は `OscMessage` 型を受け付けるため、sealed interface 有効時も無効時も同じように動作する

## プログラムから直接使う

Gradle プラグインを使わずにプログラムから呼び出す場合は `OscCodegen.generateFromFile()` を使います。

```kotlin
import com.oscplatform.codegen.CodeGenOptions
import com.oscplatform.codegen.OscCodegen
import java.nio.file.Paths

val files: Map<String, String> = OscCodegen.generateFromFile(
    schemaFile = Paths.get("schema.yaml"),
    options = CodeGenOptions(packageName = "com.example.osc.generated"),
)

// files: 相対ファイルパス → ソースコード文字列
files.forEach { (path, source) ->
    println("=== $path ===")
    println(source)
}
```

戻り値は `相対ファイルパス → ファイル内容` のマップです。ファイルへの書き出しはオーバーロードに委ねる設計になっているため、メモリ上でソースを検証したい場合などにも利用できます。

## クラス名・フィールド名の導出ルール

| 変換元 | 変換後 | 例 |
|---|---|---|
| メッセージ名（ドット区切り） | PascalCase クラス名 | `light.color` → `LightColor` |
| パス区切り含む名前 | 各セグメントを PascalCase で連結 | `mesh/dual` → `MeshDual` |
| 配列フィールド名（末尾 `s`）| 末尾 `s` を除いた PascalCase | `points` → `Point`（nested class 名） |

## 依存関係

```
osc-codegen
└── osc-core   （OscSchema モデル・SchemaLoader を使用）
```

通常は `osc-gradle-plugin` 経由で間接的に依存します。カスタムツールから直接使う場合のみ明示的な依存宣言が必要です。

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.oscplatform:osc-codegen:<version>")
}
```
