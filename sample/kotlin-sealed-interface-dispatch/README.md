# kotlin-sealed-interface-dispatch

generated sealed interface と `runtime.on<OscMessages> { ... }` を組み合わせ、
複数メッセージをひとつの受信入口に束ねるサンプルです。

## 目的

- `sealedInterfaceName` による generated sealed interface の有効化方法を確認する
- generated helper `on` を import して `runtime.on<OscMessages> { ... }` を使う
- `when` 式の網羅性チェックで複数メッセージを型安全に分岐する
- 受信登録と分岐ロジックを `handleMessage(msg: OscMessages)` に分けて見通しを保つ

## 実行手順

リポジトリルートから:

```bash
./gradlew -p sample/kotlin-sealed-interface-dispatch run
```

または sample ディレクトリ内から:

```bash
cd sample/kotlin-sealed-interface-dispatch
../../gradlew run
```

## 期待される出力

受信ハンドラは並列に起動されるため、2 行の順序は前後する場合があります。

```
[Received] /light/color r=255, g=64, b=32
[Received] /sensor/value v=1.25
```

## ポート

| ポート | 用途 |
|---|---|
| 19030 | UDP ループバック（送受信共用） |

## 生成される主なファイル

`sealedInterfaceName.set("OscMessages")` を指定すると、通常のメッセージクラスに加えて次の generated ファイルが出力されます。

```text
build/generated/sources/osc/main/kotlin/com/example/osc/generated/
  LightColor.kt
  SensorValue.kt
  OscMessages.kt
  OscMessagesRuntimeExtensions.kt
```

`OscMessagesRuntimeExtensions.kt` に含まれる generated extension を import すると、次のように書けます。

```kotlin
import com.example.osc.generated.OscMessages
import com.example.osc.generated.on

runtime.on<OscMessages> { msg ->
    println(handleMessage(msg))
}
```

## ファイル構成

```text
schema.yaml          ← /light/color と /sensor/value のスキーマ
build.gradle.kts     ← sealedInterfaceName を含む codegen 設定
src/main/kotlin/
  com/example/
    Main.kt          ← union 受信デモ
```