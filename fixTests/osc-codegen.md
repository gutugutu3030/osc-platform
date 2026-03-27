# osc-codegen

## 対象

- Kotlin code generator、codegen facade、生成オプション

## 現状

既存テスト:

- `KotlinCodeGeneratorTest`

既に守れている範囲:

- 単純な scalar message 生成
- LENGTH と array の一部
- tuple array の一部
- bundle class 生成の一部
- class 名変換 helper

不足している範囲:

- `OscCodegen.generateFromFile` の public 契約
- YAML / KTS 入力の両対応
- unsupported language の異常系
- 生成コードのコンパイル可能性
- Kotlin 予約語、識別子境界、空 package に近い入力
- 生成 bundle / message の `fromNamedArgs` 実行契約

不適切または弱い既存テスト:

- 生成ソースの substring 確認が中心で、構文破壊や import 不整合を見逃しやすい
- generator と facade の責務が分離されていない

重複整理候補:

- class 名変換 helper のテストは generator 本体の検証と分けられる

## 追加するテスト

1. `OscCodegenTest` を追加する。
- 正常系: YAML ファイルから生成できる。
- 正常系: KTS ファイルから生成できる。
- 異常系: unsupported language で失敗する。
- 異常系: schema ファイル不正で失敗する。

2. `KotlinCodeGeneratorCompileTest` を追加する。
- 正常系: 生成結果を Kotlin compiler でコンパイルできる。
- 正常系: bundle と message が同時にコンパイルできる。

3. `KotlinCodeGeneratorIdentifierTest` を追加する。
- 境界: 記号や区切りを含む message 名。
- 境界: bundle 名の変換。
- 境界: Kotlin 予約語に近い field 名。

4. `GeneratedContractTest` を追加する。
- 正常系: `toNamedArgs` と `fromNamedArgs` が往復できる。
- 異常系: `fromNamedArgs` が型不一致を検出する。

## 修正する既存テスト

1. `KotlinCodeGeneratorTest` の substring 依存を減らす。
- 生成内容の詳細は最小限の構造確認に留める。
- 契約は compile test と runtime contract test に寄せる。

## 重複整理

1. helper 名変換の単体テストと、生成内容の詳細文字列テストを分離する。
2. generator の責務と facade の責務を別ファイルで管理する。

## 推奨検証コマンド

- `./gradlew :osc-codegen:test --no-daemon`

## 要レポート条件

- 生成コードの妥当性確認に `src/main` の API 契約変更が必要で、テスト側だけでは一貫した期待値を置けない。