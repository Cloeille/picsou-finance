import { AccountTypeBadge } from "picsou"

export function Types() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <AccountTypeBadge type="CHECKING" />
      <AccountTypeBadge type="SAVINGS" />
      <AccountTypeBadge type="PEA" />
      <AccountTypeBadge type="COMPTE_TITRES" />
      <AccountTypeBadge type="CRYPTO" />
      <AccountTypeBadge type="REAL_ESTATE" />
      <AccountTypeBadge type="LOAN" />
    </div>
  )
}
