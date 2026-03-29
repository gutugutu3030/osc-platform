import com.oscplatform.core.schema.dsl.*

// OscRuntime に渡すスキーマ定義 (Kotlin DSL)
// 1 行目の import は IDE (IntelliJ IDEA / Android Studio) の補完・未解決参照エラー解消のために明示している。
// 実行時は KotlinScriptSchemaLoader が同 import を自動付与するため、
// 二重になっても動作に影響はない。

oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }
}
