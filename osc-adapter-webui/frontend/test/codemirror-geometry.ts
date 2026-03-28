type RectLike = {
  x: number;
  y: number;
  top: number;
  bottom: number;
  left: number;
  right: number;
  width: number;
  height: number;
  toJSON(): Omit<RectLike, "toJSON">;
};

const CHAR_WIDTH = 8;
const LINE_HEIGHT = 16;

/**
 * jsdom 上で CodeMirror が必要とする最小限の座標 API を補完する。
 */
export function installCodeMirrorGeometryMocks(): void {
  if (!hasRangeGeometry()) {
    Object.defineProperty(Range.prototype, "getClientRects", {
      configurable: true,
      value: function getClientRects(): RectLike[] {
        return [createRect(this.startContainer, this.startOffset, this.endOffset)];
      },
    });
  }

  if (!hasRangeBoundingRect()) {
    Object.defineProperty(Range.prototype, "getBoundingClientRect", {
      configurable: true,
      value: function getBoundingClientRect(): RectLike {
        return createRect(this.startContainer, this.startOffset, this.endOffset);
      },
    });
  }
}

function hasRangeGeometry(): boolean {
  return typeof Range.prototype.getClientRects === "function";
}

function hasRangeBoundingRect(): boolean {
  return typeof Range.prototype.getBoundingClientRect === "function";
}

function createRect(node: Node, startOffset: number, endOffset: number): RectLike {
  const lineIndex = resolveLineIndex(node);
  const safeStart = Math.max(0, startOffset);
  const safeEnd = Math.max(safeStart + 1, endOffset);
  const left = safeStart * CHAR_WIDTH;
  const width = Math.max(1, safeEnd - safeStart) * CHAR_WIDTH;
  const top = lineIndex * LINE_HEIGHT;

  return {
    x: left,
    y: top,
    top,
    bottom: top + LINE_HEIGHT,
    left,
    right: left + width,
    width,
    height: LINE_HEIGHT,
    toJSON() {
      return {
        x: this.x,
        y: this.y,
        top: this.top,
        bottom: this.bottom,
        left: this.left,
        right: this.right,
        width: this.width,
        height: this.height,
      };
    },
  };
}

function resolveLineIndex(node: Node): number {
  const element = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
  const line = element?.closest(".cm-line");
  if (line === null || line === undefined) {
    return 0;
  }

  const container = line.parentElement;
  if (container === null) {
    return 0;
  }

  return Array.from(container.querySelectorAll(".cm-line")).indexOf(line);
}
