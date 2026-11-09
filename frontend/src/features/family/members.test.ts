import { describe, it, expect } from 'vitest'
import { selectSwitchableMembers } from './members'
import type { FamilyMemberItem } from './api'

function member(over: Partial<FamilyMemberItem>): FamilyMemberItem {
  return {
    id: 1,
    displayName: 'X',
    avatarColor: '#000',
    managed: true,
    hasLogin: false,
    activated: false,
    loginName: null,
    mfaEnabled: false,
    ...over,
  }
}

describe('selectSwitchableMembers', () => {
  it('includes managed members who have not activated their own login', () => {
    const child = member({ id: 2, managed: true, hasLogin: false, activated: false })
    const pendingLogin = member({ id: 3, managed: true, hasLogin: true, activated: false })
    expect(selectSwitchableMembers([child, pendingLogin])).toEqual([child, pendingLogin])
  })

  it('excludes independent members (own activated password)', () => {
    const independent = member({ id: 4, managed: true, hasLogin: true, activated: true })
    expect(selectSwitchableMembers([independent])).toEqual([])
  })

  it('excludes the admin / non-managed members', () => {
    const admin = member({ id: 1, managed: false, activated: true })
    expect(selectSwitchableMembers([admin])).toEqual([])
  })
})
