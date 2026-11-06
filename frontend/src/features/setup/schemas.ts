import { z } from 'zod'

/**
 * Client-side shape/rule validation for each wizard step. Rules mirror the
 * backend {@code @Valid} annotations so the UI can surface errors inline
 * before a round-trip; the backend remains the single source of truth on
 * mutations — clients can be tampered with.
 */

export const USERNAME_REGEX = /^[a-zA-Z0-9._-]+$/
export const AVATAR_COLOR_REGEX = /^#[0-9a-fA-F]{6}$/
export const UUID_SHAPE_REGEX = /^[0-9a-fA-F-]{32,36}$/
const ORIGIN_REGEX = /^https?:\/\/[A-Za-z0-9.-]+(:\d{1,5})?$/
// Redirect URIs accept a trailing path so http://host:8080/sync/callback
// validates, while http://host:8080 alone also passes for the CORS step.
const REDIRECT_URI_REGEX = /^https?:\/\/[A-Za-z0-9.-]+(:\d{1,5})?(\/.*)?$/

export const setupAdminSchema = z.object({
  username: z
    .string()
    .min(3, 'auth.validation.usernameTooShort')
    .max(50, 'auth.validation.usernameTooLong')
    .regex(USERNAME_REGEX, 'auth.validation.usernameFormat'),
  password: z
    .string()
    .min(8, 'auth.validation.passwordTooShort')
    .max(128, 'auth.validation.passwordTooLong'),
  displayName: z.string().max(80).optional().or(z.literal('')),
  avatarColor: z.string().regex(AVATAR_COLOR_REGEX, 'setup.admin.avatarColorInvalid').optional(),
})
export type SetupAdminFormValues = z.infer<typeof setupAdminSchema>

export const setupSecuritySchema = z.object({
  allowedOrigins: z
    .array(z.string().regex(ORIGIN_REGEX, 'setup.security.originFormat'))
    .min(1, 'setup.security.originRequired'),
  secureCookies: z.boolean(),
})
export type SetupSecurityFormValues = z.infer<typeof setupSecuritySchema>

export const enableBankingConfigSchema = z.object({
  applicationId: z.string().regex(UUID_SHAPE_REGEX, 'setup.enablebanking.appIdFormat'),
  redirectUri: z.string().regex(REDIRECT_URI_REGEX, 'setup.enablebanking.redirectFormat'),
})
export type EnableBankingConfigFormValues = z.infer<typeof enableBankingConfigSchema>
