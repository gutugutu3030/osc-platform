import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installCodeMirrorGeometryMocks } from "./codemirror-geometry";

describe("editor main", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    installCodeMirrorGeometryMocks();
    document.body.innerHTML = `
      <div class="editor-header">
        <span>Kotlin DSL Editor</span>
        <div style="display:flex;gap:8px;">
          <button class="template-btn" id="format-btn">フォーマット</button>
          <button class="template-btn" id="load-template-btn">サンプルを挿入</button>
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

    const mod = await import("../src/editor/main");

    (document.getElementById("load-template-btn") as HTMLButtonElement).click();
    expect(mod.__testEditor.getValue()).toContain("oscSchema {");

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

    const mod = await import("../src/editor/main");

    mod.__testEditor.setValue('oscSchema {\nmessage("/a") {\nscalar("x", INT)\n}\n}');

    (document.getElementById("format-btn") as HTMLButtonElement).click();
    expect(mod.__testEditor.getValue()).toContain('    message("/a") {');

    await vi.advanceTimersByTimeAsync(600);
    await flushAsyncWork();

    expect(document.getElementById("status")?.textContent).toBe("エラー ✗");
    expect(document.getElementById("preview")?.textContent).toContain("syntax error");
  });

  it("Vim トグルチェックボックスで Vim モードの有効/無効を切り替えられる", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      json: async () => ({ success: true, schema: { messages: [], bundles: [] } }),
    }));

    const mod = await import("../src/editor/main");

    const checkbox = document.getElementById("vim-toggle-checkbox") as HTMLInputElement;
    expect(checkbox).not.toBeNull();
    expect(checkbox.type).toBe("checkbox");

    // 初期状態は無効
    expect(mod.__testEditor.isVimEnabled()).toBe(false);
    expect(checkbox.checked).toBe(false);

    // チェックボックスを有効にして Vim モードを有効化
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event("change"));
    expect(mod.__testEditor.isVimEnabled()).toBe(true);

    // チェックボックスを無効にして Vim モードを無効化
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event("change"));
    expect(mod.__testEditor.isVimEnabled()).toBe(false);
  });
});

async function flushAsyncWork(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}