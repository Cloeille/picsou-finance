import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Eye, EyeSlash } from '@phosphor-icons/react'
import { Input } from '@/components/ui/input'

type SecretInputProps = Omit<React.ComponentProps<typeof Input>, 'type'>

/**
 * A password field with the show/hide toggle used by the login form and the desktop lock.
 * Autofill and spellcheck stay off: it holds secrets the browser must not keep.
 */
export function SecretInput({ className, ...props }: SecretInputProps) {
  const { t } = useTranslation()
  const [visible, setVisible] = useState(false)
  return (
    <div className="relative">
      <Input
        {...props}
        type={visible ? 'text' : 'password'}
        spellCheck={false}
        autoCapitalize="none"
        className={className ? `pr-11 ${className}` : 'pr-11'}
      />
      <button
        type="button"
        onClick={() => setVisible(v => !v)}
        className="absolute right-1 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        aria-label={visible ? t('auth.hidePassword') : t('auth.showPassword')}
        aria-pressed={visible}
      >
        {visible ? (
          <EyeSlash className="size-4" aria-hidden="true" />
        ) : (
          <Eye className="size-4" aria-hidden="true" />
        )}
      </button>
    </div>
  )
}
