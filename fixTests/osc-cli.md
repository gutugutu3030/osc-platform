# osc-cli

## 対象

- top-level main、サブコマンド dispatch、usage 組み立て

## 現状

既存テスト:

- `MainTest`
- `JarIsolationTest`

既に守れている範囲:

- top-level usage に主要コマンドが載ること
- test classpath での依存可視性の一部

不足している範囲:

- `main` の command dispatch
- help、version、unknown command の exit code 契約
- `mcp`, `webui`, CLI コマンドの委譲先確認
- `null` 引数時の分岐

不適切または弱い既存テスト:

- `MainTest` は usage の文字列確認だけで dispatch を見ていない
- `JarIsolationTest` はテスト classpath の確認に留まり、配布物保証としては弱い

重複整理候補:

- usage 系は `CliAdapterExecuteTest`, `McpAdapterExecuteTest`, `WebUiAdapterTest` と重なりやすい
- `JarIsolationTest` は `osc-core` 側と責務が近い

## 追加するテスト

1. `MainDispatchTest` を追加する。
- 正常系: `help` が top-level usage を返す。
- 正常系: `--version` が version へ委譲される。
- 正常系: `mcp` が McpAdapter に委譲される。
- 正常系: `webui` が WebUiAdapter に委譲される。
- 正常系: CLI コマンドが CliAdapter に委譲される。
- 異常系: unknown command が 1 を返す。

2. `buildTopLevelUsage` の構成テストを補強する。
- usage 行の順序を固定する。
- 重複行が出ないことを確認する。

## 修正する既存テスト

1. `JarIsolationTest` は「依存の smoke test」として明示するか、依存境界検証へ差し替える。
2. `MainTest` は usage 文字列羅列から dispatch 契約中心へ比重を移す。

## 重複整理

1. subcommand 側で十分に確認している usage 文言の重複アサートを減らす。
2. classpath 可視性の確認は一箇所へ集約する。

## 推奨検証コマンド

- `./gradlew :osc-cli:test --no-daemon`

## 要レポート条件

- `main` が `exitProcess` を直接呼ぶため、`src/main` を変更せずに安定した dispatch 単体テストを組めない。