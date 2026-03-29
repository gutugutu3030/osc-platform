import { getCM, Vim } from "@replit/codemirror-vim";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installCodeMirrorGeometryMocks } from "./codemirror-geometry";

describe("editor vim mode", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    installCodeMirrorGeometryMocks();
    localStorage.clear();
    clearUnnamedRegister();
    document.body.innerHTML = `
      <div class="editor-header">
        <span>Kotlin DSL Editor</span>
        <div style="display:flex;gap:8px;">
          <button class="template-btn" id="format-btn">フォーマット</button>
          <button class="template-btn" id="load-template-btn">サンプルを挿入</button>
          <button class="template-btn" id="download-schema-btn">schema.kts をダウンロード</button>
        </div>
      </div>
      <div class="editor-wrap">
        <textarea id="editor"></textarea>
        <div id="ac-popup"></div>
        <div id="cursor-mirror"></div>
      </div>
      <span id="status"></span>
      <div id="preview"></div>
    `;
  });

  afterEach(() => {
    clearUnnamedRegister();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.body.innerHTML = "";
  });

  it("Y で現在行を linewise yank できる", async () => {
    const { editor, cm, view } = await setupVimEditor("first\nsecond\nthird");

    setCursor(view, 1, 2);
    pressKeys(cm, "Y");

    const register = Vim.getRegisterController().unnamedRegister;
    expect(register.toString()).toBe("second\n");
    expect(register.linewise).toBe(true);
    expect(register.blockwise).toBe(false);

    setCursor(view, 2, 0);
    pressKeys(cm, "p");

    expect(editor.getValue()).toBe("first\nsecond\nthird\nsecond");
  });

  it("D でカーソル位置から行末まで削除できる", async () => {
    const { editor, cm, view } = await setupVimEditor("alpha beta\nsecond line");

    setCursor(view, 0, 6);
    pressKeys(cm, "D");

    const register = Vim.getRegisterController().unnamedRegister;
    expect(editor.getValue()).toBe("alpha \nsecond line");
    expect(register.toString()).toBe("beta");
    expect(register.linewise).toBe(false);
    expect(register.blockwise).toBe(false);
  });

  it("C でカーソル位置から行末まで変更して insert mode に入る", async () => {
    const { editor, cm, view } = await setupVimEditor("alpha beta\nsecond line");

    setCursor(view, 0, 6);
    pressKeys(cm, "C");

    expect(editor.getValue()).toBe("alpha \nsecond line");
    expect((cm as { state: { vim: { insertMode: boolean } } }).state.vim.insertMode).toBe(true);
  });

  it("Ctrl-V で visual block に入り blockwise yank できる", async () => {
    const { cm, view } = await setupVimEditor("abcd\nabef\nabgh");

    setCursor(view, 0, 1);
    pressKeys(cm, "<C-v>");

    const vimState = (cm as { state: { vim: { visualMode: boolean; visualBlock: boolean } } }).state.vim;
    expect(vimState.visualMode).toBe(true);
    expect(vimState.visualBlock).toBe(true);

    pressKeys(cm, "j", "j", "l", "y");

    const register = Vim.getRegisterController().unnamedRegister;
    expect(vimState.visualMode).toBe(false);
    expect(vimState.visualBlock).toBe(false);
    expect(register.blockwise).toBe(true);
    expect(register.linewise).toBe(false);
    expect(register.toString()).toBe("bgh");
  });

  it("yy dd cc yw dw の基本操作を維持する", async () => {
    const { editor, cm, view } = await setupVimEditor("alpha beta\ngamma delta\nthird line");

    setCursor(view, 0, 0);
    pressKeys(cm, "y", "y");
    expect(Vim.getRegisterController().unnamedRegister.toString()).toBe("alpha beta\n");

    setCursor(view, 2, 0);
    pressKeys(cm, "p");
    expect(editor.getValue()).toBe("alpha beta\ngamma delta\nthird line\nalpha beta");

    setCursor(view, 1, 0);
    pressKeys(cm, "d", "d");
    expect(editor.getValue()).toBe("alpha beta\nthird line\nalpha beta");

    setCursor(view, 0, 0);
    pressKeys(cm, "y", "w");
    expect(Vim.getRegisterController().unnamedRegister.toString()).toBe("alpha ");

    setCursor(view, 1, 0);
    pressKeys(cm, "d", "w");
    expect(editor.getValue()).toBe("alpha beta\nline\nalpha beta");

    setCursor(view, 0, 0);
    pressKeys(cm, "c", "c");
    expect(editor.getValue()).toBe("\nline\nalpha beta");
    expect((cm as { state: { vim: { insertMode: boolean } } }).state.vim.insertMode).toBe(true);
  });
  it("yank 時にシステムクリップボードへテキストが書き込まれる", async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: writeTextMock, readText: vi.fn() },
      writable: true,
      configurable: true,
    });

    const { cm, view } = await setupVimEditor("hello world\nsecond line");

    setCursor(view, 0, 0);
    pressKeys(cm, "y", "w");

    expect(writeTextMock).toHaveBeenCalledWith("hello ");

    setCursor(view, 0, 0);
    pressKeys(cm, "Y");

    expect(writeTextMock).toHaveBeenCalledWith("hello world\n");
  });

  it("フォーカス時にシステムクリップボードから + レジスタへ同期する", async () => {
    const readTextMock = vi.fn().mockResolvedValue("pasted from clipboard");
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockResolvedValue(undefined), readText: readTextMock },
      writable: true,
      configurable: true,
    });

    const { view } = await setupVimEditor("some text");

    // フォーカスイベントを発火してクリップボード同期をトリガー
    view.dom.dispatchEvent(new FocusEvent("focus"));
    await flushAsyncWork();

    // "+" レジスタにクリップボードの内容が反映されている
    const plusRegister = Vim.getRegisterController().getRegister("+");
    expect(plusRegister.toString()).toBe("pasted from clipboard");
  });
});

function clearUnnamedRegister(): void {
  Vim.getRegisterController().unnamedRegister.clear();
}

function pressKeys(cm: object, ...keys: string[]): void {
  for (const key of keys) {
    Vim.handleKey(cm as never, key, "user");
  }
}

/**
 * Vim のカーソル位置を指定された行・列に移動する。
 *
 * @param view CodeMirror EditorView 互換オブジェクト
 * @param lineIndex 0 ベースの行インデックス
 * @param ch 行内のカラム位置（0 ベース）
 */
function setCursor(view: { state: { doc: { line: (lineNumber: number) => { from: number } } }; dispatch: (spec: { selection: { anchor: number } }) => void; dom: HTMLElement }, lineIndex: number, ch: number): void {
  const line = view.state.doc.line(lineIndex + 1);
  view.dispatch({ selection: { anchor: line.from + ch } });
}

async function setupVimEditor(initialText: string): Promise<{
  editor: {
    getValue(): string;
    getView(): object;
    setValue(text: string): void;
    setVimMode(enabled: boolean): void;
  };
  cm: object;
  view: {
    dom: HTMLElement;
    state: { doc: { line: (lineNumber: number) => { from: number } } };
    dispatch: (spec: { selection: { anchor: number } }) => void;
  };
}> {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      json: async () => ({ success: true, schema: { messages: [], bundles: [] } }),
    }),
  );

  const mod = await import("../src/editor/main");
  mod.__testEditor.setVimMode(true);
  mod.__testEditor.setValue(initialText);

  const view = mod.__testEditor.getView();
  const cm = getCM(view);
  if (cm === null) {
    throw new Error("Failed to get CodeMirror vim adapter");
  }

  clearUnnamedRegister();

  return {
    editor: mod.__testEditor,
    cm,
    view,
  };
}

/**
 * 非同期マイクロタスクキューをフラッシュする。
 *
 * `Promise.resolve()` を 2 回 await することで、
 * テスト対象コード内でチェーンされた非同期処理（クリップボード読み書きなど）を
 * 確実に完了させる。
 *
 * @return 非同期処理のフラッシュ完了を示す Promise
 */
async function flushAsyncWork(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}
