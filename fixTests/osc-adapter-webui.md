# osc-adapter-webui

## 対象

- deprecated な `osc webui` アダプター、`WebUiServer` の HTTP / SSE / 送信 UI 動作

## 現状

既存テスト:

- `WebUiAdapterTest`

既に守れている範囲:

- command summary の文字列
- `--help` の終了コード
- 未知オプションのエラー表示

不足している範囲:

- `WebUiServer` 本体の起動 / 停止
- `GET /`, `GET /api/schema`, `POST /api/send`, `GET /api/events`
- sender / monitor / mcp 各モード差分
- runtime event と additional event の SSE 配信
- JSON 入力の必須項目欠落とエラー応答
- 初期 messageRef、初期 args の HTML 埋め込み

不適切または弱い既存テスト:

- `WebUiAdapterTest` は smoke test としては有効だが、本体挙動の保証には不足している
- adapter と server の責務差がテスト構成に反映されていない

重複整理候補:

- help / usage の確認は `osc-cli` 側の top-level でも近い責務を持つ

## 追加するテスト

1. `WebUiServerHttpTest` を追加する。
- 正常系: `GET /` が HTML を返す。
- 正常系: `GET /api/schema` が message 一覧 JSON を返す。
- 異常系: 未知 path が 404。

2. `WebUiServerSendApiTest` を追加する。
- 正常系: sender mode で `POST /api/send` が runtime.send を呼ぶ。
- 異常系: monitor mode で 403。
- 異常系: `messageRef` / `host` / `port` 欠落で 400。
- 異常系: runtime.send 例外で 400。

3. `WebUiServerSseTest` を追加する。
- 正常系: 接続直後に `connected` を送る。
- 正常系: runtime `received` を配信する。
- 正常系: additional event を配信する。
- 正常系: send success / send failure を配信する。

4. `WebUiServerHtmlConfigTest` を追加する。
- 正常系: 初期 target host / port が埋め込まれる。
- 正常系: `initialMessageRef` と `initialArgs` が HTML に反映される。

## 修正する既存テスト

1. `WebUiAdapterTest` の説明を smoke test に合わせる。
2. adapter test は CLI 分岐に限定し、サーバー挙動の期待を背負わせない。

## 重複整理

1. HTML / JSON / SSE の責務を別ファイルへ分離し、1 本の巨大統合テストにしない。
2. usage 確認は最小限を維持し、詳細な挙動確認は server テストへ寄せる。

## 推奨検証コマンド

- `./gradlew :osc-adapter-webui:test --no-daemon`

## 要レポート条件

- `WebUiServer` が private 実装に強く依存しており、`src/main` を変えずに HTTP 観測点を確保できない。