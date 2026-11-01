import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Input } from '@/components/ui/input'

interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  confirmLabel?: string
  cancelLabel?: string
  onConfirm: () => void
  loading?: boolean
  variant?: 'default' | 'destructive'
  /**
   * When set, the confirm button stays disabled until the user retypes this exact
   * text. Use for irreversible actions (e.g. deleting a member and all their data).
   */
  confirmPhrase?: string
  /**
   * A friendly, already-formatted error message to show inside the dialog (the
   * dialog stays open so the user can read it and retry). Pass the result of
   * {@link formatApiError}; the parent owns clearing it on close/success.
   */
  error?: string
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  loading,
  variant = 'destructive',
  confirmPhrase,
  error,
}: ConfirmDialogProps) {
  const { t } = useTranslation()
  const [typed, setTyped] = useState('')
  const [wasOpen, setWasOpen] = useState(open)

  // Clear any previous input each time the dialog (re)opens — adjusting state
  // during render rather than in an effect avoids a cascading-render lint error.
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) setTyped('')
  }

  const phraseRequired = !!confirmPhrase
  const confirmDisabled = loading || (phraseRequired && typed !== confirmPhrase)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {phraseRequired && (
          <div className="space-y-2">
            <p className="text-sm">
              {t('common.confirmTypePrompt', { phrase: confirmPhrase })}
            </p>
            <Input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={confirmPhrase}
              autoFocus
            />
          </div>
        )}
        {error && (
          <AlertBanner variant="bordered" role="alert">{error}</AlertBanner>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            {cancelLabel ?? t('common.cancel')}
          </Button>
          <Button variant={variant} onClick={onConfirm} disabled={confirmDisabled}>
            {confirmLabel ?? t('common.delete')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
