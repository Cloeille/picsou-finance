/**
 * Lossless JSON: numbers keep their source text, so `1.50` and `1.5` stay distinct.
 * Goldens are written with `stringify` and read back with `parse`, never with JSON.parse.
 */
export class JNum {
  constructor(readonly raw: string) {}
}

export type Json = null | boolean | string | JNum | Json[] | { [key: string]: Json };

export function parse(text: string): Json {
  let i = 0;
  const fail = (msg: string): never => {
    throw new SyntaxError(`${msg} at offset ${i}`);
  };
  const ws = () => {
    while (i < text.length && " \t\n\r".includes(text[i]!)) i++;
  };
  const value = (): Json => {
    ws();
    const c = text[i];
    if (c === "{") {
      i++;
      const obj: { [key: string]: Json } = {};
      ws();
      if (text[i] === "}") {
        i++;
        return obj;
      }
      for (;;) {
        ws();
        if (text[i] !== '"') fail("expected key");
        const key = str();
        ws();
        if (text[i++] !== ":") fail("expected ':'");
        obj[key] = value();
        ws();
        const sep = text[i++];
        if (sep === "}") return obj;
        if (sep !== ",") fail("expected ',' or '}'");
      }
    }
    if (c === "[") {
      i++;
      const arr: Json[] = [];
      ws();
      if (text[i] === "]") {
        i++;
        return arr;
      }
      for (;;) {
        arr.push(value());
        ws();
        const sep = text[i++];
        if (sep === "]") return arr;
        if (sep !== ",") fail("expected ',' or ']'");
      }
    }
    if (c === '"') return str();
    if (text.startsWith("true", i)) {
      i += 4;
      return true;
    }
    if (text.startsWith("false", i)) {
      i += 5;
      return false;
    }
    if (text.startsWith("null", i)) {
      i += 4;
      return null;
    }
    const m = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i, i + 400));
    if (!m) return fail("unexpected token");
    i += m[0].length;
    return new JNum(m[0]);
  };
  const str = (): string => {
    const start = i;
    i++;
    while (i < text.length && text[i] !== '"') i += text[i] === "\\" ? 2 : 1;
    if (i >= text.length) fail("unterminated string");
    i++;
    return JSON.parse(text.slice(start, i)) as string;
  };
  const out = value();
  ws();
  if (i !== text.length) fail("trailing data");
  return out;
}

/** Canonical form: object keys sorted, array order kept, numbers as their source text. */
export function stringify(value: Json, indent = 2, depth = 0): string {
  const pad = " ".repeat(indent * (depth + 1));
  const end = " ".repeat(indent * depth);
  if (value === null || typeof value === "boolean") return String(value);
  if (typeof value === "string") return JSON.stringify(value);
  if (value instanceof JNum) return value.raw;
  // Harness metadata (a step's status) is a plain number; body numbers are always JNum.
  if (typeof value === "number") return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return `[\n${value.map((v) => pad + stringify(v, indent, depth + 1)).join(",\n")}\n${end}]`;
  }
  const keys = Object.keys(value).sort();
  if (keys.length === 0) return "{}";
  return `{\n${keys.map((k) => `${pad}${JSON.stringify(k)}: ${stringify(value[k]!, indent, depth + 1)}`).join(",\n")}\n${end}}`;
}

/** Plain JS view of a lossless value, for captures and scenario code. */
export function toPlain(value: Json): unknown {
  if (value instanceof JNum) return Number(value.raw);
  if (Array.isArray(value)) return value.map(toPlain);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, toPlain(v)]));
  }
  return value;
}

/** Decimal text reduced to sign, digits and exponent, so numerically equal values compare equal. */
export function decimalKey(raw: string): string {
  const m = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw);
  if (!m) return raw;
  const digits = (m[2]! + (m[3] ?? "")).replace(/^0+/, "");
  let exp = Number(m[4] ?? 0) - (m[3]?.length ?? 0);
  if (digits === "") return "0";
  let d = digits;
  while (d.endsWith("0")) {
    d = d.slice(0, -1);
    exp++;
  }
  return `${m[1]}${d}e${exp}`;
}
