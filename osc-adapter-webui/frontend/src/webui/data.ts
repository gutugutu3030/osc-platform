export function readJsonScript<T>(id: string, fallbackValue: T): T {
  const element = document.getElementById(id);
  if (!(element instanceof HTMLScriptElement)) {
    return fallbackValue;
  }

  try {
    const parsed = JSON.parse(element.textContent ?? "null") as T | null;
    return parsed ?? fallbackValue;
  } catch (error) {
    console.error("Failed to parse JSON script:", id, error);
    return fallbackValue;
  }
}