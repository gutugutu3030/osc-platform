import { esc } from "../shared/html";
import type { CompletionCatalog, CompletionItem, EditorContext } from "./types";

export function detectContext(text: string, cursorPosition: number): EditorContext {
  const beforeCursor = text.substring(0, cursorPosition);
  const scopeStack = ["top"];
  let index = 0;
  let inString = false;
  let stringChar = "";

  while (index < beforeCursor.length) {
    const ch = beforeCursor[index];
    if (!inString && (ch === '"' || ch === "'")) {
      inString = true;
      stringChar = ch;
      index += 1;
      continue;
    }
    if (inString) {
      if (ch === "\\") {
        index += 2;
        continue;
      }
      if (ch === stringChar) {
        inString = false;
      }
      index += 1;
      continue;
    }
    if (ch === "{") {
      const before = beforeCursor.substring(0, index).trimEnd();
      const keyword = getLastKeyword(before);
      if (keyword === "oscSchema") {
        scopeStack.push("schema");
      } else if (keyword === "message") {
        scopeStack.push("message");
      } else if (keyword === "array") {
        scopeStack.push("array");
      } else if (keyword === "tuple") {
        scopeStack.push("tuple");
      } else if (keyword === "bundle") {
        scopeStack.push("bundle");
      } else {
        scopeStack.push(scopeStack[scopeStack.length - 1]);
      }
    } else if (ch === "}" && scopeStack.length > 1) {
      scopeStack.pop();
    }
    index += 1;
  }

  return { scope: scopeStack[scopeStack.length - 1], inArgs: isInsideArgs(beforeCursor) };
}

export function getCurrentWord(text: string, cursorPosition: number): string {
  const before = text.substring(0, cursorPosition);
  const match = before.match(/(\w+)$/);
  return match?.[1] ?? "";
}

export function getSuggestions(
  context: EditorContext,
  prefix: string,
  completions: CompletionCatalog,
): CompletionItem[] {
  let items: CompletionItem[] = [];
  if (context.inArgs) {
    items = items.concat(completions.types ?? [], completions.roles ?? [], completions.namedParams ?? []);
  } else {
    items = items.concat(completions[context.scope] ?? []);
    if (context.scope !== "top") {
      items = items.concat(completions.types ?? []);
    }
  }

  if (prefix === "") {
    return items;
  }

  const lowerPrefix = prefix.toLowerCase();
  return items.filter((item) => item.label.toLowerCase().startsWith(lowerPrefix));
}

export class AutocompleteController {
  private items: CompletionItem[] = [];
  private index = -1;
  private prefix = "";
  private visible = false;

  public constructor(
    private readonly editor: HTMLTextAreaElement,
    private readonly popup: HTMLElement,
    private readonly cursorMirror: HTMLElement,
    private readonly completions: CompletionCatalog,
    private readonly onAccept: () => void,
  ) {}

  public handleKeyDown(event: KeyboardEvent): boolean {
    if (!this.visible) {
      return false;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      this.index = (this.index + 1) % this.items.length;
      this.updateSelection();
      return true;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      this.index = (this.index - 1 + this.items.length) % this.items.length;
      this.updateSelection();
      return true;
    }
    if (event.key === "Enter" || (event.key === "Tab" && !event.shiftKey)) {
      event.preventDefault();
      this.accept();
      return true;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      this.hide();
      return true;
    }
    return false;
  }

  public hide(): void {
    this.popup.style.display = "none";
    this.visible = false;
    this.items = [];
    this.index = -1;
  }

  public trigger(force = false): void {
    const position = this.editor.selectionStart;
    const text = this.editor.value;
    const prefix = getCurrentWord(text, position);
    const beforeCursor = text.substring(0, position);
    const lastLine = beforeCursor.split("\n").pop() ?? "";
    const lineQuotes = (lastLine.match(/"/g) ?? []).length;
    if (lineQuotes % 2 === 1) {
      this.hide();
      return;
    }

    const suggestions = getSuggestions(detectContext(text, position), prefix, this.completions);
    if (!force && prefix === "" && suggestions.length > 5) {
      this.hide();
      return;
    }
    this.show(suggestions, prefix);
  }

  private accept(): void {
    if (this.index < 0 || this.index >= this.items.length) {
      this.hide();
      return;
    }

    const item = this.items[this.index];
    const position = this.editor.selectionStart;
    const before = this.editor.value.substring(0, position);
    const after = this.editor.value.substring(position);
    const nextBefore = `${before.substring(0, before.length - this.prefix.length)}${item.insert}`;
    this.editor.value = `${nextBefore}${after}`;
    this.editor.selectionStart = nextBefore.length;
    this.editor.selectionEnd = nextBefore.length;
    this.editor.focus();
    this.hide();
    this.onAccept();
  }

  private show(items: CompletionItem[], prefix: string): void {
    if (items.length === 0) {
      this.hide();
      return;
    }

    this.items = items;
    this.prefix = prefix;
    this.index = 0;
    this.visible = true;
    this.popup.innerHTML = `${items
      .map(
        (item, idx) =>
          `<div class="ac-item${idx === 0 ? " ac-active" : ""}" data-idx="${idx}"><span class="ac-label">${esc(item.label)}</span><span class="ac-kind">${esc(item.detail || item.kind)}</span></div>`,
      )
      .join("")}<div class="ac-hint">↑↓ 選択 · Tab/Enter 確定 · Esc 閉じる</div>`;

    const coords = this.getCursorCoords();
    this.popup.style.left = `${coords.left}px`;
    this.popup.style.top = `${coords.top + 22}px`;
    this.popup.style.display = "block";

    Array.from(this.popup.querySelectorAll<HTMLElement>(".ac-item")).forEach((element) => {
      element.addEventListener("mousedown", (event) => {
        event.preventDefault();
        this.index = Number.parseInt(element.dataset.idx ?? "0", 10);
        this.accept();
      });
    });
  }

  private getCursorCoords(): { left: number; top: number } {
    const text = this.editor.value.substring(0, this.editor.selectionStart);
    this.cursorMirror.style.width = `${this.editor.clientWidth}px`;
    this.cursorMirror.textContent = text;
    const span = document.createElement("span");
    span.textContent = "|";
    this.cursorMirror.appendChild(span);

    const spanRect = span.getBoundingClientRect();
    const mirrorRect = this.cursorMirror.getBoundingClientRect();
    return {
      left: spanRect.left - mirrorRect.left,
      top: spanRect.top - mirrorRect.top - this.editor.scrollTop + this.editor.offsetTop,
    };
  }

  private updateSelection(): void {
    Array.from(this.popup.querySelectorAll<HTMLElement>(".ac-item")).forEach((element, idx) => {
      if (idx === this.index) {
        element.classList.add("ac-active");
        element.scrollIntoView({ block: "nearest" });
        return;
      }
      element.classList.remove("ac-active");
    });
  }
}

function getLastKeyword(text: string): string {
  const callMatch = text.match(/(\w+)\s*\([^)]*\)\s*$/);
  if (callMatch !== null) {
    return callMatch[1];
  }
  const tailMatch = text.match(/(\w+)\s*$/);
  return tailMatch?.[1] ?? "";
}

function isInsideArgs(text: string): boolean {
  let depth = 0;
  let inString = false;
  let stringChar = "";

  for (let index = text.length - 1; index >= 0; index -= 1) {
    const ch = text[index];
    if (inString) {
      if (ch === stringChar && (index === 0 || text[index - 1] !== "\\")) {
        inString = false;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      stringChar = ch;
      continue;
    }
    if (ch === ")") {
      depth += 1;
    }
    if (ch === "(") {
      if (depth === 0) {
        return true;
      }
      depth -= 1;
    }
    if (ch === "{" || ch === "}" || ch === "\n") {
      if (depth <= 0) {
        return false;
      }
    }
  }
  return false;
}