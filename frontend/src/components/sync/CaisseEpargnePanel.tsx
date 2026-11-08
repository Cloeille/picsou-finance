import { BpceSessionPanel } from "@/components/sync/BpceSessionPanel"
import { caisseEpargneApi } from "@/features/sync/api"

interface CaisseEpargnePanelProps {
  onConnected?: () => void
}

/** Caisse d'Epargne online banking: identifier, the bank's keypad, then Sécur'Pass. */
export function CaisseEpargnePanel({ onConnected }: CaisseEpargnePanelProps = {}) {
  return (
    <BpceSessionPanel
      provider="caisseEpargne"
      api={caisseEpargneApi}
      translationPrefix="sync.caisseEpargne"
      fieldIdPrefix="caisse-epargne"
      onConnected={onConnected}
    />
  )
}
