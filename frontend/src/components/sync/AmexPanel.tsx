import { useMemo } from "react"
import { CreditCard } from '@phosphor-icons/react'
import { useTranslation } from "react-i18next"
import { SidecarSessionPanel } from "@/components/sync/SidecarSessionPanel"
import { amexApi } from "@/features/sync/api"
import { useRecoverAmexHistory } from "@/features/sync/hooks"

interface AmexPanelProps {
  onConnected?: () => void
}

export function AmexPanel({ onConnected }: AmexPanelProps = {}) {
  const { t } = useTranslation()
  const recovery = useRecoverAmexHistory()
  const webview = useMemo(
    () => ({
      start: () => amexApi.startWebview(),
      getStatus: (processId: string) => amexApi.getWebviewStatus(processId),
      cancel: (processId: string) => amexApi.cancelWebview(processId),
    }),
    [],
  )
  return (
    <SidecarSessionPanel
      provider="amex"
      translationPrefix="sync.amex"
      fieldIdPrefix="amex"
      loginIcon={CreditCard}
      webview={webview}
      extraField={{
        id: "method",
        label: t("sync.amex.otpMethod"),
        defaultValue: "sms",
        options: [
          { value: "sms", label: t("sync.amex.otpMethodSms") },
          { value: "email", label: t("sync.amex.otpMethodEmail") },
        ],
      }}
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
