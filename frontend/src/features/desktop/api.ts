import { api } from '@/lib/api-client'

export interface DesktopLockStatus {
  enabled: boolean
}

export type UpdateDesktopLockRequest =
  | { enabled: true; newPassword: string }
  | { enabled: false; currentPassword: string }

export const desktopApi = {
  getLockStatus: () =>
    api.get<DesktopLockStatus>('/desktop/lock').then((r) => r.data),

  updateLock: (body: UpdateDesktopLockRequest) =>
    api.put<DesktopLockStatus>('/desktop/lock', body).then((r) => r.data),
}
