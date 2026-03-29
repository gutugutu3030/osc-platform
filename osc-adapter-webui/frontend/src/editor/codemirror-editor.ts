import {
  acceptCompletion,
  autocompletion,
  closeCompletion,
  CompletionContext,
  type CompletionResult,
  moveCompletionSelection,
  startCompletion,
} from "@codemirror/autocomplete";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, indentOnInput } from "@codemirror/language";
import { Compartment, EditorState, type Extension, Prec } from "@codemirror/state";
import {
  crosshairCursor,
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
  rectangularSelection,
  type ViewUpdate,
} from "@codemirror/view";
import { vim } from "@replit/codemirror-vim";
import { detectContext, getCurrentWord, getSuggestions } from "./autocomplete";
import type { CompletionCatalog, CompletionItem } from "./types";
import { setupVimClipboard } from "./vim-clipboard";
import { applyVimPresets } from "./vim-preset";

/** localStorage に保存する Vim モードの設定キー。 */
const VIM_MODE_STORAGE_KEY = "osc-editor-vim-mode";

/**
 * OS のカラースキーム設定がダークモードかどうかを判定する。
 *
 * `window.matchMedia` が利用できない環境ではダークモードとみなす。
 *
 * @return ダークモードなら true
 */
function prefersDarkMode(): boolean {
  return !window.matchMedia?.("(prefers-color-scheme: light)").matches;
}

/**
 * CodeMirror 6 ベースのエディタラッパー。
 *
 * 既存の `<textarea>` を CodeMirror 6 エディタビューに置き換え、
 * Vim モードの動的切り替え・自動補完・OS 設定連動テーマを提供する。
 */
export class CodeMirrorEditor {
  /** CodeMirror のエディタビューインスタンス */
  private view: EditorView;

  /** Vim モード拡張を動的に切り替えるための Compartment */
  private readonly vimCompartment = new Compartment();

  /** テーマ拡張を動的に切り替えるための Compartment */
  private readonly themeCompartment = new Compartment();

  /** 現在 Vim モードが有効かどうか */
  private vimEnabled: boolean;

  /** OS カラースキーム変更監視用の MediaQueryList */
  private readonly colorSchemeQuery: MediaQueryList | null;

  /** OS カラースキーム変更時のリスナー */
  private readonly colorSchemeListener: ((event: MediaQueryListEvent) => void) | null;

  /**
   * CodeMirror エディタを初期化し、指定コンテナに配置する。
   *
   * OS のカラースキーム設定を検出し、ダーク／ライトテーマを自動で適用する。
   * `prefers-color-scheme` の変更を監視し、動的にテーマを切り替える。
   *
   * @param container エディタを配置する親 DOM 要素
   * @param completions 自動補完カタログ
   * @param onChange エディタ内容変更時のコールバック
   */
  constructor(
    container: HTMLElement,
    completions: CompletionCatalog,
    onChange: (text: string) => void,
  ) {
    // localStorage から Vim モードの設定を復元
    this.vimEnabled = localStorage.getItem(VIM_MODE_STORAGE_KEY) === "true";

    // Vim プリセットキーマッピングの適用
    applyVimPresets();

    // OS 設定に応じた初期テーマを選択
    const isDark = prefersDarkMode();
    const initialTheme = isDark ? this.createDarkTheme() : this.createLightTheme();

    // エディタの拡張機能一覧を構築
    const extensions: Extension[] = [
      this.vimCompartment.of(this.vimEnabled ? vim() : []),
      lineNumbers(),
      highlightActiveLineGutter(),
      history(),
      drawSelection(),
      dropCursor(),
      indentOnInput(),
      bracketMatching(),
      rectangularSelection(),
      crosshairCursor(),
      highlightActiveLine(),
      keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
      autocompletion({
        defaultKeymap: false,
        override: [
          (context: CompletionContext): CompletionResult | null =>
            this.completionSource(context, completions),
        ],
      }),
      // 補完候補の確定は Tab で行う（Enter ではなく）
      Prec.highest(
        keymap.of([
          { key: "Tab", run: acceptCompletion },
          { key: "Escape", run: closeCompletion },
          { key: "ArrowDown", run: moveCompletionSelection(true) },
          { key: "ArrowUp", run: moveCompletionSelection(false) },
          { key: "PageDown", run: moveCompletionSelection(true, "page") },
          { key: "PageUp", run: moveCompletionSelection(false, "page") },
          { key: "Ctrl-Space", run: startCompletion },
        ]),
      ),
      EditorView.updateListener.of((update: ViewUpdate) => {
        if (update.docChanged) {
          onChange(update.state.doc.toString());
        }
      }),
      this.themeCompartment.of(initialTheme),
    ];

    this.view = new EditorView({
      state: EditorState.create({
        doc: "",
        extensions,
      }),
      parent: container,
    });

    // Vim レジスタとシステムクリップボードの双方向同期を設定
    setupVimClipboard(this.view);

    // OS カラースキーム変更を監視し、テーマを動的に切り替える
    const mql = window.matchMedia?.("(prefers-color-scheme: dark)") ?? null;
    if (mql !== null) {
      const listener = (event: MediaQueryListEvent): void => {
        const theme = event.matches ? this.createDarkTheme() : this.createLightTheme();
        this.view.dispatch({
          effects: this.themeCompartment.reconfigure(theme),
        });
      };
      mql.addEventListener("change", listener);
      this.colorSchemeQuery = mql;
      this.colorSchemeListener = listener;
    } else {
      this.colorSchemeQuery = null;
      this.colorSchemeListener = null;
    }
  }

  /**
   * エディタの現在のテキスト内容を返す。
   *
   * @return エディタ全文のテキスト
   */
  getValue(): string {
    return this.view.state.doc.toString();
  }

  /**
   * エディタのテキスト内容を置き換える。
   *
   * @param text 設定する新しいテキスト
   */
  setValue(text: string): void {
    this.view.dispatch({
      changes: {
        from: 0,
        to: this.view.state.doc.length,
        insert: text,
      },
    });
  }

  /**
   * エディタにフォーカスを設定する。
   */
  focus(): void {
    this.view.focus();
  }

  /**
   * メインカーソルの位置（オフセット）を返す。
   *
   * @return カーソルのドキュメント先頭からのオフセット
   */
  getCursorPosition(): number {
    return this.view.state.selection.main.head;
  }

  /**
   * カーソル位置を指定オフセットに移動する。
   *
   * @param pos 設定するカーソルのオフセット
   */
  setCursorPosition(pos: number): void {
    this.view.dispatch({
      selection: { anchor: pos },
    });
  }

  /**
   * Vim モードの有効/無効を切り替える。
   *
   * @param enabled true で Vim モード有効、false で無効
   */
  setVimMode(enabled: boolean): void {
    this.vimEnabled = enabled;
    localStorage.setItem(VIM_MODE_STORAGE_KEY, String(enabled));
    this.view.dispatch({
      effects: this.vimCompartment.reconfigure(enabled ? vim() : []),
    });
  }

  /**
   * 現在 Vim モードが有効かどうかを返す。
   *
   * @return Vim モードが有効なら true
   */
  isVimEnabled(): boolean {
    return this.vimEnabled;
  }

  /**
   * CodeMirror の EditorView インスタンスを返す。
   *
   * @return CodeMirror EditorView
   */
  getView(): EditorView {
    return this.view;
  }

  /**
   * エディタと関連リソースを破棄する。
   *
   * OS カラースキーム変更リスナーを解除し、EditorView を破棄する。
   */
  dispose(): void {
    if (this.colorSchemeQuery !== null && this.colorSchemeListener !== null) {
      this.colorSchemeQuery.removeEventListener("change", this.colorSchemeListener);
    }
    this.view.destroy();
  }

  /**
   * 自動補完ソースを提供する。
   *
   * 既存の DSL コンテキスト検出ロジックを活用して、
   * カーソル位置に応じた補完候補を返す。
   *
   * @param context CodeMirror の補完コンテキスト
   * @param completions 補完カタログ
   * @return 補完結果。候補が無い場合は null
   */
  private completionSource(
    context: CompletionContext,
    completions: CompletionCatalog,
  ): CompletionResult | null {
    const text = context.state.doc.toString();
    const pos = context.pos;

    // 文字列リテラル内では補完を表示しない
    const beforeCursor = text.substring(0, pos);
    const lastLine = beforeCursor.split("\n").pop() ?? "";
    const lineQuotes = (lastLine.match(/"/g) ?? []).length;
    if (lineQuotes % 2 === 1) {
      return null;
    }

    const prefix = getCurrentWord(text, pos);
    const editorContext = detectContext(text, pos);
    const suggestions = getSuggestions(editorContext, prefix, completions);

    if (suggestions.length === 0) {
      return null;
    }

    return {
      from: pos - prefix.length,
      options: suggestions.map((item: CompletionItem) => ({
        label: item.label,
        apply: item.insert,
        detail: item.detail,
        type: item.kind,
      })),
    };
  }

  /**
   * ダークテーマの EditorView テーマ拡張を生成する。
   *
   * 既存の editor.css のカラースキームに合わせたダークテーマを定義する。
   *
   * @return CodeMirror テーマ拡張
   */
  private createDarkTheme(): Extension {
    return EditorView.theme(
      {
        "&": {
          backgroundColor: "#0f172a",
          color: "#e2e8f0",
          fontFamily: "'Courier New', monospace",
          fontSize: "13px",
          height: "100%",
        },
        ".cm-content": {
          caretColor: "#60a5fa",
          padding: "14px",
          lineHeight: "1.6",
        },
        "&.cm-focused .cm-cursor": {
          borderLeftColor: "#60a5fa",
        },
        "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": {
          backgroundColor: "#1e3a5f",
        },
        ".cm-gutters": {
          backgroundColor: "#1e293b",
          color: "#475569",
          border: "none",
          borderRight: "1px solid #334155",
        },
        ".cm-activeLineGutter": {
          backgroundColor: "#334155",
        },
        ".cm-activeLine": {
          backgroundColor: "#1e293b44",
        },
        ".cm-matchingBracket": {
          backgroundColor: "#334155",
          outline: "1px solid #60a5fa",
        },
        // Vim ステータスバー
        ".cm-vim-panel": {
          backgroundColor: "#1e293b",
          color: "#94a3b8",
          borderTop: "1px solid #334155",
          padding: "2px 8px",
          fontFamily: "'Courier New', monospace",
          fontSize: "12px",
        },
        ".cm-vim-panel input": {
          backgroundColor: "#0f172a",
          color: "#e2e8f0",
          border: "1px solid #334155",
          fontFamily: "'Courier New', monospace",
          fontSize: "12px",
        },
        // 補完ポップアップ
        ".cm-tooltip-autocomplete": {
          backgroundColor: "#1e293b",
          border: "1px solid #3b82f6",
          borderRadius: "6px",
          boxShadow: "0 4px 16px rgba(0,0,0,0.4)",
        },
        ".cm-tooltip-autocomplete > ul > li": {
          padding: "5px 10px",
          fontSize: "12px",
          color: "#93c5fd",
        },
        ".cm-tooltip-autocomplete > ul > li[aria-selected]": {
          backgroundColor: "#2563eb",
          color: "white",
        },
        ".cm-completionLabel": {
          fontFamily: "'Courier New', monospace",
        },
        ".cm-completionDetail": {
          fontSize: "10px",
          color: "#64748b",
          marginLeft: "auto",
        },
      },
      { dark: true },
    );
  }

  /**
   * ライトテーマの EditorView テーマ拡張を生成する。
   *
   * OS のカラースキームがライトモードの場合に使用する。
   * editor.css のライトテーマ変数に合わせた配色を定義する。
   *
   * @return CodeMirror テーマ拡張
   */
  private createLightTheme(): Extension {
    return EditorView.theme(
      {
        "&": {
          backgroundColor: "#f8fafc",
          color: "#1e293b",
          fontFamily: "'Courier New', monospace",
          fontSize: "13px",
          height: "100%",
        },
        ".cm-content": {
          caretColor: "#2563eb",
          padding: "14px",
          lineHeight: "1.6",
        },
        "&.cm-focused .cm-cursor": {
          borderLeftColor: "#2563eb",
        },
        "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": {
          backgroundColor: "#dbeafe",
        },
        ".cm-gutters": {
          backgroundColor: "#f1f5f9",
          color: "#94a3b8",
          border: "none",
          borderRight: "1px solid #cbd5e1",
        },
        ".cm-activeLineGutter": {
          backgroundColor: "#e2e8f0",
        },
        ".cm-activeLine": {
          backgroundColor: "#f1f5f944",
        },
        ".cm-matchingBracket": {
          backgroundColor: "#e2e8f0",
          outline: "1px solid #2563eb",
        },
        // Vim ステータスバー
        ".cm-vim-panel": {
          backgroundColor: "#f1f5f9",
          color: "#64748b",
          borderTop: "1px solid #cbd5e1",
          padding: "2px 8px",
          fontFamily: "'Courier New', monospace",
          fontSize: "12px",
        },
        ".cm-vim-panel input": {
          backgroundColor: "#ffffff",
          color: "#1e293b",
          border: "1px solid #cbd5e1",
          fontFamily: "'Courier New', monospace",
          fontSize: "12px",
        },
        // 補完ポップアップ
        ".cm-tooltip-autocomplete": {
          backgroundColor: "#ffffff",
          border: "1px solid #3b82f6",
          borderRadius: "6px",
          boxShadow: "0 4px 16px rgba(0,0,0,0.12)",
        },
        ".cm-tooltip-autocomplete > ul > li": {
          padding: "5px 10px",
          fontSize: "12px",
          color: "#2563eb",
        },
        ".cm-tooltip-autocomplete > ul > li[aria-selected]": {
          backgroundColor: "#2563eb",
          color: "white",
        },
        ".cm-completionLabel": {
          fontFamily: "'Courier New', monospace",
        },
        ".cm-completionDetail": {
          fontSize: "10px",
          color: "#94a3b8",
          marginLeft: "auto",
        },
      },
      { dark: false },
    );
  }
}
