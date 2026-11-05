import { BpceSessionPanel, type BpceRegionOption } from "@/components/sync/BpceSessionPanel"
import { banquePopulaireApi } from "@/features/sync/api"
import type { BanquePopulaireRegion } from "@/types/api"

/** The regional banks the login page offers, by their names: proper nouns, the same in every language. */
const REGIONS = [
  { value: "ALSACE_LORRAINE_CHAMPAGNE", label: "Alsace Lorraine Champagne" },
  { value: "AQUITAINE_CENTRE_ATLANTIQUE", label: "Aquitaine Centre Atlantique" },
  { value: "AUVERGNE_RHONE_ALPES", label: "Auvergne Rhône-Alpes" },
  { value: "BOURGOGNE_FRANCHE_COMTE", label: "Bourgogne Franche-Comté" },
  { value: "BRED", label: "BRED" },
  { value: "GRAND_OUEST", label: "Grand Ouest" },
  { value: "MEDITERRANEE", label: "Méditerranée" },
  { value: "NORD", label: "Nord" },
  { value: "OCCITANE", label: "Occitane" },
  { value: "RIVES_DE_PARIS", label: "Rives de Paris" },
  { value: "SUD", label: "Sud" },
  { value: "VAL_DE_FRANCE", label: "Val de France" },
] as const satisfies readonly (BpceRegionOption & { value: BanquePopulaireRegion })[]

interface BanquePopulairePanelProps {
  onConnected?: () => void
}

/** Banque Populaire online banking: regional bank, identifier, the bank's keypad, then Sécur'Pass. */
export function BanquePopulairePanel({ onConnected }: BanquePopulairePanelProps = {}) {
  return (
    <BpceSessionPanel
      provider="banquePopulaire"
      api={banquePopulaireApi}
      translationPrefix="sync.banquePopulaire"
      fieldIdPrefix="banque-populaire"
      regions={REGIONS}
      alphanumericIdentifier
      onConnected={onConnected}
    />
  )
}
