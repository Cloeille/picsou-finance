import { useTranslation } from 'react-i18next'
import { KeyRound } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { SetupSsoInfo } from '@/features/setup/api'
import { submitSsoStart } from '@/features/sso/start'

/** The admin step when the first admin must come from the SSO admin group. */
export function SetupAdminSso({ sso }: { sso: SetupSsoInfo }) {
  const { t } = useTranslation()

  if (!sso.enabled) {
    return (
      <p role="alert" className="mx-auto max-w-md rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
        {t('setup.admin.ssoMisconfigured')}
      </p>
    )
  }
  return (
    <div className="mx-auto w-full max-w-md space-y-4">
      <p className="text-center text-sm text-muted-foreground">
        {t('setup.admin.ssoHint', { provider: sso.providerName })}
      </p>
      {/* rounded-full like every other wizard CTA */}
      <Button
        type="button"
        onClick={() => submitSsoStart({ intent: 'setup' })}
        className="h-auto min-h-10 w-full whitespace-normal rounded-full py-2"
      >
        <KeyRound aria-hidden="true" />
        {t('setup.admin.ssoContinue', { provider: sso.providerName })}
      </Button>
    </div>
  )
}
