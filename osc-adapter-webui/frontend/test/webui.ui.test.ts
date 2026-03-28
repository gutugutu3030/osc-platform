import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

class FakeEventSource {
  public static instances: FakeEventSource[] = [];
  public onmessage: ((event: MessageEvent<string>) => void) | null = null;
  public onerror: ((event: Event) => void) | null = null;

  public constructor(public readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  public emit(payload: unknown): void {
    this.onmessage?.(new MessageEvent("message", { data: JSON.stringify(payload) }));
  }

  public fail(): void {
    this.onerror?.(new Event("error"));
  }

  public close(): void {}
}

describe("webui main", () => {
  beforeEach(() => {
    vi.resetModules();
    FakeEventSource.instances = [];
    document.body.innerHTML = `
      <header>
        <p id="schema-label"></p>
      </header>
      <div id="message-items"></div>
      <div id="placeholder"></div>
      <div id="send-form" style="display:none">
        <h2 id="form-name"></h2>
        <div id="form-path"></div>
        <div id="form-desc"></div>
        <div id="form-fields"></div>
        <div id="target-row"></div>
        <input id="target-host" />
        <input id="target-port" />
        <button id="send-btn">Send</button>
        <div id="send-result"></div>
      </div>
      <button id="clear-btn">Clear</button>
      <div id="log-entries"></div>
      <script id="webui-schema-data" type="application/json">{"messages":[{"name":"light.color","path":"/light/color","description":"RGB","args":[{"name":"r","kind":"scalar","type":"int","typeLabel":"r: int","inputType":"number","placeholder":"0"},{"name":"enabled","kind":"scalar","type":"bool","typeLabel":"enabled: bool","inputType":"text","placeholder":"true / false"}]}]}</script>
      <script id="webui-config-data" type="application/json">{"allowSend":true,"defaultTargetHost":"127.0.0.2","defaultTargetPort":9100,"initialMessageRef":"light.color","initialArgs":{"r":12,"enabled":"yes"}}</script>
    `;
    vi.stubGlobal("EventSource", FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.body.innerHTML = "";
  });

  it("メッセージ選択と送信ボタン操作で fetch に正しい payload を渡す", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      json: async () => ({ success: true }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await import("../src/webui/main");

    expect(document.getElementById("schema-label")?.textContent).toContain("1 messages");
    expect((document.getElementById("target-host") as HTMLInputElement).value).toBe("127.0.0.2");
    expect((document.getElementById("arg-r") as HTMLInputElement).value).toBe("12");

    (document.getElementById("arg-r") as HTMLInputElement).value = "33";
    (document.getElementById("arg-enabled") as HTMLInputElement).value = "true";
    (document.getElementById("send-btn") as HTMLButtonElement).click();
    await flushAsyncWork();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/send",
      expect.objectContaining({
        method: "POST",
        headers: { "Content-Type": "application/json" },
      }),
    );

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      messageRef: "light.color",
      host: "127.0.0.2",
      port: 9100,
      args: { r: 33, enabled: true },
    });
    expect(document.getElementById("send-result")?.textContent).toContain("Sent successfully");
  });

  it("EventSource で表示したログを Clear ボタンで消せる", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        json: async () => ({ success: true }),
      }),
    );

    await import("../src/webui/main");

    expect(FakeEventSource.instances).toHaveLength(1);
    FakeEventSource.instances[0].emit({ type: "received", path: "/light/color", args: [1, 2, 3] });
    expect(document.getElementById("log-entries")?.textContent).toContain("recv /light/color [1,2,3]");

    (document.getElementById("clear-btn") as HTMLButtonElement).click();
    expect(document.getElementById("log-entries")?.textContent).toBe("");
  });
});

async function flushAsyncWork(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}