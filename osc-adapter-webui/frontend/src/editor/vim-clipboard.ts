import { EditorView } from "@codemirror/view";
import { Vim } from "@replit/codemirror-vim";

/**
 * Vim のレジスタ操作とシステムクリップボードを双方向に同期する。
 *
 * - **書き込み方向**: yank / delete / change 操作の結果を
 *   `navigator.clipboard.writeText` でシステムクリップボードへコピーする。
 * - **読み込み方向**: エディタがフォーカスを受け取った際に
 *   `navigator.clipboard.readText` で `+` レジスタを更新する。
 *   ユーザーは `"+p` で外部からコピーしたテキストを貼り付けられる。
 *
 * @param view クリップボード同期を有効にする CodeMirror EditorView
 */
export function setupVimClipboard(view: EditorView): void {
  // yank/delete/change → クリップボード同期
  patchRegisterForClipboardWrite();

  // フォーカス復帰時にクリップボード → "+" レジスタ同期
  syncClipboardOnFocus(view);
}

/**
 * RegisterController の pushText をラップし、
 * すべてのレジスタ書き込みをシステムクリップボードへ反映する。
 *
 * 元の処理が確実に実行された後、非同期でクリップボードへ書き込む。
 * クリップボード API が利用不可の場合はエラーを無視する。
 */
function patchRegisterForClipboardWrite(): void {
  const controller = Vim.getRegisterController();
  const origPushText = controller.pushText.bind(controller);

  // pushText をラップしてクリップボード書き込みを追加
  controller.pushText = function (
    registerName: string | null | undefined,
    operator: string,
    text: string,
    linewise?: boolean,
    blockwise?: boolean,
  ): void {
    origPushText(registerName, operator, text, linewise, blockwise);

    // ブラックホールレジスタ ("_") への書き込みはクリップボードに反映しない
    if (registerName === "_") return;

    // 非同期でクリップボードへ書き込み（失敗やAPI未対応は無視）
    navigator.clipboard?.writeText(text)?.catch(() => {});
  };
}

/**
 * エディタのフォーカスイベントを監視し、
 * システムクリップボードの内容を Vim の `+` レジスタへ同期する。
 *
 * `navigator.clipboard.readText()` はセキュアコンテキストかつ
 * ユーザー操作後でないと利用できない場合がある。
 * 権限不足やブラウザ制約で失敗した場合はエラーを無視する。
 *
 * @param view フォーカスイベントを監視する EditorView
 */
function syncClipboardOnFocus(view: EditorView): void {
  view.dom.addEventListener("focus", async () => {
    try {
      // Clipboard API が利用可能な場合のみ読み取りを試行
      if (!navigator.clipboard?.readText) return;
      const text = await navigator.clipboard.readText();
      if (text) {
        const controller = Vim.getRegisterController();
        const reg = controller.getRegister("+");
        reg.setText(text);
      }
    } catch {
      // クリップボード読み取り不可（権限不足やセキュアコンテキスト外）の場合は無視
    }
  });
}
