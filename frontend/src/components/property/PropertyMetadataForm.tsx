import { useState } from 'react'
import { useForm, useWatch, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { AddressAutocomplete } from './AddressAutocomplete'
import { parseAmount } from '@/lib/utils'
import { formatApiError } from '@/lib/errors'
import { useUpdateRealEstateMetadata } from '@/features/accounts/hooks'
import type { RealEstateMetadata, RealEstateMetadataRequest } from '@/types/api'

const toOptionalNumber = (v: unknown): number | undefined =>
  v === '' || v == null ? undefined : parseAmount(String(v))

const propertySchema = z.object({
  // Acquisition
  purchasePrice: z.number().min(0),
  purchaseDate: z.string().optional(),
  agencyFees: z.number().min(0).optional(),
  notaryFees: z.number().min(0).optional(),
  worksCost: z.number().min(0).optional(),

  // Classification
  propertyType: z.string().optional(),
  category: z.string().optional(),
  description: z.string().max(5000).optional(),

  // Address
  address: z.string().max(500).optional(),
  postalCode: z.string().max(10).optional(),
  city: z.string().max(120).optional(),

  // Characteristics
  surfaceArea: z.number().min(0).optional(),
  landArea: z.number().min(0).optional(),
  constructionYear: z.number().min(1000).max(2200).optional(),
  rooms: z.number().min(0).optional(),
  bedrooms: z.number().min(0).optional(),
  bathrooms: z.number().min(0).optional(),
  floorNumber: z.number().optional(),
  floorsTotal: z.number().min(0).optional(),
  garageCount: z.number().min(0).optional(),
  parkingCount: z.number().min(0).optional(),
  energyClass: z.string().optional(),

  rentalIncome: z.number().min(0).optional(),
})

type PropertyFormData = z.infer<typeof propertySchema>

const selectControlClassName =
  'flex h-10 w-full rounded-md border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]'

const PROPERTY_KINDS = ['HOUSE', 'APARTMENT', 'BUILDING', 'LAND', 'PARKING', 'COMMERCIAL'] as const
const CATEGORIES = ['PRIMARY_RESIDENCE', 'SECONDARY_RESIDENCE', 'RENTAL', 'LAND', 'OTHER'] as const
const ENERGY_CLASSES = ['A', 'B', 'C', 'D', 'E', 'F', 'G'] as const

interface PropertyMetadataFormProps {
  accountId: number
  metadata?: RealEstateMetadata
  onSaved?: () => void
}

export function PropertyMetadataForm({ accountId, metadata, onSaved }: PropertyMetadataFormProps) {
  const { t } = useTranslation()
  const update = useUpdateRealEstateMetadata()

  // Booleans live outside react-hook-form: Switch is not a native input, and threading each
  // one through a Controller adds noise for no gain on a form this wide.
  const [hasElevator, setHasElevator] = useState(metadata?.hasElevator ?? false)
  const [hasGarden, setHasGarden] = useState(metadata?.hasGarden ?? false)
  const [hasTerrace, setHasTerrace] = useState(metadata?.hasTerrace ?? false)
  const [hasBalcony, setHasBalcony] = useState(metadata?.hasBalcony ?? false)
  const [valuationMode, setValuationMode] = useState(metadata?.valuationMode ?? 'ESTIMATED')

  const { register, handleSubmit, control, setValue, formState } = useForm<PropertyFormData>({
    resolver: zodResolver(propertySchema),
    defaultValues: {
      purchasePrice: metadata?.purchasePrice ?? 0,
      purchaseDate: metadata?.purchaseDate ?? undefined,
      agencyFees: metadata?.agencyFees ?? undefined,
      notaryFees: metadata?.notaryFees ?? undefined,
      worksCost: metadata?.worksCost ?? undefined,
      propertyType: metadata?.propertyType ?? '',
      category: metadata?.category ?? '',
      description: metadata?.description ?? '',
      address: metadata?.address ?? '',
      postalCode: metadata?.postalCode ?? '',
      city: metadata?.city ?? '',
      surfaceArea: metadata?.surfaceArea ?? undefined,
      landArea: metadata?.landArea ?? undefined,
      constructionYear: metadata?.constructionYear ?? undefined,
      rooms: metadata?.rooms ?? undefined,
      bedrooms: metadata?.bedrooms ?? undefined,
      bathrooms: metadata?.bathrooms ?? undefined,
      floorNumber: metadata?.floorNumber ?? undefined,
      floorsTotal: metadata?.floorsTotal ?? undefined,
      garageCount: metadata?.garageCount ?? undefined,
      parkingCount: metadata?.parkingCount ?? undefined,
      energyClass: metadata?.energyClass ?? '',
      rentalIncome: metadata?.rentalIncome ?? undefined,
    },
  })

  // useWatch, not watch(): the latter returns a fresh function each render, which makes
  // React Compiler skip memoizing this component entirely (see docs/conventions/frontend.md).
  const selectedKind = useWatch({ control, name: 'propertyType' })
  const isApartment = selectedKind === 'APARTMENT'
  const isHouse = selectedKind === 'HOUSE'

  const submit = (data: PropertyFormData) => {
    const payload: RealEstateMetadataRequest = {
      ...data,
      propertyType: data.propertyType || null,
      category: (data.category || null) as RealEstateMetadataRequest['category'],
      energyClass: data.energyClass || null,
      description: data.description || null,
      address: data.address || null,
      postalCode: data.postalCode || null,
      city: data.city || null,
      country: 'FR',
      hasElevator,
      hasGarden,
      hasTerrace,
      hasBalcony,
      valuationMode,
    }
    update.mutate({ id: accountId, data: payload }, { onSuccess: () => onSaved?.() })
  }

  return (
    <form onSubmit={handleSubmit(submit)} className="space-y-6">
      <Section title={t('property.form.classification')}>
        <Field label={t('property.form.propertyType')} htmlFor="propertyType">
          <select id="propertyType" className={selectControlClassName} {...register('propertyType')}>
            <option value="">{t('property.form.choose')}</option>
            {PROPERTY_KINDS.map(kind => (
              <option key={kind} value={kind}>{t(`property.kind.${kind}`)}</option>
            ))}
          </select>
        </Field>

        <Field label={t('property.form.category')} htmlFor="category">
          <select id="category" className={selectControlClassName} {...register('category')}>
            <option value="">{t('property.form.choose')}</option>
            {CATEGORIES.map(category => (
              <option key={category} value={category}>{t(`property.category.${category}`)}</option>
            ))}
          </select>
        </Field>

        <Field label={t('property.form.description')} htmlFor="description" full>
          <textarea
            id="description"
            rows={2}
            className="flex w-full rounded-md border border-input bg-input/20 px-4 py-2 text-sm outline-none dark:bg-input/30"
            {...register('description')}
          />
        </Field>
      </Section>

      <Section title={t('property.form.address')}>
        <Field label={t('property.form.street')} htmlFor="address" full>
          <Controller
            name="address"
            control={control}
            render={({ field }) => (
              <AddressAutocomplete
                id="address"
                value={field.value ?? ''}
                onChange={field.onChange}
                placeholder={t('property.form.addressPlaceholder')}
                onSelect={suggestion => {
                  // Filling postcode and city from the same match keeps the three fields
                  // consistent -- the backend re-geocodes whenever any of them changes.
                  field.onChange(suggestion.label)
                  if (suggestion.postcode) setValue('postalCode', suggestion.postcode)
                  if (suggestion.city) setValue('city', suggestion.city)
                }}
              />
            )}
          />
        </Field>

        <Field label={t('property.form.postalCode')} htmlFor="postalCode">
          <Input id="postalCode" {...register('postalCode')} />
        </Field>
        <Field label={t('property.form.city')} htmlFor="city">
          <Input id="city" {...register('city')} />
        </Field>

        {metadata?.inseeCode && (
          <p className="col-span-full text-xs text-muted-foreground">
            {t('property.form.geocoded', { insee: metadata.inseeCode })}
          </p>
        )}
      </Section>

      <Section title={t('property.form.characteristics')}>
        <Field label={t('property.form.surfaceArea')} htmlFor="surfaceArea">
          <NumericInput id="surfaceArea" {...register('surfaceArea', { setValueAs: toOptionalNumber })} />
        </Field>
        {isHouse && (
          <Field label={t('property.form.landArea')} htmlFor="landArea">
            <NumericInput id="landArea" {...register('landArea', { setValueAs: toOptionalNumber })} />
          </Field>
        )}
        <Field label={t('property.form.rooms')} htmlFor="rooms">
          <NumericInput id="rooms" {...register('rooms', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.bedrooms')} htmlFor="bedrooms">
          <NumericInput id="bedrooms" {...register('bedrooms', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.bathrooms')} htmlFor="bathrooms">
          <NumericInput id="bathrooms" {...register('bathrooms', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.constructionYear')} htmlFor="constructionYear">
          <NumericInput id="constructionYear" {...register('constructionYear', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.energyClass')} htmlFor="energyClass">
          <select id="energyClass" className={selectControlClassName} {...register('energyClass')}>
            <option value="">{t('property.form.unknown')}</option>
            {ENERGY_CLASSES.map(cls => <option key={cls} value={cls}>{cls}</option>)}
          </select>
        </Field>
      </Section>

      <Section title={t('property.form.details')}>
        {isApartment && (
          <>
            <Field label={t('property.form.floorNumber')} htmlFor="floorNumber">
              <NumericInput id="floorNumber" {...register('floorNumber', { setValueAs: toOptionalNumber })} />
            </Field>
            <Field label={t('property.form.floorsTotal')} htmlFor="floorsTotal">
              <NumericInput id="floorsTotal" {...register('floorsTotal', { setValueAs: toOptionalNumber })} />
            </Field>
            <Toggle label={t('property.form.hasElevator')} checked={hasElevator} onChange={setHasElevator} />
          </>
        )}
        <Field label={t('property.form.garageCount')} htmlFor="garageCount">
          <NumericInput id="garageCount" {...register('garageCount', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.parkingCount')} htmlFor="parkingCount">
          <NumericInput id="parkingCount" {...register('parkingCount', { setValueAs: toOptionalNumber })} />
        </Field>
        <Toggle label={t('property.form.hasGarden')} checked={hasGarden} onChange={setHasGarden} />
        <Toggle label={t('property.form.hasTerrace')} checked={hasTerrace} onChange={setHasTerrace} />
        <Toggle label={t('property.form.hasBalcony')} checked={hasBalcony} onChange={setHasBalcony} />
      </Section>

      <Section title={t('property.form.acquisition')}>
        <Field label={t('property.form.purchasePrice')} htmlFor="purchasePrice">
          <NumericInput id="purchasePrice" {...register('purchasePrice', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.purchaseDate')} htmlFor="purchaseDate">
          <Controller
            name="purchaseDate"
            control={control}
            render={({ field }) => (
              <DateInput id="purchaseDate" value={field.value ?? ''} onChange={field.onChange} />
            )}
          />
        </Field>
        <Field label={t('property.form.agencyFees')} htmlFor="agencyFees">
          <NumericInput id="agencyFees" {...register('agencyFees', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.notaryFees')} htmlFor="notaryFees">
          <NumericInput id="notaryFees" {...register('notaryFees', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.worksCost')} htmlFor="worksCost">
          <NumericInput id="worksCost" {...register('worksCost', { setValueAs: toOptionalNumber })} />
        </Field>
        <Field label={t('property.form.rentalIncome')} htmlFor="rentalIncome">
          <NumericInput id="rentalIncome" {...register('rentalIncome', { setValueAs: toOptionalNumber })} />
        </Field>
      </Section>

      <Section title={t('property.form.valuationMode')}>
        <div className="col-span-full flex items-center justify-between gap-4 rounded-lg border p-3">
          <div>
            <p className="text-sm font-medium">{t('property.form.autoValuation')}</p>
            <p className="text-xs text-muted-foreground">{t('property.form.autoValuationHint')}</p>
          </div>
          <Switch
            checked={valuationMode === 'ESTIMATED'}
            onCheckedChange={checked => setValuationMode(checked ? 'ESTIMATED' : 'MANUAL')}
            aria-label={t('property.form.autoValuation')}
          />
        </div>
      </Section>

      {update.isError && (
        <p role="alert" className="text-sm text-destructive">
          {formatApiError(update.error, t, 'property.form.saveError')}
        </p>
      )}

      <div className="flex justify-end">
        <Button type="submit" disabled={update.isPending || formState.isSubmitting}>
          {update.isPending ? t('common.loading') : t('common.save')}
        </Button>
      </div>
    </form>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <fieldset className="space-y-3">
      <legend className="text-sm font-medium text-muted-foreground">{title}</legend>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">{children}</div>
    </fieldset>
  )
}

function Field({ label, htmlFor, children, full = false }: {
  label: string; htmlFor: string; children: React.ReactNode; full?: boolean
}) {
  return (
    <div className={`space-y-1.5 ${full ? 'sm:col-span-2' : ''}`}>
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  )
}

function Toggle({ label, checked, onChange }: {
  label: string; checked: boolean; onChange: (v: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border px-4 py-2">
      <span className="text-sm">{label}</span>
      <Switch checked={checked} onCheckedChange={onChange} aria-label={label} />
    </div>
  )
}
