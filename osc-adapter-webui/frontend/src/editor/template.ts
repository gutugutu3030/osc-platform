import type { CompletionCatalog } from "./types";

export const DSL_IMPORT_LINE = "import com.oscplatform.core.schema.dsl.*";

export const COMPLETIONS: CompletionCatalog = {
  top: [{ label: "oscSchema", insert: "oscSchema {\n    \n}", kind: "function", detail: "スキーマ定義のルート" }],
  schema: [
    { label: "message", insert: "message(\"", kind: "function", detail: "メッセージ定義" },
    { label: "bundle", insert: "bundle(\"", kind: "function", detail: "バンドル定義" },
  ],
  message: [
    { label: "description", insert: "description(\"", kind: "function", detail: "メッセージの説明" },
    { label: "name", insert: "name(\"", kind: "function", detail: "メッセージの論理名" },
    { label: "scalar", insert: "scalar(\"", kind: "function", detail: "スカラー引数" },
    { label: "arg", insert: "arg(\"", kind: "function", detail: "スカラー引数 (別名)" },
    { label: "array", insert: "array(\"", kind: "function", detail: "配列引数" },
  ],
  array: [
    { label: "scalar", insert: "scalar(", kind: "function", detail: "スカラー配列要素" },
    { label: "tuple", insert: "tuple {\n", kind: "function", detail: "タプル配列要素" },
  ],
  tuple: [{ label: "field", insert: "field(\"", kind: "function", detail: "タプルフィールド" }],
  bundle: [
    { label: "description", insert: "description(\"", kind: "function", detail: "バンドルの説明" },
    { label: "message", insert: "message(\"", kind: "function", detail: "メッセージ参照" },
  ],
  types: [
    { label: "INT", insert: "INT", kind: "type", detail: "整数型" },
    { label: "FLOAT", insert: "FLOAT", kind: "type", detail: "浮動小数点型" },
    { label: "STRING", insert: "STRING", kind: "type", detail: "文字列型" },
    { label: "BOOL", insert: "BOOL", kind: "type", detail: "真偽型" },
    { label: "BLOB", insert: "BLOB", kind: "type", detail: "バイナリ型" },
  ],
  roles: [
    { label: "LENGTH", insert: "LENGTH", kind: "role", detail: "長さロール" },
    { label: "VALUE", insert: "VALUE", kind: "role", detail: "値ロール" },
  ],
  namedParams: [
    { label: "role", insert: "role = ", kind: "param", detail: "スカラーのロール指定" },
    { label: "length", insert: "length = ", kind: "param", detail: "固定長指定" },
    { label: "lengthFrom", insert: "lengthFrom = \"", kind: "param", detail: "長さ参照フィールド" },
  ],
};

/**
 * schema.kts 向けの DSL import を先頭に正規化する。
 *
 * 既存の import 行がどこかに含まれていても重複させず、
 * ダウンロード用テキストでは先頭へ 1 回だけ配置する。
 *
 * @param text エディタの現在内容
 * @returns import 行を先頭に正規化した schema.kts テキスト
 */
export function normalizeSchemaDslImport(text: string): string {
  const normalizedLines = text.replace(/\r\n/g, "\n").split("\n");
  const bodyLines = normalizedLines.filter((line) => line.trim() !== DSL_IMPORT_LINE);

  while (bodyLines.length > 0 && bodyLines[0].trim() === "") {
    bodyLines.shift();
  }

  const body = bodyLines.join("\n").trimEnd();
  if (body.length === 0) {
    return `${DSL_IMPORT_LINE}\n`;
  }

  return `${DSL_IMPORT_LINE}\n\n${body}\n`;
}

export const EDITOR_TEMPLATE = `${DSL_IMPORT_LINE}

oscSchema {
    message("/light/color") {
        description("RGB カラーを設定する")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    message("/synth/volume") {
        description("シンセサイザーの音量を設定する")
        scalar("level", FLOAT)
    }

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

    bundle("SceneBundle") {
        description("シーン管理バンドル")
        message("/light/color")
        message("/synth/volume")
    }
}`;