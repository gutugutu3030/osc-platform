# スキーマの書き方ガイド

osc-platform では、OSC メッセージの仕様を **スキーマ** として定義します。
スキーマを書くことで、CLI・MCP・ドキュメント生成・コード生成など、すべてのツールがその定義を共有できるようになります。

このガイドでは、スキーマの書き方をゼロから丁寧に解説します。

---

## 目次

1. [スキーマとは](#1-スキーマとは)
2. [対応フォーマット](#2-対応フォーマット)
3. [はじめてのスキーマ — 最小構成](#3-はじめてのスキーマ--最小構成)
4. [メッセージ定義の基本](#4-メッセージ定義の基本)
5. [対応する型一覧](#5-対応する型一覧)
6. [配列（array）の使い方](#6-配列arrayの使い方)
7. [タプル（tuple）の使い方](#7-タプルtupleの使い方)
8. [バンドル（bundle）の定義](#8-バンドルbundleの定義)
9. [命名ルール](#9-命名ルール)
10. [スキーマ探索ルール](#10-スキーマ探索ルール)
11. [予約語・キーワード一覧](#11-予約語キーワード一覧)
12. [バリデーションルール](#12-バリデーションルール)
13. [よくあるパターン集](#13-よくあるパターン集)
14. [CLI で検証する](#14-cli-で検証する)
15. [まとめ](#15-まとめ)

---

## 1. スキーマとは

OSC（Open Sound Control）では、`/light/color 255 0 0` のようにアドレスと値の列をネットワークに送信します。
しかし、このままでは「255 は何を意味するのか？」「int なのか float なのか？」が曖昧になりがちです。

**スキーマ** は、各メッセージの **パス・引数名・型・説明** を 1 か所に明示的に定義するファイルです。

```text
スキーマなし:                    スキーマあり:
/light/color 255 0 0            path: /light/color
  └─ 255 は何？型は？               r: 255  (int)  ← 名前と型が明確
                                    g: 0    (int)
                                    b: 0    (int)
```

スキーマを書くと、以下が自動的に手に入ります:

- CLI での送信コマンド (`osc send light.color --r 255 --g 0 --b 0`)
- MCP tools（LLM 連携）
- HTML/Markdown 仕様書生成
- Kotlin 型安全クラスの自動生成

---

## 2. 対応フォーマット

osc-platform では **2 つのフォーマット** でスキーマを記述できます。

| フォーマット | 拡張子 | 特徴 |
|---|---|---|
| **Kotlin DSL** | `.kts` | 型安全で IDE 補完が効く。**推奨フォーマット** |
| **YAML** | `.yaml` / `.yml` | 環境を選ばず、設定ファイルとして扱いやすい |

どちらのフォーマットで書いても、読み込まれた結果は同じ内部モデル（`OscSchema`）になります。
このガイドでは、すべての例を **Kotlin DSL と YAML の両方** で示します。

---

## 3. はじめてのスキーマ — 最小構成

最も簡単なスキーマは、**1 つのメッセージに 1 つのスカラー引数** を持つ定義です。

このスキーマが表す OSC パケット:

```text
/volume ,f 0.8
        ~~  ~~~
        型   値
```

### Kotlin DSL (`schema.kts`)

```kotlin
oscSchema {
    message("/volume") {
        scalar("level", FLOAT)
    }
}
```

### YAML (`schema.yaml`)

```yaml
messages:
  - path: /volume
    args:
      - name: level
        kind: scalar
        type: float
```

これだけで、`/volume` というパスに `level`（float 型）という引数を持つメッセージが定義されます。

> **ポイント:** スキーマには **最低 1 つのメッセージ** が必要です。空のスキーマはエラーになります。

---

## 4. メッセージ定義の基本

### 構文

メッセージは **パス（OSC アドレス）** と **引数リスト** で構成されます。
オプションで **名前** と **説明** を付けることができます。

このスキーマが表す OSC パケット:

```text
/light/color ,iii 255 0 0
              ~~~  ~~ ~ ~
              型   r  g b
```

#### Kotlin DSL

```kotlin
oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }
}
```

#### YAML

```yaml
messages:
  - path: /light/color
    description: RGB カラーを設定する
    args:
      - name: r
        kind: scalar
        type: int
      - name: g
        kind: scalar
        type: int
      - name: b
        kind: scalar
        type: int
```

### 各要素の意味

| 要素 | 必須 | 説明 |
|---|---|---|
| `path` | ✅ | OSC アドレスパス。`/` で始まる文字列（例: `/light/color`） |
| `name` | — | メッセージの論理名。省略するとパスから自動生成される（例: `light.color`） |
| `description` | — | メッセージの説明文。ドキュメント生成や MCP ツール説明に使われる |
| `args` | — | 引数ノードのリスト。省略すると引数なしのメッセージになる |

### スカラー引数

最も基本的な引数の種類です。1 つの値を表します。

#### Kotlin DSL

```kotlin
// 基本形
scalar("r", INT)

// arg() は scalar() のショートハンド（同じ意味）
arg("r", INT)
```

#### YAML

```yaml
- name: r
  kind: scalar    # kind を省略した場合も scalar として扱われる
  type: int
```

---

## 5. 対応する型一覧

スキーマで使用できるデータ型は以下の 5 種類です。

| 型トークン（YAML） | DSL 定数 | OSC type tag | Kotlin 型 | 説明 |
|---|---|---|---|---|
| `int` / `integer` / `i` | `INT` | `i` | `Int` | 32 ビット整数 |
| `float` / `f` | `FLOAT` | `f` | `Float` | 32 ビット浮動小数点数 |
| `string` / `str` / `s` | `STRING` | `s` | `String` | 文字列 |
| `bool` / `boolean` | `BOOL` | `T` / `F` | `Boolean` | 真偽値 |
| `blob` / `bytes` | `BLOB` | `b` | `ByteArray` | バイナリデータ |

> **ヒント:** YAML では複数のエイリアスが使えます（例: `int` と `integer` は同じ意味）。
> Kotlin DSL では `INT`, `FLOAT`, `STRING`, `BOOL`, `BLOB` の定数を使います。

### 型の使用例

これらのスキーマが表す OSC パケット:

```text
/device/info ,sTs "sensor-01" true "v2.1.0"
              ~~~  ~~~~~~~~~~      ~~~~~~~~
              型   deviceId        firmwareVersion

/data/chunk ,ib 0 <バイナリデータ>
             ~~  ~
             型  chunkIndex
```

#### Kotlin DSL

```kotlin
oscSchema {
    message("/device/info") {
        description("デバイスのメタ情報")
        scalar("deviceId", STRING)
        scalar("connected", BOOL)
        scalar("firmwareVersion", STRING)
    }

    message("/data/chunk") {
        description("バイナリデータチャンクを送信する")
        scalar("chunkIndex", INT)
        scalar("payload", BLOB)
    }
}
```

#### YAML

```yaml
messages:
  - path: /device/info
    description: デバイスのメタ情報
    args:
      - name: deviceId
        kind: scalar
        type: string
      - name: connected
        kind: scalar
        type: bool
      - name: firmwareVersion
        kind: scalar
        type: string

  - path: /data/chunk
    description: バイナリデータチャンクを送信する
    args:
      - name: chunkIndex
        kind: scalar
        type: int
      - name: payload
        kind: scalar
        type: blob
```

> **`bool` の文字列変換について:** CLI から送信する際、`true` / `false` / `1` / `0` / `yes` / `no` が受け付けられます。それ以外の値はエラーになります。

> **`blob` の MCP 経由について:** MCP（JSON）経由で `blob` を送受信する場合、値は base64 エンコードされた文字列として扱われます。

---

## 6. 配列（array）の使い方

複数の値をまとめて送りたい場合は **配列** を使います。

配列には **長さの指定方法** が 2 種類あります:

| 方式 | 説明 | ユースケース |
|---|---|---|
| **固定長**（`length`） | 要素数を数値で固定 | 行列（4x4 = 16 要素）など |
| **可変長**（`lengthFrom`） | 先行するスカラーフィールドの値を長さとして参照 | 可変個数のデータ列 |

### 6.1 固定長のスカラー配列

要素数が決まっている場合は `length` を使います。

このスキーマが表す OSC パケット（4x4 単位行列の場合）:

```text
/transform/matrix ,ffffffffffffffff 1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0
                   ~~~~~~~~~~~~~~~~ ~~~ ~~~ ~~~ ~~~                                         ~~~ ~~~ ~~~
                   16 個の float    m0  m1  m2  m3  ...                                    m13 m14 m15
```

#### Kotlin DSL

```kotlin
message("/transform/matrix") {
    description("4x4 変換行列を設定する（列優先、16 要素）")
    array("matrix", length = 16) {
        scalar(FLOAT)
    }
}
```

#### YAML

```yaml
- path: /transform/matrix
  description: 4x4 変換行列を設定する（列優先、16 要素）
  args:
    - name: matrix
      kind: array
      length: 16
      items:
        kind: scalar
        type: float
```

### 6.2 可変長のスカラー配列

要素数が動的に変わる場合は、**先行するスカラーフィールド**（`role: length`）を長さとして参照します。

このスキーマが表す OSC パケット（3 チャンネルの場合）:

```text
/audio/levels ,ifff 3 0.8 0.5 0.9
               ~~~~  ~  ~~~ ~~~ ~~~
               型    channelCount  levels（可変長）
```

#### Kotlin DSL

```kotlin
message("/audio/levels") {
    description("チャンネルごとの音量レベルを設定する（0.0〜1.0）")
    scalar("channelCount", INT, role = LENGTH)
    array("levels", lengthFrom = "channelCount") {
        scalar(FLOAT)
    }
}
```

#### YAML

```yaml
- path: /audio/levels
  description: チャンネルごとの音量レベルを設定する（0.0〜1.0）
  args:
    - name: channelCount
      kind: scalar
      type: int
      role: length
    - name: levels
      kind: array
      lengthFrom: channelCount
      items:
        kind: scalar
        type: float
```

> **重要なルール:**
> - `lengthFrom` で参照するフィールドは、配列よりも **前** に定義されている必要があります。
> - 参照先は `kind: scalar`、`type: int`、`role: length` でなければなりません。
> - `length`（固定長）と `lengthFrom`（可変長）を **同時に指定することはできません**。

### スカラーロール（role）

スカラー引数にはオプションで `role` を設定できます。

| role | DSL 定数 | 説明 |
|---|---|---|
| `value`（デフォルト） | `VALUE` | 通常の値フィールド |
| `length` | `LENGTH` | 後続の配列の長さを示すフィールド |

`role: length` を持つフィールドは、コード生成時に `points.size` から自動導出される computed プロパティになります。
これにより、長さフィールドを手動で管理する必要がなくなります。

---

## 7. タプル（tuple）の使い方

配列の各要素が **複数の名前付きフィールド** を持つ場合は **タプル** を使います。
例えば、3D 座標 (`x`, `y`, `z`) の配列や、ラベル付きオブジェクトの配列に適しています。

### 7.1 タプル配列（可変長）

このスキーマが表す OSC パケット（2 点の場合）:

```text
/mesh/points ,iiifiif 2 1 2 3.0 4 5 6.5
              ~~~~~~~  ~ ~ ~ ~~~ ~ ~ ~~~
              型       pointCount
                         x y z   x y z    ← 2 点分が平坦化される
```

#### Kotlin DSL

```kotlin
message("/mesh/points") {
    description("XYZ 座標点群を設定する")
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

#### YAML

```yaml
- path: /mesh/points
  description: XYZ 座標点群を設定する
  args:
    - name: pointCount
      kind: scalar
      type: int
      role: length
    - name: points
      kind: array
      lengthFrom: pointCount
      items:
        kind: tuple
        fields:
          - name: x
            type: int
          - name: y
            type: int
          - name: z
            type: float
```

### 7.2 複合フィールドを持つタプル

タプルのフィールドには、すべての対応型を混在させることができます。

このスキーマが表す OSC パケット（2 オブジェクトの場合）:

```text
/scene/objects ,iisTs 2 1 "box" true 2 "sphere" false
                ~~~~~ ~  ~ ~~~~~ ~~~~ ~ ~~~~~~~~ ~~~~~
                型    objectCount
                        id label visible id label  visible  ← 平坦化
```

#### Kotlin DSL

```kotlin
message("/scene/objects") {
    description("ラベルと表示状態を持つシーンオブジェクト一覧を設定する")
    scalar("objectCount", INT, role = LENGTH)
    array("objects", lengthFrom = "objectCount") {
        tuple {
            field("id", INT)
            field("label", STRING)
            field("visible", BOOL)
        }
    }
}
```

#### YAML

```yaml
- path: /scene/objects
  description: ラベルと表示状態を持つシーンオブジェクト一覧を設定する
  args:
    - name: objectCount
      kind: scalar
      type: int
      role: length
    - name: objects
      kind: array
      lengthFrom: objectCount
      items:
        kind: tuple
        fields:
          - name: id
            type: int
          - name: label
            type: string
          - name: visible
            type: bool
```

> **ルール:**
> - タプルには **1 つ以上のフィールド** が必要です（空のタプルはエラー）。
> - タプル内のフィールド名は **重複できません**。

---

## 8. バンドル（bundle）の定義

**バンドル** は、複数のメッセージをまとめてアトミック（一括）に送信するためのグループ定義です。

### 構文

このスキーマが表す OSC バンドル（アトミック送信時のワイヤ上の構造）:

```text
#bundle <timetag>
├─ /light/color ,iii 255 0 0
└─ /device/flag ,T true
```

個別メッセージとして送信する場合は通常の OSC パケットです:

```text
/light/color ,iii 255 0 0
/device/flag ,T true
```

#### Kotlin DSL

```kotlin
oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    message("/device/flag") {
        description("デバイスのフラグを設定する")
        scalar("enabled", BOOL)
    }

    bundle("set_scene") {
        description("カラーとフラグをアトミックに設定する")
        message("/light/color")
        message("/device/flag")
    }
}
```

#### YAML

```yaml
messages:
  - path: /light/color
    description: RGB カラーを設定する
    args:
      - name: r
        kind: scalar
        type: int
      - name: g
        kind: scalar
        type: int
      - name: b
        kind: scalar
        type: int

  - path: /device/flag
    description: デバイスのフラグを設定する
    args:
      - name: enabled
        kind: scalar
        type: bool

bundles:
  - name: set_scene
    description: カラーとフラグをアトミックに設定する
    messages:
      - ref: /light/color
      - ref: /device/flag
```

### 各要素の意味

| 要素 | 必須 | 説明 |
|---|---|---|
| `name` | ✅ | バンドルの論理名（空白不可） |
| `description` | — | バンドルの説明文 |
| `messages` | ✅ | 含まれるメッセージへの参照リスト（パスまたは名前）。最低 1 つ必要 |

### バンドルの参照方法

バンドル内の `message`（DSL）/ `ref`（YAML）では、以下のいずれかでメッセージを参照できます:

- **パス形式**: `/light/color`（`/` で始まる）
- **名前形式**: `light.color`

> **ルール:**
> - バンドルが参照するメッセージは、同じスキーマ内に定義されている必要があります。
> - バンドル内のメッセージ間で **引数名が衝突してはなりません**（例: 2 つのメッセージに同名の `id` がある場合はエラー）。

---

## 9. 命名ルール

### メッセージ名の自動生成

`name` を省略した場合、パスからメッセージ名が自動的に生成されます。

| パス | 自動生成される名前 |
|---|---|
| `/light/color` | `light.color` |
| `/mesh/points` | `mesh.points` |
| `/device/info` | `device.info` |

ルール: パスの `/` をドット `.` に置き換えた文字列になります。

### MCP ツール名の自動生成

MCP アダプタでは、パスからツール名が自動生成されます。

| パス | MCP ツール名 |
|---|---|
| `/light/color` | `set_light_color` |
| `/mesh/points` | `set_mesh_points` |

ルール: `set_` プレフィックス + パスセグメントをアンダースコア `_` で結合した文字列になります。

### バンドルの MCP ツール名

| バンドル名 | MCP ツール名 |
|---|---|
| `set_scene` | `bundle_set_scene` |
| `LightBundle` | `bundle_LightBundle` |

ルール: `bundle_` プレフィックス + バンドル名（英数字・アンダースコア以外は `_` に変換）。

### 名前のオーバーライド

自動生成された名前が適切でない場合は、`name` を明示的に指定できます。

このスキーマが表す OSC パケット（名前はワイヤ上では使われず、CLI/MCP 上の識別子です）:

```text
/light/color ,iii 255 0 0    ← パケット自体は同じ
                              CLI: osc send rgb --r 255 --g 0 --b 0
                                          ~~~ ← name で指定した論理名で送信できる
```

#### Kotlin DSL

```kotlin
message("/light/color") {
    name("rgb")
    description("RGB カラーを設定する")
    scalar("r", INT)
    scalar("g", INT)
    scalar("b", INT)
}
```

#### YAML

```yaml
- path: /light/color
  name: rgb
  description: RGB カラーを設定する
  args:
    - name: r
      kind: scalar
      type: int
```

---

## 10. スキーマ探索ルール

CLI コマンド（`run` / `send` / `doc` / `list` / `validate` / `gen` / `mcp`）で `--schema` を指定しなかった場合、以下の優先順位でスキーマファイルが探索されます。

1. カレントディレクトリの `schema.kts` を探す
2. なければ `schema.yaml` を探す
3. なければ `schema.yml` を探す
4. なければ `schema` で始まる `.kts` / `.yaml` / `.yml` ファイルの先頭（辞書順）を使用
5. それでも見つからなければエラー

> **注意:** `schema.kts` と `schema.yaml`（または `schema.yml`）が同時に存在する場合は、 **警告を出して `schema.kts` を優先** します。

---

## 11. 予約語・キーワード一覧

### Kotlin DSL のキーワード

| キーワード | 使用場所 | 説明 |
|---|---|---|
| `oscSchema { }` | トップレベル | スキーマ定義のルートブロック |
| `message(path) { }` | `oscSchema` 内 | メッセージを定義する |
| `bundle(name) { }` | `oscSchema` 内 | バンドルを定義する |
| `name(value)` | `message` 内 | メッセージの論理名を設定する |
| `description(value)` | `message` / `bundle` 内 | 説明文を設定する |
| `scalar(name, type)` | `message` 内 | スカラー引数を追加する |
| `scalar(name, type, role)` | `message` 内 | ロール付きスカラー引数を追加する |
| `arg(name, type)` | `message` 内 | `scalar` のショートハンド |
| `array(name, length) { }` | `message` 内 | 固定長の配列引数を追加する |
| `array(name, lengthFrom) { }` | `message` 内 | 可変長の配列引数を追加する |
| `scalar(type)` | `array` 内 | スカラー型の配列要素を定義する |
| `tuple { }` | `array` 内 | タプル型の配列要素を定義する |
| `field(name, type)` | `tuple` 内 | タプルのフィールドを追加する |
| `message(ref)` | `bundle` 内 | バンドルにメッセージ参照を追加する |

### DSL 型定数

| 定数 | 対応する型 |
|---|---|
| `INT` | 32 ビット整数 |
| `FLOAT` | 32 ビット浮動小数点数 |
| `STRING` | 文字列 |
| `BOOL` | 真偽値 |
| `BLOB` | バイナリデータ |

### DSL ロール定数

| 定数 | 説明 |
|---|---|
| `VALUE` | 通常の値（デフォルト） |
| `LENGTH` | 後続配列の長さ |

### YAML のキーワード

#### トップレベル

| キー | 必須 | 説明 |
|---|---|---|
| `messages` | ✅ | メッセージ定義のリスト |
| `bundles` | — | バンドル定義のリスト |

#### メッセージ定義

| キー | 必須 | 説明 |
|---|---|---|
| `path` | ✅ | OSC アドレスパス |
| `name` | — | メッセージの論理名（省略時はパスから自動生成） |
| `description` | — | メッセージの説明文 |
| `args` | — | 引数ノードのリスト |

#### 引数定義（args の要素）

| キー | 必須 | 説明 |
|---|---|---|
| `name` | ✅ | 引数名（空白不可） |
| `kind` | — | 引数の種類。`scalar`（デフォルト）または `array` |
| `type` | `scalar` 時 ✅ | データ型（`int`, `float`, `string`, `bool`, `blob`） |
| `role` | — | スカラーのロール。`value`（デフォルト）または `length` |
| `length` | `array` 時 ※ | 固定長の要素数 |
| `lengthFrom` | `array` 時 ※ | 長さを参照する先行フィールド名 |
| `items` | `array` 時 ✅ | 配列要素の仕様 |

> ※ `length` と `lengthFrom` はいずれか一方が必須です。

#### 配列要素定義（items）

| キー | 必須 | 説明 |
|---|---|---|
| `kind` | — | 要素の種類。`scalar` または `tuple`（`type` があれば `scalar`、`fields` があれば `tuple` と推定） |
| `type` | `scalar` 時 ✅ | 要素のデータ型 |
| `fields` | `tuple` 時 ✅ | タプルフィールド定義のリスト |

#### タプルフィールド定義（fields の要素）

| キー | 必須 | 説明 |
|---|---|---|
| `name` | ✅ | フィールド名（空白不可） |
| `type` | ✅ | フィールドのデータ型 |

#### バンドル定義

| キー | 必須 | 説明 |
|---|---|---|
| `name` | ✅ | バンドル名（空白不可） |
| `description` | — | バンドルの説明文 |
| `messages` | ✅ | メッセージ参照のリスト（最低 1 つ） |

#### バンドルメッセージ参照

| キー | 必須 | 説明 |
|---|---|---|
| `ref` | ✅ | メッセージへの参照（パスまたは名前） |

---

## 12. バリデーションルール

スキーマを読み込む際に、以下のルールが自動的に検証されます。
違反がある場合はエラーが報告されます。

### メッセージ全体

| ルール | 説明 |
|---|---|
| メッセージが 1 つ以上存在する | スキーマは空にできない |
| パスの重複禁止 | 同じパスを持つメッセージは定義できない |
| 名前の重複禁止 | 同じ論理名を持つメッセージは定義できない |

### 引数

| ルール | 説明 |
|---|---|
| 引数名の空白禁止 | 名前が空白の引数は許可されない |
| 引数名の重複禁止 | 同一メッセージ内で引数名は一意でなければならない |

### 配列

| ルール | 説明 |
|---|---|
| `length` >= 0 | 固定長は 0 以上の整数 |
| `length` と `lengthFrom` の排他 | 両方同時に指定できない |
| `lengthFrom` は前方参照のみ | 配列より前に定義されたスカラーを参照する |
| `lengthFrom` の参照先は INT + LENGTH | 参照先は `int` 型で `role: length` であること |
| `items` が必須 | 配列には要素定義が必要 |

### タプル

| ルール | 説明 |
|---|---|
| フィールドが 1 つ以上 | 空のタプルは禁止 |
| フィールド名の空白禁止 | 名前が空白のフィールドは許可されない |
| フィールド名の重複禁止 | 同一タプル内でフィールド名は一意でなければならない |

### バンドル

| ルール | 説明 |
|---|---|
| バンドル名の空白禁止 | 名前が空白のバンドルは許可されない |
| バンドル名の重複禁止 | 同じ名前のバンドルは定義できない |
| 参照メッセージが存在する | `ref` で指定したメッセージがスキーマ内に存在すること |
| メッセージが 1 つ以上 | バンドルは最低 1 つのメッセージを含む |
| 引数名の衝突禁止 | バンドル内のメッセージ間で引数名が重複してはならない |

---

## 13. よくあるパターン集

### パターン 1: シンプルなコントロール

デバイスの ON/OFF やシンプルなパラメータ送信。

```text
OSC パケット: /device/flag ,T true
```

```kotlin
oscSchema {
    message("/device/flag") {
        description("デバイスのフラグを設定する")
        scalar("enabled", BOOL)
    }
}
```

### パターン 2: RGB カラー

照明やビジュアルでよく使う RGB 値の送信。

```text
OSC パケット: /light/color ,iii 255 0 0
```

```kotlin
oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }
}
```

### パターン 3: 固定長行列

3D 変換行列のような固定サイズのデータ。

```text
OSC パケット: /transform/matrix ,ffffffffffffffff 1.0 0.0 0.0 0.0 ... 0.0 0.0 0.0 1.0
```

```kotlin
oscSchema {
    message("/transform/matrix") {
        description("4x4 変換行列を設定する（列優先、16 要素）")
        array("matrix", length = 16) {
            scalar(FLOAT)
        }
    }
}
```

### パターン 4: 可変長データ列

チャンネルごとの音量など、要素数が可変のデータ。

```text
OSC パケット: /audio/levels ,ifff 3 0.8 0.5 0.9
```

```kotlin
oscSchema {
    message("/audio/levels") {
        description("チャンネルごとの音量レベルを設定する（0.0〜1.0）")
        scalar("channelCount", INT, role = LENGTH)
        array("levels", lengthFrom = "channelCount") {
            scalar(FLOAT)
        }
    }
}
```

### パターン 5: 3D ポイント群（タプル配列）

XYZ 座標の配列。構造化されたデータをまとめて送信する。

```text
OSC パケット: /mesh/points ,iiifiif 2 1 2 3.0 4 5 6.5
                                    ~ ~~~~~ ~~~~~
                             pointCount 点1    点2   ← タプルが平坦化される
```

```kotlin
oscSchema {
    message("/mesh/points") {
        description("XYZ 座標点群を設定する")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }
}
```

### パターン 6: バンドルによるアトミック送信

複数メッセージをまとめて送る場合。

```text
OSC バンドル:
  #bundle <timetag>
  ├─ /mesh/points ,iiif 1 10 20 3.0
  └─ /device/flag ,T true
```

```kotlin
oscSchema {
    message("/mesh/points") {
        description("3D ポイント群を設定する")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }

    message("/device/flag") {
        description("デバイスのフラグを設定する")
        scalar("enabled", BOOL)
    }

    bundle("set_scene") {
        description("ポイント群とフラグをアトミックに設定する")
        message("/mesh/points")
        message("/device/flag")
    }
}
```

### パターン 7: フルスキーマ（実践的な例）

以下は、照明制御・3D シーン管理・デバイス情報を組み合わせた実践的なスキーマです。

各メッセージの OSC パケット:

```text
/light/color      ,iii               255 0 0
/mesh/points      ,iiifiif           2 1 2 3.0 4 5 6.5
/transform/matrix ,ffffffffffffffff  1.0 0.0 ... 0.0 1.0
/device/info      ,sTs               "sensor-01" true "v2.1.0"

#bundle <timetag>                    ← SceneBundle としてまとめて送信
├─ /mesh/points      ,iiifiif        2 1 2 3.0 4 5 6.5
└─ /transform/matrix ,ffffffffffffffff 1.0 0.0 ... 0.0 1.0
```

```kotlin
oscSchema {
    // ---- 照明 ----
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    // ---- 3D ----
    message("/mesh/points") {
        description("XYZ 座標点群を設定する")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }

    message("/transform/matrix") {
        description("4x4 変換行列を設定する（列優先、16 要素）")
        array("matrix", length = 16) {
            scalar(FLOAT)
        }
    }

    // ---- デバイス ----
    message("/device/info") {
        description("デバイスのメタ情報")
        scalar("deviceId", STRING)
        scalar("connected", BOOL)
        scalar("firmwareVersion", STRING)
    }

    // ---- バンドル ----
    bundle("SceneBundle") {
        description("3D シーン管理メッセージのバンドル")
        message("/mesh/points")
        message("/transform/matrix")
    }
}
```

---

## 14. CLI で検証する

スキーマが正しく書けているか、CLI で確認できます。

### スキーマの検証

```bash
osc validate schema.yaml
osc validate schema.kts
```

エラーがなければ `Schema is valid` と表示されます。

### スキーマの内容一覧

```bash
osc list schema.yaml
```

定義されたメッセージとバンドルの一覧が表示されます。

### HTML/Markdown 仕様書の生成

```bash
# HTML 仕様書を生成
osc doc --schema schema.yaml --out build/docs/osc-schema/index.html

# Markdown 仕様書を生成
osc doc --schema schema.yaml --format markdown --out build/docs/osc-schema/index.md
```

### メッセージの送信テスト

```bash
# スカラー引数の送信
osc send light.color --host 127.0.0.1 --port 9000 --r 255 --g 0 --b 0

# 構造化引数（JSON）の送信
osc send mesh.points --host 127.0.0.1 --port 9000 \
    --pointCount 2 \
    --points '[{"x":1,"y":2,"z":3.0},{"x":4,"y":5,"z":6.5}]'
```

> **補足:** help やこのガイドではコマンド名を `osc` と表記しています。
> 実際に `installDist` で生成される実行ファイル名は `osc-cli` です。

---

## 15. まとめ

| やりたいこと | 使うもの |
|---|---|
| 1 つの値を送る | `scalar(name, type)` |
| 固定長の配列を送る | `array(name, length = N) { scalar(type) }` |
| 可変長の配列を送る | `scalar(name, INT, role = LENGTH)` + `array(name, lengthFrom = ...)` |
| 構造化データの配列を送る | `array(...) { tuple { field(name, type) } }` |
| 複数メッセージを一括送信する | `bundle(name) { message(ref) }` |
| メッセージに説明を付ける | `description("...")` |
| メッセージ名を上書きする | `name("...")` |

スキーマを 1 つ書くだけで、CLI・MCP・ドキュメント・コード生成のすべてが連動します。
まずは [最小構成](#3-はじめてのスキーマ--最小構成) から始めて、必要に応じて配列やバンドルを追加していきましょう。

---

## 関連ドキュメント

- [README.md](../README.md) — プロジェクト全体の概要
- [osc-core/README.md](../osc-core/README.md) — コアモジュールの詳細
- [sample/README.md](../sample/README.md) — サンプルプロジェクト一覧
- [feature.md](../feature.md) — コード生成機能の提案
