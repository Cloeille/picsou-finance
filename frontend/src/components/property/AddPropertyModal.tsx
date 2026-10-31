import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { AddressAutocomplete } from './AddressAutocomplete'
import { useCreateAccount, useUpdateRealEstateMetadata, useRefreshValuation } from '@/features/accounts/hooks'
import { formatApiError } from '@/lib/errors'
import { parseAmount } from '@/lib/utils'
import { PROPERTY_KIND_ICONS } from '@/lib/property-icons'
import type { PropertyCategory, PropertyKind, RealEstateMetadataRequest } from '@/types/api'

interface AddPropertyModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Ordered for the picker; the glyphs are shared with the account card so a property looks
 *  the same wherever it is shown. */
const KINDS: PropertyKind[] = ['HOUSE', 'APARTMENT', 'BUILDING', 'LAND', 'PARKING', 'COMMERCIAL']

const CATEGORIES: PropertyCategory[] = [
  'PRIMARY_RESIDENCE', 'SECONDARY_RESIDENCE', 'RENTAL', 'LAND', 'OTHER',
]

const selectControlClassName =
  'flex h-10 w-full rounded-md border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]'

const num = (v: string): number | undefined => (v.trim() === '' ? undefined : parseAmount(v))

/**
 * Guided flow for adding a property.
 *
 * <p>Exists because the generic "Manuel" path was the only way in, and nobody looks for their
 * house under "create an account manually". It also does in one pass what that path left the
 * user to discover: create the account, save the description, and run the first estimate.
 *
 * <p>Three steps, ordered so the estimate-critical fields come first — kind, living area and
 * address are what the valuation actually needs; everything after refines it.
 */
export function AddPropertyModal({ open, onOpenChange }: AddPropertyModalProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const createAccount = useCreateAccount()
  const updateMetadata = useUpdateRealEstateMetadata()
  const refreshValuation = useRefreshValuation()

  const [step, setStep] = useState(1)

  // Step 1 — identity
  const [kind, setKind] = useState<PropertyKind | ''>('')
  const [category, setCategory] = useState<PropertyCategory | ''>('')
  const [name, setName] = useState('')

  // Step 2 — where and what
  const [address, setAddress] = useState('')
  const [postalCode, setPostalCode] = useState('')
  const [city, setCity] = useState('')
  const [surfaceArea, setSurfaceArea] = useState('')
  const [landArea, setLandArea] = useState('')
  const [rooms, setRooms] = useState('')
  const [bedrooms, setBedrooms] = useState('')
  const [bathrooms, setBathrooms] = useState('')
  const [floorNumber, setFloorNumber] = useState('')
  const [hasElevator, setHasElevator] = useState(false)

  // Survives a failed submit: the account is created before its description is saved, so a
  // retry after the second call failed must reuse it. Without this the user gets a second
  // empty REAL_ESTATE account on every attempt, and no hint the first one exists.
  const [createdAccountId, setCreatedAccountId] = useState<number | null>(null)

  // Step 3 — what it cost
  const [purchasePrice, setPurchasePrice] = useState('')
  const [purchaseDate, setPurchaseDate] = useState('')
  const [notaryFees, setNotaryFees] = useState('')
  const [agencyFees, setAgencyFees] = useState('')
  const [worksCost, setWorksCost] = useState('')

  const isApartment = kind === 'APARTMENT'
  const isHouse = kind === 'HOUSE'
  const canEstimate = kind === 'HOUSE' || kind === 'APARTMENT'

  const step1Valid = kind !== '' && name.trim() !== ''
  const step2Valid = !canEstimate || (surfaceArea.trim() !== '' && address.trim() !== '')
  const step3Valid = purchasePrice.trim() !== ''

  const pending = createAccount.isPending || updateMetadata.isPending
  const error = createAccount.error ?? updateMetadata.error

  function reset() {
    setStep(1)
    setKind(''); setCategory(''); setName('')
    setAddress(''); setPostalCode(''); setCity('')
    setSurfaceArea(''); setLandArea(''); setRooms(''); setBedrooms(''); setBathrooms('')
    setFloorNumber(''); setHasElevator(false)
    setPurchasePrice(''); setPurchaseDate(''); setNotaryFees(''); setAgencyFees(''); setWorksCost('')
    setCreatedAccountId(null)
  }

  function close(next: boolean) {
    if (!next) reset()
    onOpenChange(next)
  }

  async function submit() {
    const price = num(purchasePrice) ?? 0
    const costBasis = price + (num(notaryFees) ?? 0) + (num(agencyFees) ?? 0) + (num(worksCost) ?? 0)

    let accountId = createdAccountId
    if (accountId == null) {
      const created = await createAccount.mutateAsync({
        name: name.trim(),
        type: 'REAL_ESTATE',
        currency: 'EUR',
        // Seeded from what the property cost, so it never shows 0 € and a 100% loss while
        // waiting for its first estimate. A successful estimate replaces it immediately.
        currentBalance: costBasis > 0 ? costBasis : undefined,
        isManual: true,
        color: '#a855f7',
      })
      accountId = created.id
      setCreatedAccountId(accountId)
    }

    const metadata: RealEstateMetadataRequest = {
      purchasePrice: price,
      purchaseDate: purchaseDate || null,
      notaryFees: num(notaryFees) ?? null,
      agencyFees: num(agencyFees) ?? null,
      worksCost: num(worksCost) ?? null,
      propertyType: kind || null,
      category: category || null,
      address: address.trim() || null,
      postalCode: postalCode.trim() || null,
      city: city.trim() || null,
      country: 'FR',
      surfaceArea: num(surfaceArea) ?? null,
      landArea: num(landArea) ?? null,
      rooms: num(rooms) ?? null,
      bedrooms: num(bedrooms) ?? null,
      bathrooms: num(bathrooms) ?? null,
      floorNumber: isApartment ? num(floorNumber) ?? null : null,
      hasElevator: isApartment ? hasElevator : null,
      valuationMode: 'ESTIMATED',
    }
    await updateMetadata.mutateAsync({ id: accountId, data: metadata })

    // Fire the first estimate without blocking the redirect: the detail page renders the
    // outcome, and a slow or unavailable source must not hold the dialog open.
    if (canEstimate) {
      refreshValuation.mutate(accountId)
    }

    close(false)
    navigate(`/accounts/${accountId}`)
  }

  /**
   * `submit` is wired to onClick, where a rejected promise escapes as an unhandled rejection.
   * Both mutations already surface their failure through `error` above, so there is nothing to
   * report here — this only keeps the rejection from leaving the handler.
   */
  function onSubmit() {
    void submit().catch(() => {})
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-w-xl sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('property.add.title')}</DialogTitle>
          <DialogDescription>{t(`property.add.step${step}Hint`)}</DialogDescription>
        </DialogHeader>

        <ol className="flex gap-2" aria-label={t('property.add.progress')}>
          {[1, 2, 3].map(n => (
            <li
              key={n}
              aria-current={n === step ? 'step' : undefined}
              className={`h-1 flex-1 rounded-full ${n <= step ? 'bg-primary' : 'bg-muted'}`}
            />
          ))}
        </ol>

        {step === 1 && (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>{t('property.form.propertyType')}</Label>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {KINDS.map(value => {
                  const Icon = PROPERTY_KIND_ICONS[value]
                  return (
                    <button
                      key={value}
                      type="button"
                      onClick={() => setKind(value)}
                      aria-pressed={kind === value}
                      className={`flex items-center gap-2 rounded-md border p-3 text-left text-sm transition-colors
                        ${kind === value ? 'border-primary bg-primary/5' : 'hover:bg-muted/40'}`}
                    >
                      <Icon className="size-4 shrink-0" />
                      {t(`property.kind.${value}`)}
                    </button>
                  )
                })}
              </div>
              {kind !== '' && !canEstimate && (
                <p className="text-xs text-muted-foreground">{t('property.add.notEstimable')}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="prop-category">{t('property.form.category')}</Label>
              <select
                id="prop-category"
                className={selectControlClassName}
                value={category}
                onChange={e => setCategory(e.target.value as PropertyCategory)}
              >
                <option value="">{t('property.form.choose')}</option>
                {CATEGORIES.map(c => (
                  <option key={c} value={c}>{t(`property.category.${c}`)}</option>
                ))}
              </select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="prop-name">{t('property.add.name')}</Label>
              <Input
                id="prop-name"
                value={name}
                placeholder={t('property.add.namePlaceholder')}
                onChange={e => setName(e.target.value)}
              />
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="prop-address">{t('property.form.street')}</Label>
              <AddressAutocomplete
                id="prop-address"
                value={address}
                onChange={setAddress}
                placeholder={t('property.form.addressPlaceholder')}
                onSelect={s => {
                  setAddress(s.label)
                  if (s.postcode) setPostalCode(s.postcode)
                  if (s.city) setCity(s.city)
                }}
              />
              <p className="text-xs text-muted-foreground">{t('property.add.addressHint')}</p>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label htmlFor="prop-postal">{t('property.form.postalCode')}</Label>
                <Input id="prop-postal" value={postalCode} onChange={e => setPostalCode(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-city">{t('property.form.city')}</Label>
                <Input id="prop-city" value={city} onChange={e => setCity(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-surface">{t('property.form.surfaceArea')}</Label>
                <NumericInput id="prop-surface" value={surfaceArea}
                  onChange={e => setSurfaceArea(e.target.value)} />
              </div>
              {isHouse && (
                <div className="space-y-2">
                  <Label htmlFor="prop-land">{t('property.form.landArea')}</Label>
                  <NumericInput id="prop-land" value={landArea}
                    onChange={e => setLandArea(e.target.value)} />
                </div>
              )}
              <div className="space-y-2">
                <Label htmlFor="prop-rooms">{t('property.form.rooms')}</Label>
                <NumericInput id="prop-rooms" value={rooms} onChange={e => setRooms(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-bedrooms">{t('property.form.bedrooms')}</Label>
                <NumericInput id="prop-bedrooms" value={bedrooms}
                  onChange={e => setBedrooms(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-bathrooms">{t('property.form.bathrooms')}</Label>
                <NumericInput id="prop-bathrooms" value={bathrooms}
                  onChange={e => setBathrooms(e.target.value)} />
              </div>
              {isApartment && (
                <div className="space-y-2">
                  <Label htmlFor="prop-floor">{t('property.form.floorNumber')}</Label>
                  <NumericInput id="prop-floor" value={floorNumber}
                    onChange={e => setFloorNumber(e.target.value)} />
                </div>
              )}
            </div>

            {isApartment && (
              <div className="flex items-center justify-between gap-3 rounded-lg border px-4 py-2">
                <span className="text-sm">{t('property.form.hasElevator')}</span>
                <Switch checked={hasElevator} onCheckedChange={setHasElevator}
                  aria-label={t('property.form.hasElevator')} />
              </div>
            )}
          </div>
        )}

        {step === 3 && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label htmlFor="prop-price">{t('property.form.purchasePrice')}</Label>
                <NumericInput id="prop-price" value={purchasePrice}
                  onChange={e => setPurchasePrice(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-date">{t('property.form.purchaseDate')}</Label>
                <DateInput id="prop-date" value={purchaseDate} onChange={setPurchaseDate} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-notary">{t('property.form.notaryFees')}</Label>
                <NumericInput id="prop-notary" value={notaryFees}
                  onChange={e => setNotaryFees(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-agency">{t('property.form.agencyFees')}</Label>
                <NumericInput id="prop-agency" value={agencyFees}
                  onChange={e => setAgencyFees(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prop-works">{t('property.form.worksCost')}</Label>
                <NumericInput id="prop-works" value={worksCost}
                  onChange={e => setWorksCost(e.target.value)} />
              </div>
            </div>
            <p className="text-xs text-muted-foreground">{t('property.add.feesHint')}</p>
          </div>
        )}

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(error, t, 'property.add.error')}
          </p>
        )}

        <DialogFooter className="gap-2 sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            onClick={() => (step === 1 ? close(false) : setStep(step - 1))}
            disabled={pending}
          >
            {step > 1 && <ArrowLeft className="mr-2 size-4" />}
            {step === 1 ? t('common.cancel') : t('common.back')}
          </Button>

          {step < 3 ? (
            <Button
              type="button"
              onClick={() => setStep(step + 1)}
              disabled={(step === 1 && !step1Valid) || (step === 2 && !step2Valid)}
            >
              {t('common.next')}
            </Button>
          ) : (
            <Button type="button" onClick={onSubmit} disabled={!step3Valid || pending}>
              {pending ? t('common.loading') : t('property.add.submit')}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
