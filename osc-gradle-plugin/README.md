# osc-gradle-plugin

OSC スキーマ（`schema.yaml` / `schema.kts`）から Kotlin クラスを自動生成する Gradle プラグインです。

プラグインを適用するだけで `generateOscSources` タスクが登録され、`compileKotlin` / `compileJava` の前に自動実行されます。生成ファイルはビルドキャッシュ対応しており、スキーマが変更されていない場合は再生成をスキップします。

## プラグイン ID

```
com.oscplatform.schema-codegen
```

## クイックスタート

### 1. プラグインを適用する

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.oscplatform.schema-codegen")
}
```

### 2. 拡張設定を書く

```kotlin
oscSchemaCodegen {
    schema.set(layout.projectDirectory.file("schema.yaml"))   // 必須
    packageName.set("com.example.osc.generated")              // 必須
    language.set("kotlin")                                    // 省略可（デフォルト "kotlin"）
}
```

### 3. ビルドする

```sh
./gradlew generateOscSources
# または compileKotlin / build 時に自動実行される
```

生成ファイルは `build/generated/sources/osc/main/kotlin/` に出力され、自動的にソースセットへ追加されます。

## 設定項目一覧

| プロパティ | 型 | 必須 | デフォルト値 | 説明 |
|---|---|---|---|---|
| `schema` | `RegularFileProperty` | ✅ | なし | スキーマファイルのパス（`.yaml` / `.kts`） |
| `packageName` | `Property<String>` | ✅ | なし | 生成クラスのパッケージ名 |
| `language` | `Property<String>` | | `"kotlin"` | 生成言語（現在 `"kotlin"` のみ対応） |
| `outputDir` | `DirectoryProperty` | | `build/generated/sources/osc/main/kotlin` | 生成ファイルの出力先ディレクトリ |

## 動作の仕組み

```
schema.yaml / schema.kts
        │
        ▼
 generateOscSources  ←── @CacheableTask（入力ファイルが変わらなければスキップ）
        │
        ▼
 build/generated/sources/osc/main/kotlin/
        │  com/example/osc/generated/
        │      LightColor.kt
        │      ...
        ▼
 compileKotlin / compileJava（自動依存）
```

1. `GenerateOscSourcesTask` が `OscCodegen.generateFromFile()` を呼び出し
2. `osc-codegen` がスキーマを解析して Kotlin ソースを生成
3. 出力ディレクトリがソースセットに追加され、通常のコンパイル対象になる

## 生成されるクラスの使い方

`OscRuntime` の高レベル API と組み合わせることで、型安全な OSC 通信を最小限の記述で実現できます。

```kotlin
// 生成型を companion object ごと渡すだけで送受信できる
runtime.on(LightColor) { color ->
    println("r=${color.r}, g=${color.g}, b=${color.b}")
}

runtime.send(
    companion = LightColor,
    msg = LightColor(r = 255, g = 0, b = 128),
    target = OscTarget("127.0.0.1", 9000),
)
```

生成クラスの詳細は [`osc-codegen`](../osc-codegen/README.md) を参照してください。

## 外部プロジェクトから使う場合

`osc-platform` のルートプロジェクトとは独立したプロジェクトから使う場合、`settings.gradle.kts` でプラグインの解決設定が必要です。

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()   // ローカルパブリッシュ済みの場合
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.oscplatform.schema-codegen") {
                useModule("com.oscplatform:osc-gradle-plugin:<version>")
            }
        }
    }
}
```

または `includeBuild` によるコンポジットビルドで参照することもできます（リポジトリ内のサンプルはこの方式を採用しています）。

## ビルドキャッシュ

`GenerateOscSourcesTask` は `@CacheableTask` アノテーションが付いており、以下の条件では再実行をスキップします。

- 入力スキーマファイルの内容が変わっていない
- `packageName` / `language` の設定値が変わっていない
- 出力ディレクトリの内容が最新の状態である

CI でリモートキャッシュを有効にしている場合も恩恵を受けられます。

## 依存関係

```
osc-gradle-plugin
└── osc-codegen
    └── osc-core
```

プラグイン自体は Gradle API にのみ依存し、実行時に `osc-codegen` をクラスパスへ取り込みます。アプリケーションのランタイム依存関係には含まれません。
