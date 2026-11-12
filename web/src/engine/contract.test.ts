// Runs shared/contract/scenario.json against the in-browser engine. The Spring API runs the same file
// (api/src/test/java/.../ContractRunner.java), so both implementations answer the scenario identically.

import { describe, expect, test } from "vitest";
import scenario from "@shared/contract/scenario.json";
import { Engine } from "./engine";

interface Step {
  name: string;
  as?: string;
  method: string;
  path: string;
  body?: unknown;
  expect: { status: number; code?: string; json?: Record<string, unknown> };
  save?: Record<string, string>;
}

const PLACEHOLDER = /\{([A-Za-z0-9:]+)\}/g;

export function select(root: unknown, path: string): unknown {
  if (root == null) return undefined;
  const length = path.endsWith("#");
  const p = length ? path.slice(0, -1) : path;
  let node: any = root;
  for (const m of p.slice(1).matchAll(/\.([A-Za-z0-9_]+)|\[(\d+)\]/g)) {
    if (node == null) return undefined;
    node = m[1] != null ? node[m[1]] : node[Number(m[2])];
  }
  if (node === undefined) return undefined;
  return length ? (Array.isArray(node) ? node.length : Object.keys(node).length) : node;
}

describe("contract scenario on the browser engine", () => {
  const engine = new Engine();
  const saved = new Map<string, unknown>();

  const interpolate = (text: string) =>
    text.replace(PLACEHOLDER, (_, key) => {
      if (!saved.has(key)) throw new Error(`nothing saved as ${key}`);
      return String(saved.get(key));
    });

  const substitute = (node: unknown): unknown => {
    if (typeof node === "string") {
      const whole = /^\{([A-Za-z0-9:]+)\}$/.exec(node);
      return whole ? saved.get(whole[1]) : interpolate(node);
    }
    if (Array.isArray(node)) return node.map(substitute);
    if (node && typeof node === "object") {
      return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, substitute(v)]));
    }
    return node;
  };

  (scenario.steps as Step[]).forEach((step, i) => {
    test(`${String(i + 1).padStart(2, "0")} ${step.name}`, () => {
      const token = step.as ? (saved.get(`token:${step.as}`) as string) : null;
      const res = engine.handle(step.method, interpolate(step.path), step.body === undefined ? undefined : substitute(step.body), token);
      const text = JSON.stringify(res.body);
      expect(res.status, `status; body: ${text}`).toBe(step.expect.status);
      if (step.expect.code) expect((res.body as any)?.code, `error code; body: ${text}`).toBe(step.expect.code);
      for (const [path, value] of Object.entries(step.expect.json ?? {})) {
        expect(select(res.body, path), `${path}; body: ${text}`).toEqual(value);
      }
      for (const [key, path] of Object.entries(step.save ?? {})) {
        const v = select(res.body, path);
        if (v == null) throw new Error(`nothing at ${path} to save as ${key}; body: ${text}`);
        saved.set(key, v);
      }
    });
  });
});
