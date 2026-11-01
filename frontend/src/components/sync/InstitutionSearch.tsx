import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from '@/components/shared/BankCountrySelect'
import { Landmark, Loader2, Search } from 'lucide-react'
import { extractErrorMessage, formatApiError } from '@/lib/errors'
import { useSearchInstitutions } from '@/features/sync/hooks'

interface InstitutionSearchProps {
  onConnect: (institutionId: string, institutionName: string) => void
  connecting: boolean
  /** `page` is the Sync tab (labelled, card rows); `modal` is the compact, scrollable add-account list. */
  layout: 'page' | 'modal'
}

function InstitutionLogo({ logoUrl }: { logoUrl?: string | null }) {
  return (
    <Avatar className="size-4 shrink-0 rounded-sm after:rounded-sm">
      {logoUrl && <AvatarImage src={logoUrl} alt="" className="rounded-sm object-contain" />}
      <AvatarFallback className="rounded-sm bg-transparent">
        <Landmark className="size-4 text-muted-foreground" />
      </AvatarFallback>
    </Avatar>
  )
}

export function InstitutionSearch({ onConnect, connecting, layout }: InstitutionSearchProps) {
  const { t } = useTranslation()
  const modal = layout === 'modal'
  const [searchQuery, setSearchQuery] = useState('')
  const [country, setCountry] = useState(DEFAULT_BANK_COUNTRY)

  const {
    data: institutions,
    isError: searchFailed,
    isLoading: searchLoading,
    error: searchError,
  } = useSearchInstitutions(searchQuery.trim(), country)

  const searchEnabled = searchQuery.trim().length >= 2

  function renderRow(inst: NonNullable<typeof institutions>[number]) {
    const proBadge = inst.psuType === 'business' && (
      <Badge variant="outline" title={t('sync.banks.proBadgeTitle')}>
        {t('sync.banks.proBadge')}
      </Badge>
    )
    const connect = (
      <Button
        size="sm"
        className="justify-self-end"
        onClick={() => onConnect(inst.id, inst.name)}
        disabled={connecting}
      >
        {t('sync.banks.connect')}
      </Button>
    )
    const country = <span className="justify-self-center text-xs text-muted-foreground">{inst.country}</span>

    if (modal) {
      return (
        <div
          key={inst.id}
          className="grid grid-cols-[minmax(0,1fr)_2.5rem_auto] items-center gap-4 rounded-lg border px-3 py-2"
        >
          <div className="flex min-w-0 items-center gap-2">
            <InstitutionLogo logoUrl={inst.logoUrl} />
            <span className="min-w-0 text-sm font-medium leading-5">{inst.name}</span>
            {proBadge}
          </div>
          {country}
          {connect}
        </div>
      )
    }
    return (
      <Card key={inst.id} size="sm">
        <CardContent className="grid grid-cols-[minmax(0,1fr)_2.5rem_auto] items-center gap-4 py-2">
          <div className="flex min-w-0 items-center gap-3">
            <Landmark className="size-5 shrink-0 text-muted-foreground" />
            <span className="min-w-0 text-sm font-medium leading-5">{inst.name}</span>
            {proBadge}
          </div>
          {country}
          {connect}
        </CardContent>
      </Card>
    )
  }

  return (
    <div className={modal ? 'space-y-4' : 'space-y-3'}>
      {!modal && <label className="text-sm font-medium">{t('sync.banks.search')}</label>}
      <div className="flex items-start gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('sync.banks.searchPlaceholder')}
            className="pl-10"
            autoFocus={modal}
          />
        </div>
        <BankCountrySelect value={country} onChange={setCountry} />
      </div>

      {searchLoading && (
        modal ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            {t('common.loading')}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
        )
      )}

      {searchEnabled && searchFailed && !searchLoading && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <span className="flex-1">
            {modal
              ? extractErrorMessage(searchError, t('sync.banks.searchError'))
              : formatApiError(searchError, t, 'sync.banks.searchError')}
          </span>
        </div>
      )}

      {searchEnabled && !searchFailed && institutions && institutions.length > 0 && (
        <div className={modal ? 'space-y-2 max-h-64 overflow-y-auto' : 'space-y-2'}>
          {institutions.map(renderRow)}
        </div>
      )}

      {searchEnabled && !searchFailed && institutions && institutions.length === 0 && !searchLoading && (
        <p className="text-sm text-muted-foreground">{t('sync.banks.noConnections')}</p>
      )}
    </div>
  )
}
