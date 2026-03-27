# osc-transport-udp

## 対象

- OSC codec、UDP transport、runtime との loopback 統合

## 現状

既存テスト:

- `OscCodecBoolBlobTest`
- `UdpOscTransportIntegrationTest`
- `OscRuntimeUdpIntegrationTest`

既に守れている範囲:

- bool と blob の encode/decode
- loopback での基本送受信
- runtime 経由の UDP 統合

不足している範囲:

- 壊れたバイナリ入力に対する decode 失敗
- bundle packet の異常系 decode
- bind 失敗、ポート競合、close 後の再送などの transport 異常系
- string、int、float を含む codec 単体の境界値
- stop 後のリソース解放確認

不適切または弱い既存テスト:

- loopback 統合に寄っており、codec 破損時の局所原因を切り分けにくい
- 実ポート依存の統合ケースだけでは失敗時の原因特定に時間がかかる

重複整理候補:

- bool/blob の代表ケースは codec 単体と runtime UDP 統合の双方に散っている可能性がある
- 送受信成功ケースは `UdpOscTransportIntegrationTest` と `OscRuntimeUdpIntegrationTest` で一部責務が重なる

## 追加するテスト

1. `OscCodecMalformedPacketTest` を追加する。
- 異常系: 不正な type tag。
- 異常系: blob サイズと payload 長が不一致。
- 異常系: 4 byte 境界が壊れた packet。
- 異常系: bundle ヘッダ不正。

2. `OscCodecScalarCoverageTest` を追加する。
- 正常系: int、float、string の encode/decode。
- 境界: 空文字列、負数、端数付き float。

3. `UdpOscTransportLifecycleTest` を追加する。
- 正常系: start 後に listen できる。
- 正常系: stop 後にソケットが閉じる。
- 異常系: 同一ポート bind 競合で失敗する。

4. `UdpOscTransportIntegrationTest` を補強する。
- 異常系: 連続エラー時の errors flow を増やす。
- 境界: stop 後送信時の扱いを固定する。

## 修正する既存テスト

1. codec の責務と transport の責務を分ける。
- codec の成否は codec 単体で完結させる。
- UDP 側は socket と flow のライフサイクル確認に寄せる。

2. 実ポート使用テストには helper を導入し、待機やポート確保の実装を共通化する。

## 重複整理

1. loopback での成功送受信は 1 本を最小代表ケースに絞る。
2. bool/blob の正当性は codec 単体を主、runtime UDP 統合を従にする。

## 推奨検証コマンド

- `./gradlew :osc-transport-udp:test --no-daemon`

## 要レポート条件

- UDP の異常挙動が OS 依存で安定せず、`src/main` を変えないと再現性を確保できない。