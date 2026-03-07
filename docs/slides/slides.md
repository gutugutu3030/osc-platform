---
marp: true
theme: default
paginate: true
style: |
  :root {
    --bg-1: #070b16;
    --bg-2: #0c1222;
    --bg-3: #111827;
    --fg: #e5e7eb;
    --fg-soft: #cbd5e1;
    --accent: #22d3ee;
    --accent-2: #38bdf8;
    --line: #334155;
  }

  section {
    font-family: 'IBM Plex Sans JP', 'Avenir Next', 'Hiragino Sans', sans-serif;
    font-size: 25px;
    line-height: 1.4;
    color: var(--fg);
    background:
      radial-gradient(circle at 14% 18%, rgba(34, 211, 238, 0.16), transparent 42%),
      radial-gradient(circle at 86% 82%, rgba(56, 189, 248, 0.14), transparent 38%),
      linear-gradient(145deg, var(--bg-1) 0%, var(--bg-2) 55%, var(--bg-3) 100%);
    padding: 52px 64px;
  }

  section::after {
    color: #94a3b8;
    font-size: 0.62em;
  }

  section.lead {
    background:
      radial-gradient(circle at 20% 22%, rgba(34, 211, 238, 0.28), transparent 48%),
      radial-gradient(circle at 85% 70%, rgba(56, 189, 248, 0.2), transparent 44%),
      linear-gradient(145deg, #050910 0%, #0a1328 56%, #0f1c35 100%);
  }

  section.lead h1 {
    font-size: 2.2em;
    margin-bottom: 0.12em;
  }

  section.lead h2 {
    margin-top: 0;
    font-weight: 500;
    color: #c7e8ff;
  }

  h1 {
    color: #f8fafc;
    border-bottom: 2px solid var(--accent);
    padding-bottom: 0.2em;
    margin-bottom: 0.45em;
  }

  h2 {
    color: #dbeafe;
  }

  strong {
    color: #a5f3fc;
    font-weight: 700;
  }

  p,
  li {
    color: #e5e7eb;
  }

  blockquote {
    border-left: 4px solid var(--accent-2);
    margin: 0.45em 0;
    padding: 0.12em 0 0.12em 0.7em;
    color: #e2e8f0;
    background: rgba(15, 23, 42, 0.5);
    border-radius: 6px;
  }

  code {
    background: rgba(15, 23, 42, 0.92);
    color: #d1fae5;
    border: 1px solid #1e293b;
    border-radius: 6px;
    padding: 2px 6px;
  }

  pre {
    background: rgba(9, 14, 28, 0.95);
    border: 1px solid #1f2937;
    border-radius: 10px;
    padding: 12px 14px;
    font-size: 0.82em;
  }

  pre code {
    background: transparent;
    border: 0;
    padding: 0;
    color: #e2e8f0;
  }

  .hljs {
    color: #e2e8f0;
  }

  .hljs-string,
  .hljs-quote,
  .hljs-attr,
  .hljs-template-tag,
  .hljs-template-variable {
    color: #86efac;
  }

  .hljs-keyword,
  .hljs-selector-tag,
  .hljs-literal {
    color: #93c5fd;
  }

  .hljs-number,
  .hljs-symbol,
  .hljs-bullet {
    color: #fca5a5;
  }

  .hljs-title,
  .hljs-type,
  .hljs-class .hljs-title {
    color: #fcd34d;
  }

  .hljs-comment {
    color: #94a3b8;
  }

  table {
    width: 100%;
    font-size: 0.76em;
    border-collapse: collapse;
  }

  th,
  td {
    border: 1px solid var(--line);
    padding: 8px 10px;
    text-align: left;
  }

  th {
    background: rgba(15, 23, 42, 0.92);
    color: #f8fafc;
  }

  td {
    background: rgba(2, 6, 23, 0.72);
    color: #f8fafc;
  }

  mark,
  .highlight {
    background: linear-gradient(90deg, rgba(250, 204, 21, 0.34), rgba(250, 204, 21, 0.2));
    color: #fef3c7;
    border-radius: 4px;
    padding: 0 0.2em;
    font-weight: 700;
  }

  .small {
    font-size: 0.84em;
    color: var(--fg-soft);
  }
---

<!-- _class: lead -->

# OSC Platform
## schema を中心に、表現と実装をつなぐ

`/light/rgb 255 0 0 0.5` ― この行に **名前・型・説明** を与えると、
CLI も MCP も Docs も、すべてそこから生まれる。

対象: OSCで作品・製品を作るアーティストとエンジニア

---

# いま起きている問題

- OSC は高速で柔軟だが、仕様定義がコード外に分散しやすい
- `/path` と型タグの意味が「人の記憶」に依存する
- UI・CLI・ドキュメント・自動化が、それぞれ別の仕様を持つ
- 現場では、実装差分によるリハ直前の手戻りが起きる

> 例: `/light/rgb 255 0 0 0.5` の `255` が `int` か `float` か、
> `0.5` がアルファか輝度かで、アプリごとに解釈が割れ動作差分が発生する

**結果:** 1つの変更が複数アプリの同時修正に波及する

---

# Before / After: 仕様の持ち方

```text
  [Without schema]           [With schema]

  UI定義 ─────────┐          schema ──┬──▶ UI
  CLI引数定義 ────┼─▶ 分散   (1か所)  ├──▶ CLI
  MCP tool定義 ───┤                   ├──▶ MCP tools
  Docs記述 ────────┘                  └──▶ Docs
```

- `rate` の型を `float → int` に変えるとき、修正は `schema` 1か所
- 追加・変更・削除の全操作が、同じ1ファイルへの差分になる

---

# このリポジトリの存在意義

`osc-platform` は、OSCを置き換えるのではなく  
**OSCに「共有可能な契約層(schema)」を与える**ための基盤。

- 送信パケット中心の開発から、意味中心の開発へ
- インターフェース追加を「再実装」から「生成」に変える
- 個人技に依存した運用を、チームで再現できる運用にする

`/light/rgb` の追加なら、仕様は `message` 定義を1つ足すだけ。

---

# Core思想: Small Core + Adapter

```text
schema.kts / schema.yaml
          │
          ▼
      osc-core
  (Schema + Runtime)
     │          │
     ▼          ▼
osc-adapter-cli  osc-adapter-mcp
     │
     ▼
osc-transport-udp
```

- 依存方向: `adapter -> core -> transport`
- 追加機能は Adapter として増やせる
- LLM連携（MCP）も同じ設計で扱える
- 例: `light.rgb` を定義すると、CLIとMCPの両方に同じ意味が反映される

---

# アーティストにとってのメリット

- **事故が減る** ― `rate=0.5` のつもりが `5` で送られる型ミスを事前検証で防げる
- **更新漏れが減る** ― パラメータ追加時、操作面・説明・UIへの反映が一度に済む
- **共有できる** ― 作品の操作仕様を意図ごとセットで渡せる
- **学習コストが下がる** ― ツールをまたいでも命名・型が統一されている

**特に効く場面:** インスタレーション、VJ、舞台制御、複数人制作

---

# エンジニアにとってのメリット

- **実装を生成できる** ― CLI/MCP を手書きせず、schema から自動生成
- **テストが集約できる** ― 検証対象を「各実装」から「schema + runtime」に寄せられる
- **レビューが速くなる** ― 仕様変更は `schema.kts` の数行差分だけ確認すればよい
- **引数処理を共通化できる** ― 検証・型変換・平坦化を Runtime に集約

**効果:** 仕様変更の速度と品質を同時に上げやすい

---

# 具体例: 変更1件のコスト差

**例:** `/light/rgb` に `r: int`, `g: int`, `b: int`, `a: float` を追加

| 観点 | スキーマなし | スキーマあり |
|---|---|---|
| 変更箇所 | CLI/GUI/Docs/MCPを個別修正 | `schema` 1か所更新 |
| 型の整合性 | 各実装で個別に担保 | Runtimeで一元検証 |
| 共有 | 口頭・Wiki依存 | schema そのものが契約 |
| レビュー | 各実装の差分を個別確認 | schema差分を起点に確認 |
| 反映速度 | 実装待ち | 生成で即反映 |

---

# schema 記述: OSCメッセージに名前を与える

受信した生パケット:

```text
/light/rgb 255 0 0 0.5
```

schema を定義すると、Runtime が意味を解釈する:

```text
path: /light/rgb
r:    255   (INT)    ← 色チャンネルの型が明確になる
g:    0     (INT)
b:    0     (INT)
a:    0.5   (FLOAT)  ← アルファとして名前・型が確定する
```

---

# schema 記述: 定義から実行まで

```kotlin
oscSchema {
  message("/light/rgb") {
    description("set RGBA color")
    scalar("r", INT)
    scalar("g", INT)
    scalar("b", INT)
    scalar("a", FLOAT)
  }
}
```

```bash
# CLI から送信 (schema が引数を検証・平坦化)
osc-cli send light.rgb --r 255 --g 0 --b 0 --a 0.5
```

この定義1つが、CLI / MCP tools / 将来のUI / Docs の共通ソースになる。

---

# このリポジトリで既にできること

- Kotlin DSL と YAML で schema を定義
- Runtime で検証・変換して OSC 送信データを構築
- UDP transport で送受信、Bundle encode/decode
- CLI adapter: `run` / `send`
- MCP adapter: `tools/list` / `tools/call` 自動生成
- 対応型: `int / float / string / bool / blob`

例: `schema` に追加した `light.rgb` を CLI と MCP から同じ意味で実行できる

---

# 世界へのインパクト

**1. Creative Coding の再現性が上がる**  
作品の操作仕様が共有可能になり、1年後の再演でも再現しやすくなる。

**2. ツール間の翻訳コストが下がる**  
TouchDesigner / Unity / Web / CLI / LLM を同じ契約でつなげられる。

**3. 人とAIの協業が現実的になる**  
MCP の tools を schema から生成できるため、安全で説明可能な自動化に近づく。

---

# ロードマップ

| フェーズ | 内容 |
|---|---|
| ✅ 完了 | CLI adapter, MCP adapter, bool/blob/Bundle API |
| ⏭ 次 | UDP受信エラー可観測性, REST adapter |
| 🔭 将来 | Web UI generator, Docs自動生成, timetag scheduling拡張 |

**方針:** 実装対象を増やすより、schema を中心に接続先を増やす

---

# Quick Start

```bash
git clone https://github.com/gutugutu3030/osc-platform
cd osc-platform

# ビルド & CLIインストール
./gradlew build :osc-cli:installDist --no-daemon
export OSC="./osc-cli/build/install/osc-cli/bin/osc-cli"

# schema を読み込んで MCP サーバー起動
$OSC run schema.kts

# 直接 OSC 送信
$OSC send light.rgb --r 255 --g 0 --b 0 --a 0.5
```

`schema.kts` を書けば、CLI/MCP/将来のUIまで同じ仕様で拡張できる。  
**OSCの自由さを保ったまま、チーム開発に耐える基盤にする。**
