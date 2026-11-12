import raw from "@shared/catalog.json";

export interface Difficulty { code: string; label: string; rankPoints: number; exp: number; suggestedPoke: number }
export interface Rank { code: string; name: string; minPoints: number; maxDifficulty: string; rosterCap: number; bagCap: number }
export interface Dungeon { code: string; name: string; floors: number; direction: "B" | "F"; suggestedDifficulty: string }
export interface Item { code: string; name: string; category: string; refValue: number }
export interface Species { id: number; name: string; types: string[] }

export interface CatalogData {
  version: number;
  limits: { teamMembers: number; partySize: number; invitationDays: number; maxLevel: number };
  difficulties: Difficulty[];
  ranks: Rank[];
  missionKinds: string[];
  dungeons: Dungeon[];
  items: Item[];
  species: Species[];
}

export const catalog = raw as CatalogData;

const byCode = <T extends { code: string }>(list: T[]) => new Map(list.map((x) => [x.code, x]));
const difficulties = byCode(catalog.difficulties);
const dungeons = byCode(catalog.dungeons);
const items = byCode(catalog.items);
const ranks = byCode(catalog.ranks);
const species = new Map(catalog.species.map((s) => [s.id, s]));

export const difficulty = (code: string) => difficulties.get(code);
export const difficultyOrder = (code: string) => catalog.difficulties.findIndex((d) => d.code === code);
export const dungeon = (code: string) => dungeons.get(code);
export const item = (code: string) => items.get(code);
export const rank = (code: string) => ranks.get(code);
export const speciesById = (id: number) => species.get(id);

export function rankFor(points: number): Rank {
  let current = catalog.ranks[0];
  for (const r of catalog.ranks) if (points >= r.minPoints) current = r;
  return current;
}

export function nextRank(r: Rank): Rank | null {
  const i = catalog.ranks.indexOf(r);
  return i + 1 < catalog.ranks.length ? catalog.ranks[i + 1] : null;
}

export const floorLabel = (d: Dungeon, floor: number) => (d.direction === "B" ? `B${floor}F` : `${floor}F`);

export const itemName = (code: string) => item(code)?.name ?? code;
export const difficultyLabel = (code: string) => difficulty(code)?.label ?? code;
