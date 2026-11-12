import { expect, test } from "vitest";
import { Engine } from "./engine";
import { seed } from "./seed";

test("demo data builds through the routes and reconciles", () => {
  const engine = new Engine();
  const { teamId } = seed(engine, Date.parse("2026-11-12T12:00:00Z"));
  const report = engine.reconcile(teamId);
  expect(report.problems).toEqual([]);
  expect(report.entries).toBeGreaterThan(100);

  const team = engine.state.teams.find((t) => t.id === teamId)!;
  expect(team.rankPoints).toBeGreaterThanOrEqual(400); // Silver, so B jobs are on the board
  const statuses = engine.state.missions.filter((m) => m.teamId === teamId).map((m) => m.status);
  expect(statuses.filter((s) => s === "DEPARTED")).toHaveLength(2);
  expect(statuses.filter((s) => s === "PLANNED")).toHaveLength(1);
  expect(statuses.filter((s) => s === "POSTED")).toHaveLength(3);
  expect(engine.state.sessions).toEqual({});
});

test("nothing in the demo history is dated in the future", () => {
  const engine = new Engine();
  const end = Date.parse("2026-11-12T12:00:00Z");
  seed(engine, end);
  const latest = Math.max(...engine.state.events.map((e) => Date.parse(e.createdAt)));
  expect(latest).toBeLessThanOrEqual(end);
});
