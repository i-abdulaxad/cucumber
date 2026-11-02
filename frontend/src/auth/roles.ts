// D5: the only module that compares roles. Roles come from auth-service's `myRole`, never from the token.
import type { components } from '../api/generated/auth-service';

export type OrgRole = components['schemas']['OrganizationRole'];
export type ProjectRole = components['schemas']['ProjectRole'];

const ORG_RANK: Record<OrgRole, number> = { OWNER: 4, ADMIN: 3, MEMBER: 2, READER: 1 };
const PROJECT_RANK: Record<ProjectRole, number> = { ADMIN: 4, MEMBER: 3, TESTER: 2, READER: 1 };

/** Query keys whose data carries `myRole`; a 403 refetches them (AC-9.1). */
export const ROLE_QUERY_KEYS = [['orgs'], ['projects']] as const;

export function orgRank(role: OrgRole): number {
  return ORG_RANK[role];
}

export function projectRank(role: ProjectRole): number {
  return PROJECT_RANK[role];
}

export function hasOrgRole(role: OrgRole | null | undefined, atLeast: OrgRole): boolean {
  return role != null && ORG_RANK[role] >= ORG_RANK[atLeast];
}

export function hasProjectRole(role: ProjectRole | null | undefined, atLeast: ProjectRole): boolean {
  return role != null && PROJECT_RANK[role] >= PROJECT_RANK[atLeast];
}
