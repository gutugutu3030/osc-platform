# osc-script-definition

`schema.kts` を IntelliJ IDEA などの IDE で認識させるための Kotlin custom script definition モジュールです。

このモジュールは `schema.kts` を専用スクリプトとして登録し、OSC DSL の default imports と `osc-core` の classpath を提供します。これにより、`oscSchema {}` や `INT` などの DSL 記号がエディタ上で未解決になりにくくなります。

## 役割

- `schema.kts` 用の script definition を提供する
- `com.oscplatform.core.schema.dsl.*` を default imports として付与する
- IDE が `schema.kts` を通常の `.kts` ではなく OSC DSL スクリプトとして扱えるようにする

## 利用方法

Gradle プロジェクトから `schema.kts` の補完を有効にしたい場合は、このモジュールを依存関係に追加して Gradle 同期を実行します。

```kotlin
dependencies {
    implementation("com.oscplatform:osc-script-definition:<version>")
}
```

> `schema.kts` の実行自体は `osc-core` の `SchemaLoader` が引き続き担当します。
> このモジュールの主目的は IDE の認識改善です。