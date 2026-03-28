export interface MessageArg {
  name: string;
  kind: string;
  type: string;
  typeLabel: string;
  inputType?: string;
  placeholder?: string;
}

export interface MessageSpec {
  name: string;
  path: string;
  description?: string;
  args: MessageArg[];
}

export interface SchemaPayload {
  messages: MessageSpec[];
}

export interface UiConfig {
  mode?: string;
  allowSend?: boolean;
  defaultTargetHost?: string;
  defaultTargetPort?: number;
  initialMessageRef?: string | null;
  initialArgs?: Record<string, unknown>;
}

export interface RuntimeEventPayload {
  type: string;
  path?: string;
  args?: unknown;
  messageRef?: string;
  targetHost?: string;
  targetPort?: number;
  error?: string;
  reason?: string;
  address?: string;
  message?: string;
}