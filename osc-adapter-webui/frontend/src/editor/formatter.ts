export interface FormatResult {
  text: string;
  cursorPosition: number;
}

export function formatEditorText(text: string, cursorPosition: number): FormatResult {
  if (text.trim() === "") {
    return { text, cursorPosition };
  }

  const lines = text.split("\n");
  const result: string[] = [];
  let depth = 0;

  lines.forEach((line) => {
    const trimmed = line.trim();
    if (trimmed === "") {
      result.push("");
      return;
    }

    const leadingClose = trimmed[0] === "}";
    if (leadingClose && depth > 0) {
      depth -= 1;
    }

    result.push(`${"    ".repeat(depth)}${trimmed}`);

    let localInString = false;
    for (let index = 0; index < trimmed.length; index += 1) {
      const ch = trimmed[index];
      if (localInString) {
        if (ch === "\\") {
          index += 1;
          continue;
        }
        if (ch === '"') {
          localInString = false;
        }
        continue;
      }
      if (ch === '"') {
        localInString = true;
        continue;
      }
      if (ch === "{") {
        depth += 1;
      } else if (ch === "}" && !(leadingClose && index === 0)) {
        depth -= 1;
      }
    }
  });

  const beforeCursor = text.substring(0, cursorPosition);
  const cursorLine = beforeCursor.split("\n").length - 1;
  const cursorColumn = (beforeCursor.split("\n").pop() ?? "").length;

  const formattedText = result.join("\n");
  const formattedLines = formattedText.split("\n");
  let nextCursorPosition = 0;
  for (let lineIndex = 0; lineIndex < Math.min(cursorLine, formattedLines.length); lineIndex += 1) {
    nextCursorPosition += formattedLines[lineIndex].length + 1;
  }
  if (cursorLine < formattedLines.length) {
    nextCursorPosition += Math.min(cursorColumn, formattedLines[cursorLine].length);
  }

  return {
    text: formattedText,
    cursorPosition: Math.min(nextCursorPosition, formattedText.length),
  };
}