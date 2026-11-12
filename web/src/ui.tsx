import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { difficultyLabel, item, rank, speciesById } from "./api/catalog";
import { ApiError, type Api, type BrowserTransport } from "./api/client";
import type { Me, Role, TeamDetail } from "./api/types";

// ------------------------------------------------------------------ app context

export interface AppCtx {
  api: Api;
  browser: BrowserTransport | null;
  me: Me;
  team: TeamDetail;
  revision: number;
  bump: () => void;
  toast: (message: string) => void;
  go: (view: string, query?: Record<string, string | number>) => void;
}

export const Ctx = createContext<AppCtx | null>(null);

export function useApp() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useApp outside provider");
  return ctx;
}

export const can = (role: Role, min: Role) =>
  ["MEMBER", "OFFICER", "LEADER"].indexOf(role) >= ["MEMBER", "OFFICER", "LEADER"].indexOf(min);

/** Loads on mount and whenever the app revision changes (every successful mutation bumps it). */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const { revision } = useApp();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);
  useEffect(() => {
    const mine = ++seq.current;
    fn().then(
      (d) => { if (mine === seq.current) { setData(d); setError(null); } },
      (e) => { if (mine === seq.current) setError(e instanceof Error ? e.message : String(e)); },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [revision, ...deps]);
  return { data, error };
}

/** Runs a mutation, surfaces its error inline, and refreshes everything on success. */
export function useAction() {
  const { bump, toast } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async (fn: () => Promise<unknown>, done?: string) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      bump();
      if (done) toast(done);
      return true;
    } catch (e) {
      setError(e instanceof ApiError || e instanceof Error ? e.message : String(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, [bump, toast]);
  return { run, busy, error, setError };
}

// ------------------------------------------------------------------ formatting

export function timeAgo(iso: string | null | undefined, now = Date.now()) {
  if (!iso) return "";
  const s = Math.round((now - Date.parse(iso)) / 1000);
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 36) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 60) return `${d}d ago`;
  return new Date(iso).toLocaleDateString();
}

export const fmt = (n: number) => n.toLocaleString("en-US");

export function stamp(iso: string) {
  return new Date(iso).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

// ------------------------------------------------------------------ game visuals

export const TYPE_COLORS: Record<string, string> = {
  normal: "#a8a77a", fire: "#ee8130", water: "#6390f0", electric: "#e5bd1a", grass: "#6fb846", ice: "#79c9c5",
  fighting: "#c22e28", poison: "#a33ea1", ground: "#d4ac4f", flying: "#9a80ec", psychic: "#f95587", bug: "#a6b91a",
  rock: "#b6a136", ghost: "#735797", dragon: "#6f35fc", dark: "#705746", steel: "#9d9db8", fairy: "#d685ad",
};

export const DIFF_COLORS: Record<string, string> = {
  E: "#a7b0bb", D: "#74c577", C: "#5aaee6", B: "#e9c547", A: "#f39445", S: "#ef5a5f", STAR: "#bb7af2",
};

export const RANK_COLORS: Record<string, string> = {
  NORMAL: "#a7a7a7", BRONZE: "#c98549", SILVER: "#c5d0dc", GOLD: "#f2c94c", DIAMOND: "#7fe3f0",
  SUPER: "#ff8a65", ULTRA: "#c27ad4", HYPER: "#4fc3f7", MASTER: "#ffd54f",
};

export function TypeChip({ type }: { type: string }) {
  return <span className="chip type-chip" style={{ background: TYPE_COLORS[type] }}>{type}</span>;
}

// Black/White pixel sprites from the PokeAPI sprites repository, served by jsDelivr. If one fails to load the
// type-tinted card shows the species initial instead.
const spriteUrl = (id: number) =>
  `https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/versions/generation-v/black-white/${id}.png`;
const failedSprites = new Set<number>();

export function Portrait({ speciesId, small, large, label }: { speciesId: number; small?: boolean; large?: boolean; label?: string }) {
  const s = speciesById(speciesId);
  const [a, b] = s?.types ?? ["normal"];
  const [failed, setFailed] = useState(failedSprites.has(speciesId));
  return (
    <span
      className={`portrait${small ? " small" : ""}${large ? " large" : ""}`}
      style={{ ["--a" as string]: TYPE_COLORS[a], ["--b" as string]: TYPE_COLORS[b ?? a] }}
      title={label ?? s?.name}
    >
      {failed ? (s?.name ?? "?")[0] : (
        <img src={spriteUrl(speciesId)} alt={label ?? s?.name ?? ""} loading="lazy" decoding="async"
          onError={() => { failedSprites.add(speciesId); setFailed(true); }} />
      )}
    </span>
  );
}

export function Diff({ code, small }: { code: string; small?: boolean }) {
  return (
    <span className={`diff${small ? " small" : ""}`} style={{ ["--d" as string]: DIFF_COLORS[code] }} title={`Difficulty ${difficultyLabel(code)}`}>
      {difficultyLabel(code)}
    </span>
  );
}

export function RankEmblem({ code }: { code: string }) {
  return <span className="rank-emblem" style={{ ["--r" as string]: RANK_COLORS[code] }}>{rank(code)?.name ?? code}</span>;
}

export function Poke({ n, signed }: { n: number; signed?: boolean }) {
  return <span className="poke num">{signed && n > 0 ? "+" : ""}{fmt(n)}</span>;
}

const GLYPH_VARIANT: Record<string, string> = {
  "sitrus-berry": "gold", "pecha-berry": "pink", "chesto-berry": "violet", "blast-seed": "hot",
  "gold-thorn": "gold", gravelerock: "rock",
};

export function ItemGlyph({ code }: { code: string }) {
  const it = item(code);
  return <span className={`glyph ${it?.category ?? ""} ${GLYPH_VARIANT[code] ?? ""}`} aria-hidden />;
}

export function Stepper({ value, min = 0, max, onChange, label }: { value: number; min?: number; max: number; onChange: (v: number) => void; label: string }) {
  return (
    <span className="stepper" role="group" aria-label={label}>
      <button type="button" onClick={() => onChange(value - 1)} disabled={value <= min} aria-label={`Fewer ${label}`}>−</button>
      <output>{value}</output>
      <button type="button" onClick={() => onChange(value + 1)} disabled={value >= max} aria-label={`More ${label}`}>+</button>
    </span>
  );
}

export function ErrorLine({ error }: { error: string | null | undefined }) {
  return error ? <div className="error-line" role="alert">{error}</div> : null;
}

export function Seg<T extends string>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; label: string;
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}

export function Bar({ value, max, gold }: { value: number; max: number; gold?: boolean }) {
  const pct = max <= 0 ? 0 : Math.max(0, Math.min(100, (value / max) * 100));
  return <div className={`bar${gold ? " gold" : ""}`} role="presentation"><i style={{ width: `${pct}%` }} /></div>;
}
