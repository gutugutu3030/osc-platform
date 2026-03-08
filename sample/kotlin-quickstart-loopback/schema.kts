// OscRuntime に渡すスキーマ定義 (Kotlin DSL)
// KotlinScriptSchemaLoader が "import com.oscplatform.core.schema.dsl.*" を自動で付与するため、
// ここではそのまま oscSchema { } ブロックだけを記述する。

oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }
}
