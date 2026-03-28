import { Vim } from "@replit/codemirror-vim";

/**
 * Vim キーマッピングのプリセット定義。
 *
 * リポジトリ内で管理する Vim カスタムキーマップを定義する。
 * 必要に応じてここにマッピングを追加する。
 */
export interface VimKeyMapping {
  /** マッピング対象の入力キー */
  lhs: string;
  /** 実行するコマンドまたはキーシーケンス */
  rhs: string;
  /** マッピングが有効なモード (例: "normal", "visual", "insert") */
  mode: string;
}

/**
 * デフォルトの Vim キーマッピングプリセット一覧。
 *
 * エディタ内で利用しやすい追加マッピングを提供する。
 * 新しいマッピングを追加する場合はこの配列に定義を追加する。
 */
export const DEFAULT_VIM_PRESETS: VimKeyMapping[] = [
  // jk で Insert モードから Normal モードへ戻る
  { lhs: "kj", rhs: "<Esc>", mode: "insert" },
];

/**
 * Vim プリセットキーマッピングを適用する。
 *
 * {@link DEFAULT_VIM_PRESETS} に定義されたすべてのマッピングを
 * `@replit/codemirror-vim` の `Vim.map` に登録する。
 */
export function applyVimPresets(): void {
  // 各プリセットを Vim エンジンに登録
  for (const mapping of DEFAULT_VIM_PRESETS) {
    Vim.map(mapping.lhs, mapping.rhs, mapping.mode);
  }
}
