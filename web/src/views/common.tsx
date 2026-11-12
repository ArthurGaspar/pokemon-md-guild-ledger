import { useId, useMemo, useState } from "react";
import { catalog, itemName, speciesById } from "../api/catalog";
import type { ItemStack } from "../api/types";
import { ItemGlyph, Portrait } from "../ui";

/** Free-text species field backed by a datalist; accepts a name or a dex number. */
export function SpeciesInput({ value, onChange, label, optional }: {
  value: string; onChange: (raw: string) => void; label: string; optional?: boolean;
}) {
  const id = useId();
  return (
    <label className="field">
      <span>{label}</span>
      <input type="text" list={id} value={value} onChange={(e) => onChange(e.target.value)} placeholder={optional ? "Optional" : "Name or dex number"} />
      <datalist id={id}>
        {catalog.species.map((s) => <option key={s.id} value={`${s.name} #${s.id}`} />)}
      </datalist>
    </label>
  );
}

/** The species Mystery Dungeon games let you start as, plus a few partners. */
export const STARTERS = [1, 4, 7, 25, 152, 155, 158, 252, 255, 258, 387, 390, 393, 52, 133, 300, 446, 447, 37, 231, 403, 104];

/** Starter grid with a free field for any other species in the catalog. */
export function StarterPicker({ value, onChange }: { value: number | null; onChange: (id: number) => void }) {
  const [other, setOther] = useState(value != null && !STARTERS.includes(value) ? `${speciesById(value)?.name} #${value}` : "");
  return (
    <div className="field">
      <span>Your Pokémon</span>
      <div className="starters" role="group" aria-label="Starter">
        {STARTERS.map((id) => (
          <button key={id} type="button" className="starter" aria-pressed={value === id} onClick={() => { setOther(""); onChange(id); }}>
            <Portrait speciesId={id} />
            {speciesById(id)?.name}
          </button>
        ))}
      </div>
      <SpeciesInput label="Or any other species" value={other} optional onChange={(raw) => {
        setOther(raw);
        const id = parseSpecies(raw);
        if (id != null) onChange(id);
      }} />
    </div>
  );
}

export function parseSpecies(raw: string): number | null {
  const s = raw.trim();
  if (!s) return null;
  const hash = /#(\d+)\s*$/.exec(s);
  if (hash) return speciesById(Number(hash[1])) ? Number(hash[1]) : null;
  if (/^\d+$/.test(s)) return speciesById(Number(s)) ? Number(s) : null;
  return catalog.species.find((x) => x.name.toLowerCase() === s.toLowerCase())?.id ?? null;
}

export function Items({ stacks, empty = "none" }: { stacks: ItemStack[]; empty?: string }) {
  if (stacks.length === 0) return <span className="faint">{empty}</span>;
  return (
    <span className="row" style={{ gap: 10, display: "inline-flex" }}>
      {stacks.map((s) => (
        <span key={s.itemCode} className="row nowrap" style={{ gap: 4, display: "inline-flex" }}>
          <ItemGlyph code={s.itemCode} /> {s.quantity} × {itemName(s.itemCode)}
        </span>
      ))}
    </span>
  );
}

/** Which attacking types the party's own types cover, as a quick sanity check when building a party. */
export function useTypeSpread(speciesIds: number[]) {
  return useMemo(() => {
    const types = new Map<string, number>();
    for (const id of speciesIds) for (const t of speciesById(id)?.types ?? []) types.set(t, (types.get(t) ?? 0) + 1);
    return [...types.entries()].sort((a, b) => b[1] - a[1]);
  }, [speciesIds.join(",")]); // eslint-disable-line react-hooks/exhaustive-deps
}
