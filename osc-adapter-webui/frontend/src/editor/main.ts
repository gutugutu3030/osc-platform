import { getRequiredElement } from "../shared/dom";
import { AutocompleteController } from "./autocomplete";
import { formatEditorText } from "./formatter";
import { SchemaPreviewController } from "./preview";
import { COMPLETIONS, EDITOR_TEMPLATE } from "./template";

const editor = getRequiredElement<HTMLTextAreaElement>("editor");
const preview = getRequiredElement<HTMLElement>("preview");
const status = getRequiredElement<HTMLElement>("status");
const popup = getRequiredElement<HTMLElement>("ac-popup");
const cursorMirror = getRequiredElement<HTMLElement>("cursor-mirror");
const formatButton = getRequiredElement<HTMLButtonElement>("format-btn");
const loadTemplateButton = getRequiredElement<HTMLButtonElement>("load-template-btn");

const previewController = new SchemaPreviewController(preview, status);
const autocomplete = new AutocompleteController(editor, popup, cursorMirror, COMPLETIONS, () => {
  previewController.triggerEvaluate(editor.value);
});

function init(): void {
  previewController.showEmpty();

  formatButton.addEventListener("click", () => {
    applyFormat();
  });
  loadTemplateButton.addEventListener("click", () => {
    loadTemplate();
  });
  preview.addEventListener("click", (event) => {
    const target = event.target;
    if (target instanceof HTMLElement && target.id === "load-template-empty-btn") {
      loadTemplate();
    }
  });

  editor.addEventListener("keydown", (event) => {
    if (autocomplete.handleKeyDown(event)) {
      return;
    }
    if (handleCompletionShortcut(event)) {
      return;
    }
    if (handleFormatShortcut(event)) {
      return;
    }
    if (handleTabIndent(event)) {
      return;
    }
    if (handlePairInsertion(event)) {
      return;
    }
    if (handleCloserSkip(event)) {
      return;
    }
    if (handlePairedBackspace(event)) {
      return;
    }
    handleEnterIndent(event);
  });

  editor.addEventListener("input", () => {
    previewController.triggerEvaluate(editor.value);
    autocomplete.trigger();
  });

  editor.addEventListener("blur", () => {
    window.setTimeout(() => autocomplete.hide(), 200);
  });
}

function handleCompletionShortcut(event: KeyboardEvent): boolean {
  if ((event.ctrlKey || event.metaKey) && event.key === " ") {
    event.preventDefault();
    autocomplete.trigger(true);
    return true;
  }
  return false;
}

function handleFormatShortcut(event: KeyboardEvent): boolean {
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === "f") {
    event.preventDefault();
    applyFormat();
    return true;
  }
  return false;
}

function handleTabIndent(event: KeyboardEvent): boolean {
  if (event.key !== "Tab") {
    return false;
  }
  event.preventDefault();
  replaceSelection("    ", editor.selectionStart + 4);
  return true;
}

function handlePairInsertion(event: KeyboardEvent): boolean {
  const pairs: Record<string, string> = { "{": "}", "(": ")", "[": "]", '"': '"' };
  const closer = pairs[event.key];
  if (closer === undefined || event.ctrlKey || event.metaKey || event.altKey) {
    return false;
  }

  const start = editor.selectionStart;
  const end = editor.selectionEnd;
  const value = editor.value;
  if (start !== end) {
    event.preventDefault();
    const selected = value.substring(start, end);
    editor.value = `${value.substring(0, start)}${event.key}${selected}${closer}${value.substring(end)}`;
    editor.selectionStart = start + 1;
    editor.selectionEnd = end + 1;
    previewController.triggerEvaluate(editor.value);
    return true;
  }

  if (event.key === '"') {
    const beforeCursor = value.substring(0, start);
    const lastLine = beforeCursor.split("\n").pop() ?? "";
    const lineQuotes = (lastLine.match(/"/g) ?? []).length;
    if (lineQuotes % 2 === 1) {
      return false;
    }
  }

  event.preventDefault();
  editor.value = `${value.substring(0, start)}${event.key}${closer}${value.substring(end)}`;
  editor.selectionStart = start + 1;
  editor.selectionEnd = start + 1;
  previewController.triggerEvaluate(editor.value);
  autocomplete.trigger();
  return true;
}

function handleCloserSkip(event: KeyboardEvent): boolean {
  if (!["}", ")", "]", '"'].includes(event.key) || event.ctrlKey || event.metaKey || event.altKey) {
    return false;
  }
  const start = editor.selectionStart;
  if (editor.value[start] !== event.key) {
    return false;
  }
  event.preventDefault();
  editor.selectionStart = start + 1;
  editor.selectionEnd = start + 1;
  return true;
}

function handlePairedBackspace(event: KeyboardEvent): boolean {
  if (event.key !== "Backspace" || event.ctrlKey || event.metaKey) {
    return false;
  }
  const start = editor.selectionStart;
  if (start === 0 || start !== editor.selectionEnd) {
    return false;
  }

  const before = editor.value[start - 1];
  const after = editor.value[start];
  const paired =
    (before === "{" && after === "}") ||
    (before === "(" && after === ")") ||
    (before === "[" && after === "]") ||
    (before === '"' && after === '"');
  if (!paired) {
    return false;
  }

  event.preventDefault();
  editor.value = `${editor.value.substring(0, start - 1)}${editor.value.substring(start + 1)}`;
  editor.selectionStart = start - 1;
  editor.selectionEnd = start - 1;
  previewController.triggerEvaluate(editor.value);
  return true;
}

function handleEnterIndent(event: KeyboardEvent): boolean {
  if (event.key !== "Enter") {
    return false;
  }

  const start = editor.selectionStart;
  const value = editor.value;
  const beforeCursor = value.substring(0, start);
  const afterCursor = value.substring(start);
  const currentLine = beforeCursor.split("\n").pop() ?? "";
  const indent = currentLine.match(/^(\s*)/)?.[1] ?? "";
  const charBefore = beforeCursor.trimEnd().slice(-1);

  if (charBefore === "{") {
    event.preventDefault();
    const nextIndent = `${indent}    `;
    if (afterCursor.trimStart().startsWith("}")) {
      const insert = `\n${nextIndent}\n${indent}`;
      replaceSelection(insert, start + 1 + nextIndent.length);
      return true;
    }
    const insert = `\n${nextIndent}`;
    replaceSelection(insert, start + insert.length);
    return true;
  }

  if (indent !== "") {
    event.preventDefault();
    const insert = `\n${indent}`;
    replaceSelection(insert, start + insert.length);
    return true;
  }

  return false;
}

function applyFormat(): void {
  const result = formatEditorText(editor.value, editor.selectionStart);
  editor.value = result.text;
  editor.selectionStart = result.cursorPosition;
  editor.selectionEnd = result.cursorPosition;
  editor.focus();
  previewController.triggerEvaluate(editor.value);
}

function loadTemplate(): void {
  editor.value = EDITOR_TEMPLATE;
  editor.focus();
  previewController.triggerEvaluate(editor.value);
}

function replaceSelection(insert: string, nextCursorPosition: number): void {
  const start = editor.selectionStart;
  const end = editor.selectionEnd;
  editor.value = `${editor.value.substring(0, start)}${insert}${editor.value.substring(end)}`;
  editor.selectionStart = nextCursorPosition;
  editor.selectionEnd = nextCursorPosition;
  previewController.triggerEvaluate(editor.value);
}

init();