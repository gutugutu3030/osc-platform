export function getRequiredElement<T extends Element>(id: string): T {
  const element = document.getElementById(id);
  if (element === null) {
    throw new Error(`Missing required element #${id}`);
  }
  return element as unknown as T;
}

/**
 * CSS セレクターで必須要素を取得する。
 *
 * @param selector CSS セレクター文字列
 * @returns 見つかった要素
 * @throws Error 要素が見つからない場合
 */
export function getRequiredElementBySelector<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (element === null) {
    throw new Error(`Missing required element: ${selector}`);
  }
  return element;
}