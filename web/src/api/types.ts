// Response shapes of the Spring API. The in-browser engine returns the same shapes.

export type Role = "MEMBER" | "OFFICER" | "LEADER";
export type SplitMode = "EQUAL" | "BY_PARTY";
export type RecruitStatus = "AVAILABLE" | "ON_MISSION" | "RELEASED";
export type MissionStatus = "POSTED" | "PLANNED" | "DEPARTED" | "COMPLETED" | "FAILED" | "CANCELLED";
export type InvitationStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "REVOKED" | "EXPIRED";
export type Reason =
  | "DEPOSIT" | "DISCARD" | "CONTRIBUTION" | "PAYOUT" | "CASH_OUT" | "MISSION_PACK"
  | "MISSION_RETURN" | "ITEM_USED" | "ITEM_LOST" | "MISSION_REWARD";

export interface UserView { id: number; username: string; displayName: string; speciesId: number }
export interface AuthResponse { token: string; user: UserView }

export interface TeamSummary { id: number; name: string; rank: string; rankPoints: number; role: Role; memberCount: number }
export interface Me { user: UserView; teams: TeamSummary[]; pendingInvitations: number }

export interface TeamDetail {
  id: number; name: string; rank: string; rankName: string; rankPoints: number;
  nextRank: { code: string; name: string; minPoints: number } | null;
  maxDifficulty: string; rosterCap: number; bagCap: number;
  treasuryCutPct: number; splitMode: SplitMode;
  myMembershipId: number; myRole: Role; treasury: number;
  memberCount: number; recruitCount: number; createdAt: string;
}

export interface MemberView {
  membershipId: number; userId: number; username: string; displayName: string; speciesId: number; role: Role;
  joinedAt: string; wallet: number; recruits: number;
}

export interface Invitation {
  id: number; teamId: number; teamName: string; invitee: UserView; invitedBy: UserView; role: Role;
  status: InvitationStatus; createdAt: string; expiresAt: string;
}

export interface Recruit {
  id: number; speciesId: number; speciesName: string; types: string[]; nickname: string | null;
  level: number; exp: number; levelExp: number; nextLevelExp: number | null; status: RecruitStatus;
  recruitedBy: { membershipId: number; displayName: string } | null; recruitedAt: string; missionId: number | null;
}

export interface ItemStack { itemCode: string; quantity: number }

export interface PartySlot { recruitId: number; speciesId: number; name: string; level: number; recruitedBy: number | null }

export interface Mission {
  id: number; title: string; kind: string; dungeonCode: string; dungeonName: string; floor: number; floorLabel: string;
  difficulty: string; clientSpeciesId: number | null; clientName: string | null; rewardPoke: number;
  rewardItems: ItemStack[]; rankPoints: number; status: MissionStatus;
  leader: { membershipId: number; displayName: string; speciesId: number } | null; party: PartySlot[]; bag: ItemStack[];
  postedBy: string; postedAt: string; departedAt: string | null; closedAt: string | null; outcomeNote: string | null;
}

export interface PayoutPreview {
  rewardPoke: number; treasuryCutPct: number; splitMode: SplitMode; treasury: number;
  shares: { membershipId: number; displayName: string; speciesId: number; weight: number; amount: number }[];
  rankPoints: number; rankBefore: string; rankAfter: string; expPerRecruit: number;
  levelUps: { recruitId: number; name: string; from: number; to: number }[];
}

export interface StorageView {
  items: ItemStack[]; storageValue: number; treasury: number; myWallet: number;
  inTheField: { missionId: number; title: string; items: ItemStack[] }[];
}

export interface LedgerEntry {
  id: number; txn: string; accountKey: string; asset: string; delta: number; balanceAfter: number;
  reason: Reason; missionId: number | null; memo: string | null; actor: string | null; createdAt: string;
}

export interface Page<T> { items: T[]; page: number; size: number; total: number }

export interface Reconciliation { ok: boolean; entries: number; transactions: number; accounts: number; problems: string[] }

export interface TeamEvent { id: number; type: string; summary: string; actor: string | null; createdAt: string }

export interface Problem { status: number; detail?: string; code?: string }
