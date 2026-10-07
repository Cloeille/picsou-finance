import { CreditCard } from "lucide-react"
import { useTranslation } from "react-i18next"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import {
  useAmexStatus,
  useClearAmexSession,
  useCompleteAmexAuth,
  useInitiateAmexAuth,
  useSyncAmex,
  useRecoverAmexHistory,
} from "@/features/sync/hooks"

interface AmexPanelProps {
  onConnected?: () => void
}

export function AmexPanel({ onConnected }: AmexPanelProps = {}) {
  const { t } = useTranslation()
  const recovery = useRecoverAmexHistory()
  return (
    <SidecarSessionPanel
      translationPrefix="sync.amex"
      fieldIdPrefix="amex"
      loginIcon={CreditCard}
      extraField={{
        id: "method",
        label: t("sync.amex.otpMethod"),
        defaultValue: "sms",
        options: [
          { value: "sms", label: t("sync.amex.otpMethodSms") },
          { value: "email", label: t("sync.amex.otpMethodEmail") },
        ],
      }}
      useStatus={useAmexStatus}
      useInitiateAuth={useInitiateAmexAuth}
      useCompleteAuth={useCompleteAmexAuth}
      useSync={useSyncAmex}
      useClearSession={useClearAmexSession}
      onConnected={onConnected}
      recoveryAction={{
        label: t("sync.amex.recoverHistory"),
        busyLabel: t("sync.amex.recoveringHistory"),
        description: t("sync.amex.historyLimit"),
        busy: recovery.isPending,
        onRun: () => recovery.mutate(),
      }}
    />
  )
}
