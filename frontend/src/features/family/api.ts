import { api } from '@/lib/api-client'

export interface FamilyMemberItem {
  id: number
  displayName: string
  avatarColor: string
  managed: boolean
  hasLogin: boolean
  activated: boolean
  loginName: string | null
  mfaEnabled: boolean
  hasPassword: boolean
  ssoLinked: boolean
}

export interface FamilyDashboard {
  sharedAccounts: SharedAccountInfo[]
  sharedGoals: SharedGoalInfo[]
  totalSharedNetWorth: number
}

export interface SharedAccountInfo {
  id: number
  ownerName: string
  name: string
  type: string
  currency: string
  balance: number
  balanceEur: number
}

export interface SharedGoalInfo {
  id: number
  ownerName: string
  name: string
  targetAmount: number
  currentTotal: number
  contributions: ContributionInfo[]
}

export interface ContributionInfo {
  memberName: string
  amount: number
}

export interface SharingSettings {
  resourceType: string
  sharingLevel: 'ALL' | 'NONE' | 'MANUAL'
  sharedResourceIds: number[]
}

export const familyApi = {
  listMembers: () =>
    api.get<FamilyMemberItem[]>('/family/members').then(r => r.data),

  createMember: (data: { displayName: string; avatarColor?: string }) =>
    api.post<FamilyMemberItem>('/family/members', data).then(r => r.data),

  deleteMember: (id: number) =>
    api.delete(`/family/members/${id}`),

  generateActivationLink: (id: number) =>
    api.post<{ activationLink: string }>(`/family/members/${id}/activate`).then(r => r.data),

  resetMemberPassword: (id: number) =>
    api.post<{ resetLink: string }>(`/family/members/${id}/reset-password`).then(r => r.data),

  getDashboard: () =>
    api.get<FamilyDashboard>('/family/dashboard').then(r => r.data),

  getSharingSettings: (resourceType: string) =>
    api.get<SharingSettings>('/family/sharing', { params: { resourceType } }).then(r => r.data),

  updateSharingSettings: (data: { resourceType: string; sharingLevel: string; sharedResourceIds?: number[] }) =>
    api.put('/family/sharing', data),
}
