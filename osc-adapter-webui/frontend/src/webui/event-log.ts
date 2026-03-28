import { esc } from "../shared/html";
import type { RuntimeEventPayload } from "./types";

export class EventLogController {
  private readonly eventSource: EventSource;

  public constructor(private readonly container: HTMLElement) {
    this.eventSource = new EventSource("/api/events");
    this.eventSource.onmessage = (eventMessage) => {
      const event = this.parseEvent(eventMessage.data);
      if (event === null) {
        return;
      }
      this.handleEvent(event);
    };
    this.eventSource.onerror = () => {
      this.add("log-error", "Event stream disconnected, reconnecting...");
    };
  }

  public clear(): void {
    this.container.innerHTML = "";
  }

  public add(cssClass: string, text: string): void {
    const entry = document.createElement("div");
    entry.className = `log-entry ${cssClass}`;
    const time = new Date().toTimeString().substring(0, 8);
    entry.innerHTML = `<span class="log-time">${time}</span>${esc(text)}`;
    this.container.insertBefore(entry, this.container.firstChild);
  }

  private parseEvent(raw: string): RuntimeEventPayload | null {
    try {
      return JSON.parse(raw) as RuntimeEventPayload;
    } catch (_error) {
      return null;
    }
  }

  private handleEvent(event: RuntimeEventPayload): void {
    switch (event.type) {
      case "connected":
        this.add("log-conn", "Connected to event stream");
        break;
      case "received":
        this.add("log-recv", `recv ${event.path ?? "-"} ${JSON.stringify(event.args ?? [])}`);
        break;
      case "send_started":
        this.add(
          "log-send-start",
          `→ sending ${event.messageRef ?? "-"} to ${event.targetHost ?? "-"}:${event.targetPort ?? "-"}`,
        );
        break;
      case "send_succeeded":
        this.add(
          "log-send-ok",
          `✓ sent ${event.messageRef ?? "-"} to ${event.targetHost ?? "-"}:${event.targetPort ?? "-"}`,
        );
        break;
      case "send_failed":
        this.add("log-send-fail", `✗ failed ${event.messageRef ?? "-"}: ${event.error ?? "Unknown error"}`);
        break;
      case "validation_error":
        this.add("log-error", `validation error ${event.address ?? "-"}: ${event.reason ?? "Unknown reason"}`);
        break;
      case "transport_error":
        this.add("log-error", `transport error: ${event.message ?? "Unknown error"}`);
        break;
      case "mcp_request":
        this.add("log-mcp", `mcp request ${event.message ?? ""}`);
        break;
      case "mcp_success":
        this.add("log-mcp", `mcp success ${event.message ?? ""}`);
        break;
      case "mcp_failure":
        this.add("log-error", `mcp failure ${event.message ?? ""}`);
        break;
      default:
        break;
    }
  }
}