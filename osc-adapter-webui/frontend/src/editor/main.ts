import { getRequiredElement, getRequiredElementBySelector } from "../shared/dom";
import { CodeMirrorEditor } from "./codemirror-editor";
import { formatEditorText } from "./formatter";
import { SchemaPreviewController } from "./preview";
import { COMPLETIONS, EDITOR_TEMPLATE, normalizeSchemaDslImport } from "./template";

const editorWrap = getRequiredElementBySelector<HTMLElement>(".editor-wrap");
const preview = getRequiredElement<HTMLElement>("preview");
const status = getRequiredElement<HTMLElement>("status");
const formatButton = getRequiredElement<HTMLButtonElement>("format-btn");
const loadTemplateButton = getRequiredElement<HTMLButtonElement>("load-template-btn");
const downloadSchemaButton = getRequiredElement<HTMLButtonElement>("download-schema-btn");

const previewController = new SchemaPreviewController(preview, status);

/**
 * CodeMirror エディタのコンテナ要素を準備する。
 *
 * 既存の `<textarea>` を非表示にし、CodeMirror が描画される
 * コンテナ `<div>` を挿入する。
 *
 * @param wrap エディタラッパー要素
 * @return CodeMirror を配置するコンテナ要素
 */
function prepareEditorContainer(wrap: HTMLElement): HTMLElement {
  // 既存の textarea と補完用要素を非表示化
  const textarea = wrap.querySelector<HTMLTextAreaElement>("#editor");
  if (textarea !== null) {
    textarea.style.display = "none";
  }
  const acPopup = wrap.querySelector<HTMLElement>("#ac-popup");
  if (acPopup !== null) {
    acPopup.style.display = "none";
  }
  const cursorMirror = wrap.querySelector<HTMLElement>("#cursor-mirror");
  if (cursorMirror !== null) {
    cursorMirror.style.display = "none";
  }

  // CodeMirror 用のコンテナを追加
  const container = document.createElement("div");
  container.id = "cm-container";
  wrap.insertBefore(container, wrap.firstChild);
  return container;
}

const cmContainer = prepareEditorContainer(editorWrap);
const cmEditor = new CodeMirrorEditor(cmContainer, COMPLETIONS, (text: string) => {
  previewController.triggerEvaluate(text);
});

/**
 * Vim モードトグルボタンを作成して UI に追加する。
 *
 * エディタヘッダー内のボタン群に Vim トグルを追加する。
 * localStorage の設定に基づいて初期状態を復元する。
 *
 * @return 作成したチェックボックス要素
 */
function createVimToggle(): HTMLInputElement {
  const editorHeader = getRequiredElementBySelector<HTMLElement>(".editor-header div[style]");

  // トグルの外枠ラベル
  const label = document.createElement("label");
  label.className = "vim-toggle";
  label.title = "Vim モードの有効/無効を切り替え";

  // チェックボックス
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.id = "vim-toggle-checkbox";
  checkbox.checked = cmEditor.isVimEnabled();

  // ラベルテキスト
  const labelText = document.createElement("span");
  labelText.className = "vim-toggle-label";
  labelText.textContent = "Vim";

  label.appendChild(checkbox);
  label.appendChild(labelText);
  editorHeader.appendChild(label);

  // トグル時に Vim モードを切り替え
  checkbox.addEventListener("change", () => {
    cmEditor.setVimMode(checkbox.checked);
  });

  return checkbox;
}

/**
 * エディタの初期化処理を実行する。
 *
 * プレビュー、ボタンイベント、Vim トグルを設定する。
 */
function init(): void {
  previewController.showEmpty();

  formatButton.addEventListener("click", () => {
    applyFormat();
  });
  loadTemplateButton.addEventListener("click", () => {
    loadTemplate();
  });
  downloadSchemaButton.addEventListener("click", () => {
    downloadSchema();
  });
  preview.addEventListener("click", (event) => {
    const target = event.target;
    if (target instanceof HTMLElement && target.id === "load-template-empty-btn") {
      loadTemplate();
    }
  });

  // Vim トグルボタンの追加
  createVimToggle();
}

/**
 * フォーマットを適用してエディタ内容を整形する。
 *
 * 現在のテキストとカーソル位置を取得し、フォーマットした結果で
 * エディタを更新する。
 */
function applyFormat(): void {
  const result = formatEditorText(cmEditor.getValue(), cmEditor.getCursorPosition());
  updateEditorContent(result.text, result.cursorPosition);
}

/**
 * サンプルテンプレートをエディタに挿入する。
 */
function loadTemplate(): void {
  updateEditorContent(EDITOR_TEMPLATE);
}

/**
 * エディタ内容を更新し、必要ならカーソル位置を復元して再評価する。
 *
 * @param text エディタへ反映するテキスト
 * @param cursorPosition 復元するカーソル位置
 */
function updateEditorContent(text: string, cursorPosition?: number): void {
  cmEditor.setValue(text);
  if (cursorPosition !== undefined) {
    cmEditor.setCursorPosition(cursorPosition);
  }
  cmEditor.focus();
  previewController.triggerEvaluate(cmEditor.getValue());
}

/**
 * 現在の editor 内容を `schema.kts` としてダウンロードする。
 *
 * ダウンロード時には DSL import を先頭へ 1 回だけ正規化し、
 * editor 上で削除されていても配布物には必ず含める。
 */
function downloadSchema(): void {
  const text = normalizeSchemaDslImport(cmEditor.getValue());
  const blob = new Blob([text], { type: "text/x-kotlin;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");

  anchor.href = url;
  anchor.download = "schema.kts";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

init();

/**
 * テストからエディタにアクセスするための公開インスタンス。
 *
 * テスト環境でのみ使用する。プロダクションコードからは参照しない。
 */
export const __testEditor = cmEditor;
export const __testNormalizeSchemaDslImport = normalizeSchemaDslImport;