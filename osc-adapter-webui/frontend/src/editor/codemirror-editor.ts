import { autocompletion, CompletionContext, type CompletionResult } from "@codemirror/autocomplete";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, indentOnInput } from "@codemirror/language";
import { Compartment, EditorState, type Extension } from "@codemirror/state";
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
import { applyVimPresets } from "./vim-preset";

/** localStorage に保存する Vim モードの設定キー。 */
const VIM_MODE_STORAGE_KEY = "osc-editor-vim-mode";

/**
 * CodeMirror 6 ベースのエディタラッパー。
 *
 * 既存の `<textarea>` を CodeMirror 6 エディタビューに置き換え、
 * Vim モードの動的切り替え・自動補完・ダークテーマを提供する。
 */
export class CodeMirrorEditor {
  /** CodeMirror のエディタビューインスタンス */
  private view: EditorView;

  /** Vim モード拡張を動的に切り替えるための Compartment */
  private readonly vimCompartment = new Compartment();

  /** 現在 Vim モードが有効かどうか */
  private vimEnabled: boolean;

  /**
   * CodeMirror エディタを初期化し、指定コンテナに配置する。
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
        override: [
          (context: CompletionContext): CompletionResult | null =>
            this.completionSource(context, completions),
        ],
      }),
      EditorView.updateListener.of((update: ViewUpdate) => {
        if (update.docChanged) {
          onChange(update.state.doc.toString());
        }
      }),
      this.createDarkTheme(),
    ];

    this.view = new EditorView({
      state: EditorState.create({
        doc: "",
        extensions,
      }),
      parent: container,
    });
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
}
