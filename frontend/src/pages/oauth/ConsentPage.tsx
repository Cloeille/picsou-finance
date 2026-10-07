import { useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { Check, Loader2, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { formatApiError } from '@/lib/errors'
import { oauthConsentApi } from '@/features/oauthConsent/api'
import { scopeGroup, scopeI18nKey } from '@/features/accessKeys/scopes'

// One accessible checkbox for a single requested scope. Unlike the access-key create dialog's
// `ScopeToggle` (a faked ARIA checkbox — no plain input backs it), this one wraps a REAL
// `<input type="checkbox" name="scope">` so the browser's native form submission does the
// "one repeated `scope` field per approved scope" work for us; nothing here needs to serialize
// the selection by hand.
function ScopeCheckbox({
  scope,
  checked,
  onToggle,
}: {
  scope: string
  checked: boolean
  onToggle: () => void
}) {
  const { t } = useTranslation()
  const k = scopeI18nKey(scope)
  return (
    <label
      className={cn(
        'flex w-full cursor-pointer flex-col items-start gap-0.5 rounded-lg border p-3 text-left transition-colors',
        checked ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted',
      )}
    >
      <span className="flex items-center gap-2 text-sm font-medium">
        <input
          type="checkbox"
          name="scope"
          value={scope}
          checked={checked}
          onChange={onToggle}
          className="sr-only"
        />
        <span
          className={cn(
            'flex size-4 shrink-0 items-center justify-center rounded border',
            checked ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/40',
          )}
          aria-hidden
        >
          {checked && <Check className="size-3" />}
        </span>
        {t(`accessKeys.scopes.${k}.label`)}
        <code className="text-[10px] font-normal text-muted-foreground">{scope}</code>
      </span>
      <span className="pl-6 text-xs text-muted-foreground">{t(`accessKeys.scopes.${k}.desc`)}</span>
    </label>
  )
}

export function ConsentPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const clientId = searchParams.get('client_id') ?? ''
  const scopeParam = searchParams.get('scope') ?? ''
  const state = searchParams.get('state') ?? ''

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['oauthConsentInfo', clientId, scopeParam, state],
    queryFn: () => oauthConsentApi.getConsentInfo({ clientId, scope: scopeParam, state }),
    enabled: !!clientId,
    retry: false,
  })

  // Every requested scope starts pre-approved (standard consent-screen UX) — the user opts OUT
  // of a scope by unchecking it, rather than having to opt into everything from scratch. Synced
  // during render (not via useEffect) the same way ConfirmDialog resets its own local state on
  // open — react-query keeps `data` referentially stable across renders until it actually
  // refetches, so this only re-syncs when a genuinely new result arrives.
  const [selectedScopes, setSelectedScopes] = useState<string[]>([])
  const [syncedData, setSyncedData] = useState(data)
  if (data && data !== syncedData) {
    setSyncedData(data)
    setSelectedScopes(data.requestedScopes)
  }

  const formRef = useRef<HTMLFormElement>(null)

  function toggleScope(scope: string) {
    setSelectedScopes((prev) =>
      prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope],
    )
  }

  // Approve: submit the form as-is — the currently checked `scope` inputs travel with it.
  function handleApprove() {
    formRef.current?.requestSubmit()
  }

  // Deny: submit the SAME form, but with every scope checkbox forced unchecked first, so no
  // `scope` field reaches `/oauth2/authorize` at all. Spring AS reads an authorize-consent POST
  // with zero granted scopes as a denial and returns `access_denied` to the client.
  function handleDeny() {
    const form = formRef.current
    if (!form) return
    form.querySelectorAll<HTMLInputElement>('input[name="scope"]').forEach((cb) => {
      cb.checked = false
    })
    form.requestSubmit()
  }

  const requestedScopes = data?.requestedScopes ?? []
  const readScopes = requestedScopes.filter((s) => scopeGroup(s) === 'read')
  const writeScopes = requestedScopes.filter((s) => scopeGroup(s) === 'write')

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <Card className="w-full max-w-2xl">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <ShieldCheck className="size-5 text-muted-foreground" />
            {data ? t('oauthConsent.title', { app: data.clientName }) : t('oauthConsent.titleGeneric')}
          </CardTitle>
          <CardDescription>{t('oauthConsent.description')}</CardDescription>
        </CardHeader>
        <CardContent>
          {!clientId ? (
            <p role="alert" className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {t('oauthConsent.missingParams')}
            </p>
          ) : isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />
              {t('oauthConsent.loading')}
            </div>
          ) : isError ? (
            <p role="alert" className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {formatApiError(error, t, 'oauthConsent.error')}
            </p>
          ) : (
            <form
              ref={formRef}
              method="post"
              action="/oauth2/authorize"
              noValidate
              data-testid="consent-form"
              className="space-y-5"
            >
              <input type="hidden" name="client_id" value={clientId} />
              <input type="hidden" name="state" value={state} />

              {requestedScopes.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('oauthConsent.noScopes')}</p>
              ) : (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">{t('oauthConsent.scopesIntro')}</p>
                  {readScopes.length > 0 && (
                    <div className="space-y-2">
                      <p className="text-xs font-medium text-muted-foreground">{t('accessKeys.groupRead')}</p>
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                        {readScopes.map((s) => (
                          <ScopeCheckbox
                            key={s}
                            scope={s}
                            checked={selectedScopes.includes(s)}
                            onToggle={() => toggleScope(s)}
                          />
                        ))}
                      </div>
                    </div>
                  )}
                  {writeScopes.length > 0 && (
                    <div className="space-y-2">
                      <p className="pt-1 text-xs font-medium text-muted-foreground">{t('accessKeys.groupWrite')}</p>
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                        {writeScopes.map((s) => (
                          <ScopeCheckbox
                            key={s}
                            scope={s}
                            checked={selectedScopes.includes(s)}
                            onToggle={() => toggleScope(s)}
                          />
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}

              <div className="flex flex-col gap-2 pt-2 sm:flex-row sm:justify-end">
                <Button type="button" variant="outline" onClick={handleDeny} className="w-full sm:w-auto">
                  {t('oauthConsent.deny')}
                </Button>
                <Button type="button" onClick={handleApprove} className="w-full sm:w-auto">
                  {t('oauthConsent.approve')}
                </Button>
              </div>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
