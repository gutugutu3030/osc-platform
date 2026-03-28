# osc-transport-udp

`OscTransport` インターフェースの UDP 実装です。OSC Codec（Message / Bundle の encode / decode）も含みます。

## 提供する機能

- UDP ソケットによる OSC パケットの送受信
- OSC Message / Bundle のバイナリエンコード・デコード
- 連続受信失敗時のサーキットブレーカ

## 使い方

```kotlin
val transport = UdpOscTransport(
    bindHost = "0.0.0.0",
    bindPort = 9000,
)
```

`OscRuntime` に渡して使います。単独で使う必要はありません。

```kotlin
val runtime = OscRuntime(schema = schema, transport = transport)
runtime.start()
```

## ライフサイクル

- `stop()` は受信ループ終了まで待機し、呼び出し完了後は同一インスタンスで再度 `start()` を呼び出せます。

## サーキットブレーカ

連続受信失敗が `25` 回を超えると受信ループを自動停止します。  
状態は `consecutiveErrorCount` / `lastReceiveError` プロパティで確認できます。

## 依存関係

```
osc-transport-udp
├── osc-core
└── kotlinx-coroutines-core
```

詳細は [`osc-core`](../osc-core/README.md) を参照してください。
