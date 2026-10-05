import {
  Building,
  Building2,
  House,
  LandPlot,
  SquareParking,
  Store,
  type LucideIcon,
} from 'lucide-react'
import type { PropertyKind } from '@/types/api'

/**
 * The mark that stands in for a property's logo, one per {@link PropertyKind}.
 *
 * A property has no provider to borrow a logo from — `Account.logoUrl` is a connector
 * concern and `PROVIDER_LOGOS` keys on `Account.provider`, which is null on every manual
 * account (see `docs/features/bank-logos.md`). What a property *does* have is a kind the
 * user already picked in the metadata form, so that is what identifies it on the card.
 *
 * These are lucide components rather than files under `public/`: they inherit `currentColor`,
 * so a single map serves both themes and the account color, where an SVG asset would need a
 * light and a dark variant per kind.
 *
 * Keyed on `RealEstateMetadata.propertyKind` — the backend's `PropertyKind.parse` of the
 * free-text `property_type` column — never on the raw string. That column predates the enum
 * and old rows may hold French labels or Finary's own vocabulary; parsing it here would mean
 * keeping a second alias table in step with the Java one.
 */
export const PROPERTY_KIND_ICONS: Record<PropertyKind, LucideIcon> = {
  HOUSE: House,
  APARTMENT: Building2,
  BUILDING: Building,
  LAND: LandPlot,
  PARKING: SquareParking,
  COMMERCIAL: Store,
}
