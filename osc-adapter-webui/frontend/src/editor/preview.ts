import { esc } from "../shared/html";
import type { EditorArg, EditorArrayItem, EditorBundle, EditorMessage, EditorSchema, EvaluateResponse } from "./types";

export class SchemaPreviewController {
  private debounceTimer: number | null = null;

  public constructor(
    private readonly preview: HTMLElement,
    private readonly statusElement: HTMLElement,
  ) {}

  public triggerEvaluate(text: string): void {
    if (this.debounceTimer !== null) {
      window.clearTimeout(this.debounceTimer);
    }

    if (text.trim() === "") {
      this.showEmpty();
      return;
    }

    this.setStatus("loading", "評価中...");
    this.debounceTimer = window.setTimeout(() => {
      void this.evaluate(text);
    }, 600);
  }

  public showEmpty(): void {
    this.setStatus("idle", "入力待ち");
    this.preview.innerHTML =
      '<div class="empty-state"><div class="icon">📝</div>' +
      '<div>左のエディタに Kotlin DSL を入力してください</div>' +
      '<div style="margin-top:8px;font-size:11px;color:#475569;">入力するとリアルタイムでスキーマが可視化されます</div>' +
      '<button id="load-template-empty-btn" class="template-btn" style="margin-top:16px;">サンプルを挿入して始める</button>' +
      "</div>";
  }

  public showError(message: string): void {
    this.preview.innerHTML = `<div id="error-display" style="display:block"><pre>${esc(message)}</pre></div>`;
  }

  private async evaluate(dslText: string): Promise<void> {
    try {
      const response = await fetch("/api/evaluate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dsl: dslText }),
      });
      const data = (await response.json()) as EvaluateResponse;
      if (data.success && data.schema !== undefined) {
        this.setStatus("ok", "スキーマ有効 ✓");
        this.renderSchema(data.schema);
        return;
      }
      this.setStatus("err", "エラー ✗");
      this.showError(data.error ?? "Unknown error");
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.setStatus("err", "ネットワークエラー");
      this.showError(`サーバーに接続できません: ${message}`);
    }
  }

  private setStatus(type: string, text: string): void {
    this.statusElement.textContent = text;
    this.statusElement.className = `status-${type}`;
  }

  private renderSchema(schema: EditorSchema): void {
    let html = "";
    if ((schema.messages?.length ?? 0) > 0) {
      html += `<div class="section-title">Messages (${schema.messages?.length ?? 0})</div>`;
      schema.messages?.forEach((message) => {
        html += renderMessage(message);
      });
    }
    if ((schema.bundles?.length ?? 0) > 0) {
      html += `<div class="section-title" style="margin-top:16px;">Bundles (${schema.bundles?.length ?? 0})</div>`;
      schema.bundles?.forEach((bundle) => {
        html += renderBundle(bundle);
      });
    }
    if (html === "") {
      html = '<div class="empty-state"><div>スキーマにメッセージが定義されていません</div></div>';
    }
    this.preview.innerHTML = html;
  }
}

function renderMessage(message: EditorMessage): string {
  let html = '<div class="msg-card">';
  html += `<div class="msg-path">${esc(message.path)}</div>`;
  html += `<div class="msg-name">${esc(message.name)}</div>`;
  if (message.description !== undefined && message.description !== "") {
    html += `<div class="msg-desc">${esc(message.description)}</div>`;
  }
  if ((message.args?.length ?? 0) > 0) {
    html += '<div class="msg-args"><div class="msg-args-title">Arguments</div>';
    message.args?.forEach((arg) => {
      html += renderArg(arg);
    });
    html += "</div>";
  }
  html += "</div>";
  return html;
}

function renderBundle(bundle: EditorBundle): string {
  let html = '<div class="bundle-card">';
  html += `<div class="bundle-name">${esc(bundle.name)}</div>`;
  if (bundle.description !== undefined && bundle.description !== "") {
    html += `<div class="bundle-desc">${esc(bundle.description)}</div>`;
  }
  if ((bundle.messageRefs?.length ?? 0) > 0) {
    html += '<div class="bundle-refs">';
    bundle.messageRefs?.forEach((ref) => {
      html += `<div class="bundle-ref">${esc(ref)}</div>`;
    });
    html += "</div>";
  }
  html += "</div>";
  return html;
}

function renderArg(arg: EditorArg): string {
  if (arg.kind === "scalar") {
    let html = '<div class="arg-item">';
    html += `<span class="arg-name">${esc(arg.name)}</span>`;
    html += `<span class="arg-type">${esc(arg.type ?? "")}</span>`;
    if (arg.role !== undefined && arg.role !== "value") {
      html += `<span class="arg-role">${esc(arg.role)}</span>`;
    }
    html += "</div>";
    return html;
  }
  if (arg.kind === "array") {
    return renderArrayArg(arg);
  }
  return "";
}

function renderArrayArg(arg: EditorArg): string {
  let html = '<div class="array-item">';
  html += '<div class="array-header">';
  html += `<span class="arg-name">${esc(arg.name)}</span>`;
  html += '<span class="arg-type">array</span>';
  if (arg.length?.kind === "fixed") {
    html += `<span class="array-length">length: ${arg.length.size ?? 0}</span>`;
  } else if (arg.length?.kind === "fromField") {
    html += `<span class="array-length">length from: ${esc(arg.length.fieldName ?? "")}</span>`;
  }
  html += "</div>";
  if (arg.item !== undefined) {
    html += '<div class="array-children">';
    html += renderArrayItem(arg.item);
    html += "</div>";
  }
  html += "</div>";
  return html;
}

function renderArrayItem(item: EditorArrayItem): string {
  if (item.kind === "scalar") {
    return `<div class="arg-item"><span class="arg-type">${esc(item.type ?? "")}</span></div>`;
  }
  if (item.kind === "tuple") {
    return (item.fields ?? [])
      .map(
        (field) => `<div class="arg-item"><span class="arg-name">${esc(field.name)}</span><span class="arg-type">${esc(field.type)}</span></div>`,
      )
      .join("");
  }
  return "";
}