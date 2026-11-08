import { Loader2 } from 'lucide-react'

export function SsoProgressScreen({ message }: { message: string }) {
  return (
    <div
      role="status"
      className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-background px-4 text-center"
    >
      <Loader2 className="size-6 animate-spin text-muted-foreground" aria-hidden="true" />
      <p className="text-base text-muted-foreground">{message}</p>
    </div>
  )
}
