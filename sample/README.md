# osc-platform サンプル集

Java/Kotlin ユーザーが `osc-platform` を 5 分で試せるサンプルプロジェクト一覧です。  
各サンプルは独立した Gradle プロジェクトで、`includeBuild("../..")` によりローカルビルド成果物を参照します。

## 一覧

| ディレクトリ | 目的 | 実行コマンド |
|---|---|---|
| [kotlin-quickstart-loopback](kotlin-quickstart-loopback/) | Kotlin から `OscRuntime` を最短で使う | `./gradlew -p sample/kotlin-quickstart-loopback run` |
| [kotlin-structured-bundle](kotlin-structured-bundle/) | 構造化引数 + `sendBundle` を体験 | `./gradlew -p sample/kotlin-structured-bundle run` |
| [java-runtime-basic](java-runtime-basic/) | Java ユーザー向け Kotlin ラッパー経由の実用例 | `./gradlew -p sample/java-runtime-basic run` |
| [kotlin-mcp-stdio](kotlin-mcp-stdio/) | MCP アダプタの最小 E2E テスト（stdio フレーム）| `./gradlew -p sample/kotlin-mcp-stdio test` |

※ すべてのコマンドはリポジトリルート (`osc-platform/`) から実行してください。

## 全サンプルを一括実行

```bash
# A: Kotlin ループバック
./gradlew -p sample/kotlin-quickstart-loopback run

# B: 構造化引数 + Bundle
./gradlew -p sample/kotlin-structured-bundle run

# C: Java ラッパー
./gradlew -p sample/java-runtime-basic run

# D: MCP stdio テスト
./gradlew -p sample/kotlin-mcp-stdio test
```

## 前提条件

- JDK 21
- 親リポジトリ `osc-platform` がビルド済みまたはソース利用可能（includeBuild で自動解決）

## 依存関係の解決方法

各サンプルの `settings.gradle.kts` に以下が記載されており、  
Maven Central からダウンロードせずローカルのソースがそのまま使われます。

```kotlin
includeBuild("../..")   // osc-platform のローカルビルドを参照
```

宣言した依存座標 `com.oscplatform:osc-core:0.2.0` などは、  
Gradle のコンポジットビルド機能により自動で置換されます。

## ポート割り当て

| サンプル | ポート | 用途 |
|---|---|---|
| kotlin-quickstart-loopback | 19000 | UDP ループバック |
| kotlin-structured-bundle | 19010 | UDP ループバック |
| java-runtime-basic | 19020 | 送信先ターゲット（受信側なし）|
| kotlin-mcp-stdio | 9000 | MCP tools/call の送信先（テスト用、受信側なし）|
