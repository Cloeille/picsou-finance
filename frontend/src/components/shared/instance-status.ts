import type { InstanceStatus } from '@/features/admin/api'

/** Equality key for the dismiss store: sorted `code:sidecar`, then the latest version when an update exists. */
export function instanceStatusFingerprint(status: Pick<InstanceStatus, 'issues' | 'update'>): string {
  const issues = status.issues
    .map((issue) => `${issue.code}:${issue.sidecar}`)
    .sort()
    .join(',')
  const version = status.update?.latestVersion
  if (issues && version) return `${issues}|${version}`
  return issues || version || ''
}
