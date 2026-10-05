import { describe, it, expect } from 'vitest'
import {
  ALL_SCOPES,
  READ_SCOPES,
  WRITE_SCOPES,
  scopeGroup,
  scopeI18nKey,
} from './scopes'
import en from '@/i18n/locales/en.json'

describe('scopeGroup', () => {
  it('classifies every :read scope as read', () => {
    for (const s of [
      'accounts:read',
      'transactions:read',
      'goals:read',
      'dashboard:read',
      'prices:read',
      'family:read',
      'analysis:read',
    ]) {
      expect(scopeGroup(s)).toBe('read')
    }
  })

  it('classifies every budget -read scope as read', () => {
    for (const s of [
      'budget:categories-read',
      'budget:rules-read',
      'budget:transactions-read',
      'budget:recurring-read',
      'budget:envelopes-read',
      'budget:dashboard-read',
    ]) {
      expect(scopeGroup(s)).toBe('read')
    }
  })

  it('classifies the oauth2 introspection scopes as read', () => {
    for (const s of ['oauth2:discover', 'oauth2:session-status']) {
      expect(scopeGroup(s)).toBe('read')
    }
  })

  it('classifies :write and :trigger scopes as write', () => {
    for (const s of ['accounts:write', 'transactions:write', 'goals:write', 'sync:trigger']) {
      expect(scopeGroup(s)).toBe('write')
    }
  })

  it('classifies budget -write scopes as write', () => {
    for (const s of [
      'budget:categories-write',
      'budget:rules-write',
      'budget:transactions-write',
      'budget:envelopes-write',
    ]) {
      expect(scopeGroup(s)).toBe('write')
    }
  })
})

describe('scopeI18nKey', () => {
  it('replaces the domain:action colon with an underscore', () => {
    expect(scopeI18nKey('accounts:read')).toBe('accounts_read')
    expect(scopeI18nKey('sync:trigger')).toBe('sync_trigger')
  })
})

describe('scope vocabulary', () => {
  // Guard: the UI must offer exactly the scopes the backend honours. If either side
  // drifts, key creation would 400 on an unknown scope — this fails loud instead.
  it('mirrors the backend allowlist exactly (com.picsou.mcp.Scopes.ALL)', () => {
    expect([...ALL_SCOPES].sort()).toEqual(
      [
        'accounts:read',
        'transactions:read',
        'goals:read',
        'dashboard:read',
        'prices:read',
        'family:read',
        'analysis:read',
        'budget:categories-read',
        'budget:rules-read',
        'budget:transactions-read',
        'budget:recurring-read',
        'budget:envelopes-read',
        'budget:dashboard-read',
        'oauth2:discover',
        'oauth2:session-status',
        'accounts:write',
        'transactions:write',
        'goals:write',
        'sync:trigger',
        'budget:categories-write',
        'budget:rules-write',
        'budget:transactions-write',
        'budget:envelopes-write',
      ].sort(),
    )
  })

  // Locale parity is checked elsewhere, so English having the entry covers every locale.
  it('gives every scope a label and description, so the consent screen never shows a raw key', () => {
    const labels: Record<string, { label?: string; desc?: string }> = en.accessKeys.scopes
    for (const s of ALL_SCOPES) {
      expect(labels[scopeI18nKey(s)]?.label, s).toBeTruthy()
      expect(labels[scopeI18nKey(s)]?.desc, s).toBeTruthy()
    }
  })

  it('partitions ALL_SCOPES into read and write with no overlap or omission', () => {
    expect([...READ_SCOPES, ...WRITE_SCOPES].sort()).toEqual([...ALL_SCOPES].sort())
    expect(READ_SCOPES.some((s) => WRITE_SCOPES.includes(s))).toBe(false)
  })

  // Guards the semantic split, not just the count: a scope landing in the wrong bucket
  // (e.g. a read scope classified as write) would pass the partition test above but is
  // exactly the bug this test would have caught.
  it('puts each scope in the correct bucket, not just a bucket', () => {
    expect([...READ_SCOPES].sort()).toEqual(
      [
        'accounts:read',
        'transactions:read',
        'goals:read',
        'dashboard:read',
        'prices:read',
        'family:read',
        'analysis:read',
        'budget:categories-read',
        'budget:rules-read',
        'budget:transactions-read',
        'budget:recurring-read',
        'budget:envelopes-read',
        'budget:dashboard-read',
        'oauth2:discover',
        'oauth2:session-status',
      ].sort(),
    )
    expect([...WRITE_SCOPES].sort()).toEqual(
      [
        'accounts:write',
        'transactions:write',
        'goals:write',
        'sync:trigger',
        'budget:categories-write',
        'budget:rules-write',
        'budget:transactions-write',
        'budget:envelopes-write',
      ].sort(),
    )
  })
})
