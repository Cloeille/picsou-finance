import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'

export function ErrorScreen({ icon, code, message }: { icon: React.ReactNode; code: string; message: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <div className="flex min-h-full flex-1 items-center justify-center px-4 py-12">
      <div className="flex max-w-sm flex-col items-center gap-4 text-center">
        {icon}
        <h1 className="text-2xl font-bold">{code}</h1>
        <p className="text-balance text-muted-foreground">{message}</p>
        <div className="flex flex-wrap justify-center gap-2">
          <Button variant="outline" onClick={() => navigate(-1)}>
            {t('common.back')}
          </Button>
          <Button asChild>
            <Link to="/">{t('error.backToDashboard')}</Link>
          </Button>
        </div>
      </div>
    </div>
  )
}
