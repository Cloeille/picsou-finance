import { useDarkTheme } from "@/hooks/use-dark-theme"
import { Toaster as Sonner, type ToasterProps } from "sonner"
import { CheckCircle, Info, Warning, XCircle, CircleNotch } from '@phosphor-icons/react'

const Toaster = ({ ...props }: ToasterProps) => {
  const dark = useDarkTheme()

  return (
    <Sonner
      theme={dark ? "dark" : "light"}
      className="toaster group"
      icons={{
        success: (
          <CheckCircle className="size-4" />
        ),
        info: (
          <Info className="size-4" />
        ),
        warning: (
          <Warning className="size-4" />
        ),
        error: (
          <XCircle className="size-4" />
        ),
        loading: (
          <CircleNotch className="size-4 animate-spin" />
        ),
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
          "--border-radius": "var(--radius)",
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: {
          toast: "cn-toast",
        },
      }}
      {...props}
    />
  )
}

export { Toaster }
