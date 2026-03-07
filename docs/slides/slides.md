---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'Segoe UI', 'Noto Sans JP', sans-serif;
    font-size: 28px;
  }
  section.lead h1 {
    font-size: 2.4em;
  }
  h1 { color: #1a1a2e; border-bottom: 3px solid #0f3460; padding-bottom: 0.2em; }
  h2 { color: #16213e; }
  code { background: #f0f4f8; border-radius: 4px; padding: 2px 6px; }
  pre code { background: transparent; padding: 0; }
  table { font-size: 0.85em; }
  .highlight { color: #e94560; font-weight: bold; }
---

<!-- _class: lead -->

# OSC → schema

**これがあると**

🎛 GUI generator  
💻 CLI  
🌐 Web UI  
📄 Docs

**全部作れる。**

---

# なぜ OSC に schema が必要か

**現状の課題**

- OSC はパスと型タグだけで送受信できる → **スキーマレス**
- インターフェースが増えるたびに個別実装が発生
- CLI、GUI、MCP … それぞれが同じ「何を送るか」を再実装

**結果として起きること**

- 仕様ドリフト（実装間の乖離）
- ドキュメントと実装が同期しない
- 新しいインターフェース追加コストが高い

---

# Before vs After

| | Before | After |
|---|---|---|
| CLI | CLIが直接パスを記述 | schema から自動解決 |
| GUI | GUIが型情報を持つ | schema から自動生成 |
| Docs | 手書きで実装と乖離 | schema から自動生成 |
| MCP | LLM向けに別途実装 | schema → tools 自動生成 |

**After: `schema` が Single Source of Truth**

```
schema.kts (or schema.yaml)
  └── CLI / MCP / Web UI / Docs … すべてここから
```

---

# 解決策: OSC → schema

**OSC のメッセージ仕様をスキーマとして明示する**

```kotlin
oscSchema {
  message("/light/color") {
    description("set RGB color")
    scalar("r", INT)
    scalar("g", INT)
    scalar("b", INT)
  }
}
```

もしくは YAML で:

```yaml
messages:
  - path: /light/color
    description: set RGB color
    args:
      - { name: r, kind: scalar, type: int }
      - { name: g, kind: scalar, type: int }
      - { name: b, kind: scalar, type: int }
```

---

# アーキテクチャ: Small Core + Adapter

```
┌─────────────────────────────────────────┐
│               osc-core                  │
│  OscSchema  ·  Runtime  ·  Transport I/F│
└───────────────────┬─────────────────────┘
                    │ depends on core
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
osc-adapter-cli  osc-adapter-mcp  (future)
  CLI commands    MCP stdio srv   REST / Web UI

                    │
                    ▼
          osc-transport-udp
           (UDP 実装)
```

- Core は Schema / Runtime / Transport interface のみ
- LLM (MCP) も「1つの Adapter」として扱う
- 依存方向: `adapter → runtime(core) → transport`

---

# 具体例: schema.kts / schema.yaml

**構造化引数の例（mesh データ）**

```kotlin
message("/mesh/points") {
  description("set xyz points")
  scalar("pointCount", INT, role = LENGTH)
  array("points", lengthFrom = "pointCount") {
    tuple {
      field("x", INT)
      field("y", INT)
      field("z", FLOAT)
    }
  }
}
```

**CLI から送信:**

```bash
osc-cli send mesh.points \
  --pointCount 2 \
  --points '[{"x":1,"y":2,"z":3.0},{"x":4,"y":5,"z":6.5}]'
```

---

# 生成物マップ: schema → 全部作れる

```
schema
  │
  ├── CLI (osc-adapter-cli)
  │     send light.color --r 255 --g 0 --b 0
  │
  ├── MCP stdio server (osc-adapter-mcp)
  │     tools/list → set_light_color, set_mesh_points, ...
  │     tools/call → OSC 送信
  │
  ├── (planned) Web UI / GUI generator
  │     inputSchema から UI コンポーネントを自動生成
  │
  └── (planned) Docs
        schema から API ドキュメントを自動生成
```

**OSC 対応型:** `int / float / string / bool / blob`  
**構造:** `scalar / array / tuple` + `lengthFrom` 参照

---

# 実装状況と制約

**実装済み ✅**

- OSC Schema DSL (Kotlin) / YAML ローダ
- Runtime: 引数検証・平坦化・型変換
- osc-transport-udp: UDP 送受信 + Bundle encode/decode
- CLI adapter: `run` / `send` コマンド
- MCP adapter: `tools/list` / `tools/call` 自動生成
- bool / blob 型、Bundle 送信 API

**現フェーズの制約 ⚠**

- Bundle timetag スケジューリングは即時のみ
- MCP の `resources` / `prompts` は未対応
- Web UI / GUI generator は未実装（next phase）
- E2E テスト（UDP loopback + CLI send）は拡充中

---

# ロードマップ + Call to Action

**Next Phase**

```
[済] CLI + MCP adapter
[済] bool / blob / Bundle
[次] UDP 受信エラー可観測性
[次] REST adapter
[将来] Web UI / GUI generator
[将来] Docs 自動生成
```

**試してみる**

```bash
git clone https://github.com/gutugutu3030/osc-platform
./gradlew build :osc-cli:installDist --no-daemon
./osc-cli/build/install/osc-cli/bin/osc-cli run schema.kts
```

**schema.kts を書けば、そこから世界が広がる。**
