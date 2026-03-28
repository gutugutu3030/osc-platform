export interface CompletionItem {
  label: string;
  insert: string;
  kind: string;
  detail: string;
}

export interface CompletionCatalog {
  [scope: string]: CompletionItem[];
}

export interface EditorContext {
  scope: string;
  inArgs: boolean;
}

export interface EditorLengthSpec {
  kind: string;
  size?: number;
  fieldName?: string;
}

export interface EditorTupleField {
  name: string;
  type: string;
}

export interface EditorArrayItem {
  kind: string;
  type?: string;
  fields?: EditorTupleField[];
}

export interface EditorArg {
  kind: string;
  name: string;
  type?: string;
  role?: string;
  length?: EditorLengthSpec;
  item?: EditorArrayItem;
}

export interface EditorMessage {
  path: string;
  name: string;
  description?: string;
  args?: EditorArg[];
}

export interface EditorBundle {
  name: string;
  description?: string;
  messageRefs?: string[];
}

export interface EditorSchema {
  messages?: EditorMessage[];
  bundles?: EditorBundle[];
}

export interface EvaluateResponse {
  success: boolean;
  error?: string;
  schema?: EditorSchema;
}