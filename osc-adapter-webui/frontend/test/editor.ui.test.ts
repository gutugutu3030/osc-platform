import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("editor main", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    document.body.innerHTML = `
      <button id="format-btn">フォーマット</button>
      <button id="load-template-btn">サンプルを挿入</button>
      <span id="status"></span>
      <textarea id="editor"></textarea>
      <div id="preview"></div>
      <div id="ac-popup"></div>
      <div id="cursor-mirror"></div>
    `;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.body.innerHTML = "";
  });

  it("サンプル挿入ボタンで DSL を読み込み、評価結果をプレビューに描画する", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      json: async () => ({
        success: true,
        schema: {
          messages: [{ path: "/light/color", name: "light.color", description: "RGB", args: [] }],
          bundles: [],
        },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await import("../src/editor/main");

    (document.getElementById("load-template-btn") as HTMLButtonElement).click();
    expect((document.getElementById("editor") as HTMLTextAreaElement).value).toContain("oscSchema {");

    await vi.advanceTimersByTimeAsync(600);
    await flushAsyncWork();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/evaluate",
      expect.objectContaining({ method: "POST" }),
    );
    expect(document.getElementById("status")?.textContent).toBe("スキーマ有効 ✓");
    expect(document.getElementById("preview")?.textContent).toContain("/light/color");
  });

  it("フォーマット操作後に評価エラーを受けるとエラープレビューを表示する", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      json: async () => ({ success: false, error: "syntax error" }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await import("../src/editor/main");

    const editor = document.getElementById("editor") as HTMLTextAreaElement;
    editor.value = 'oscSchema {\nmessage("/a") {\nscalar("x", INT)\n}\n}';
    editor.selectionStart = editor.value.length;
    editor.selectionEnd = editor.value.length;

    (document.getElementById("format-btn") as HTMLButtonElement).click();
    expect(editor.value).toContain('    message("/a") {');

    await vi.advanceTimersByTimeAsync(600);
    await flushAsyncWork();

    expect(document.getElementById("status")?.textContent).toBe("エラー ✗");
    expect(document.getElementById("preview")?.textContent).toContain("syntax error");
  });
});

async function flushAsyncWork(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}