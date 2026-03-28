import { getRequiredElement } from "../shared/dom";
import { esc } from "../shared/html";
import { readJsonScript } from "./data";
import { EventLogController } from "./event-log";
import type { MessageArg, MessageSpec, SchemaPayload, UiConfig } from "./types";

const schema = readJsonScript<SchemaPayload>("webui-schema-data", { messages: [] });
const uiConfig = readJsonScript<UiConfig>("webui-config-data", {});

const schemaLabel = getRequiredElement<HTMLElement>("schema-label");
const messageItems = getRequiredElement<HTMLElement>("message-items");
const placeholder = getRequiredElement<HTMLElement>("placeholder");
const sendForm = getRequiredElement<HTMLElement>("send-form");
const formName = getRequiredElement<HTMLElement>("form-name");
const formPath = getRequiredElement<HTMLElement>("form-path");
const formDesc = getRequiredElement<HTMLElement>("form-desc");
const formFields = getRequiredElement<HTMLElement>("form-fields");
const targetRow = getRequiredElement<HTMLElement>("target-row");
const targetHost = getRequiredElement<HTMLInputElement>("target-host");
const targetPort = getRequiredElement<HTMLInputElement>("target-port");
const sendButton = getRequiredElement<HTMLButtonElement>("send-btn");
const sendResult = getRequiredElement<HTMLElement>("send-result");
const clearButton = getRequiredElement<HTMLButtonElement>("clear-btn");
const logEntries = getRequiredElement<HTMLElement>("log-entries");

const eventLog = new EventLogController(logEntries);

let selectedMessage: MessageSpec | null = null;

function init(): void {
  schemaLabel.textContent = `${schema.messages.length} messages`;
  targetHost.value = uiConfig.defaultTargetHost ?? "127.0.0.1";
  targetPort.value = String(uiConfig.defaultTargetPort ?? 9000);

  renderMessageList(schema.messages);
  applyInitialSelection();

  sendButton.addEventListener("click", () => {
    void sendMessage();
  });
  clearButton.addEventListener("click", () => {
    eventLog.clear();
  });
}

function renderMessageList(messages: MessageSpec[]): void {
  messageItems.innerHTML = "";
  messages.forEach((message) => {
    const item = document.createElement("div");
    item.className = "msg-item";
    item.dataset.messageName = message.name;
    item.dataset.messagePath = message.path;
    item.innerHTML = `<div class="msg-path">${esc(message.path)}</div><div class="msg-name">${esc(message.name)}</div>`;
    item.addEventListener("click", () => selectMessage(message, item));
    messageItems.appendChild(item);
  });
}

function applyInitialSelection(): void {
  const initialRef = uiConfig.initialMessageRef;
  if (initialRef === undefined || initialRef === null || initialRef === "") {
    return;
  }

  const items = Array.from(messageItems.querySelectorAll<HTMLElement>(".msg-item"));
  const initialItem = items.find(
    (item) => item.dataset.messageName === initialRef || item.dataset.messagePath === initialRef,
  );
  const message = schema.messages.find((candidate) => candidate.name === initialRef || candidate.path === initialRef);
  if (initialItem !== undefined && message !== undefined) {
    selectMessage(message, initialItem);
  }
}

function selectMessage(message: MessageSpec, element: HTMLElement): void {
  Array.from(messageItems.querySelectorAll<HTMLElement>(".msg-item")).forEach((item) => {
    item.classList.remove("active");
  });
  element.classList.add("active");
  selectedMessage = message;
  showForm(message);
}

function showForm(message: MessageSpec): void {
  placeholder.style.display = "none";
  sendForm.style.display = "block";
  formName.textContent = message.name;
  formPath.textContent = message.path;
  formDesc.textContent = message.description ?? "";
  sendResult.style.display = "none";
  targetRow.style.display = uiConfig.allowSend === true ? "flex" : "none";
  sendButton.style.display = uiConfig.allowSend === true ? "inline-block" : "none";

  formFields.innerHTML = "";
  message.args.forEach((arg) => {
    formFields.appendChild(buildFieldGroup(arg));
  });
}

function buildFieldGroup(arg: MessageArg): HTMLElement {
  const group = document.createElement("div");
  group.className = "field-group";

  const label = document.createElement("div");
  label.className = "field-label";
  label.textContent = arg.typeLabel;
  group.appendChild(label);

  const input = document.createElement("input");
  input.className = "field-input";
  input.type = arg.inputType ?? "text";
  input.id = `arg-${arg.name}`;
  input.placeholder = arg.placeholder ?? "";
  if (arg.inputType === "number") {
    input.step = "any";
  }

  const initialValue = formatInitialValue(arg.name);
  if (initialValue !== null) {
    input.value = initialValue;
  }

  group.appendChild(input);
  return group;
}

function formatInitialValue(argName: string): string | null {
  const initialArgs = uiConfig.initialArgs;
  if (initialArgs === undefined || !(argName in initialArgs)) {
    return null;
  }

  const value = initialArgs[argName];
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

async function sendMessage(): Promise<void> {
  if (selectedMessage === null || uiConfig.allowSend !== true) {
    return;
  }

  const args = Object.fromEntries(
    selectedMessage.args.map((arg) => {
      const input = document.getElementById(`arg-${arg.name}`);
      const value = input instanceof HTMLInputElement ? input.value : "";
      return [arg.name, parseArgValue(value, arg.type, arg.kind)];
    }),
  );

  try {
    const response = await fetch("/api/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        messageRef: selectedMessage.name,
        host: targetHost.value,
        port: Number.parseInt(targetPort.value, 10),
        args,
      }),
    });
    const data = (await response.json()) as { success?: boolean; error?: string };
    sendResult.style.display = "block";
    if (data.success === true) {
      sendResult.className = "result-ok";
      sendResult.textContent = "✓ Sent successfully";
      return;
    }

    sendResult.className = "result-err";
    sendResult.textContent = `✗ ${data.error ?? "Send failed"}`;
  } catch (_error) {
    sendResult.style.display = "block";
    sendResult.className = "result-err";
    sendResult.textContent = "✗ Network error";
  }
}

function parseArgValue(value: string, type: string, kind: string): unknown {
  if (kind === "array") {
    try {
      return JSON.parse(value) as unknown;
    } catch (_error) {
      return value;
    }
  }
  if (type === "int") {
    return Number.parseInt(value, 10);
  }
  if (type === "float") {
    return Number.parseFloat(value);
  }
  if (type === "bool") {
    return value === "true" || value === "1" || value === "yes";
  }
  return value;
}

init();