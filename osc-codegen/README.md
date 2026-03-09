# osc-codegen

`schema.yaml` / `schema.kts` に記述した OSC スキーマ定義から、型安全な Kotlin クラスを生成するコード生成ライブラリです。

`osc-gradle-plugin` の内部実装として使われますが、Gradle に依存しないため CLI やカスタムビルドツールから直接呼び出すこともできます。

## 提供する機能

| クラス / オブジェクト | 役割 |
|---|---|
| `OscCodegen` | スキーマファイルパスからコード生成するファサード |
| `KotlinCodeGenerator` | `OscMessageSpec` → Kotlin ソースへの変換ロジック |
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
                r = args["r"] as Int,
                g = args["g"] as Int,
                b = args["b"] as Int,
            )
    }
}
```

### 生成規則

| スキーマの要素 | 生成されるもの |
|---|---|
| `kind: scalar, role: value`（デフォルト） | コンストラクタパラメータ |
| `kind: scalar, role: length` | 配列サイズの computed property（コンストラクタには含まれない） |
| `kind: array` （スカラー要素） | `List<T>` コンストラクタパラメータ |
| `kind: array` （タプル要素） | `List<NestedDataClass>` コンストラクタパラメータ + nested data class |

### インターフェース実装

生成クラスは `osc-core` が定義する以下のインターフェースを実装します。

- **`OscMessage`** — `toNamedArgs()` を持つ。`OscRuntime.send(companion, msg, target)` に渡せる。
- **`OscMessageCompanion<T>`** — companion object が実装。`OscRuntime.on(companion) { msg -> }` に渡せる。

これにより `OscRuntime` のオーバーロード API を使うと、spec 解決や手動デシリアライズが不要になります。

```kotlin
// before（低レベル API）
val spec = schema.resolveMessage(LightColor.NAME) ?: error("missing")
runtime.on(spec) { event ->
    val color = LightColor.fromNamedArgs(event.namedArgs)
}

// after（生成クラスを直接渡す高レベル API）
runtime.on(LightColor) { color ->
    println("r=${color.r}, g=${color.g}, b=${color.b}")
}
```

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
