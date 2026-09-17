import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageHeader } from '@/components/shared/PageHeader'
import { BankSyncTab } from './BankSyncTab'
import { CryptoExchangeTab } from './CryptoExchangeTab'
import { CryptoWalletTab } from './CryptoWalletTab'
import { TradeRepublicTab } from './TradeRepublicTab'
import { RevolutTab } from './RevolutTab'
import { IbkrTab } from './IbkrTab'
import { FinaryTab } from './FinaryTab'
import { BourseDirectTab } from './BourseDirectTab'
import { AccountsVisibilityTab } from './AccountsVisibilityTab'
import { DegiroTab } from './DegiroTab'
import { AmundiTab } from './AmundiTab'
import { BoursoTab } from './BoursoTab'
import { FortuneoTab } from './FortuneoTab'

export function SyncPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const defaultTab = searchParams.get('tab') ?? 'banks'

  return (
    <div className="space-y-6">
      <PageHeader title={t('sync.title')} />
      <Tabs defaultValue={defaultTab}>
        <TabsList>
          <TabsTrigger value="banks">{t('sync.banks.title')}</TabsTrigger>
          <TabsTrigger value="exchanges">{t('sync.exchanges.title')}</TabsTrigger>
          <TabsTrigger value="wallets">{t('sync.wallets.title')}</TabsTrigger>
          <TabsTrigger value="tr">{t('sync.tr.title')}</TabsTrigger>
          <TabsTrigger value="revolut">{t('sync.revolut.title')}</TabsTrigger>
          <TabsTrigger value="bourso">{t('sync.bourso.title')}</TabsTrigger>
          <TabsTrigger value="bourse-direct">{t('sync.bourseDirect.title')}</TabsTrigger>
          <TabsTrigger value="degiro">{t('sync.degiro.title')}</TabsTrigger>
          <TabsTrigger value="ibkr">{t('sync.ibkr.title')}</TabsTrigger>
          <TabsTrigger value="amundi">{t('sync.amundi.title')}</TabsTrigger>
          <TabsTrigger value="fortuneo">{t('sync.fortuneo.title')}</TabsTrigger>
          <TabsTrigger value="finary">{t('sync.finary.title')}</TabsTrigger>
          <TabsTrigger value="visibility">{t('sync.visibility.title')}</TabsTrigger>
        <TabsList className="w-full justify-start">
          <TabsTrigger value="banks" className="flex-none">{t('sync.banks.title')}</TabsTrigger>
          <TabsTrigger value="exchanges" className="flex-none">{t('sync.exchanges.title')}</TabsTrigger>
          <TabsTrigger value="wallets" className="flex-none">{t('sync.wallets.title')}</TabsTrigger>
          <TabsTrigger value="tr" className="flex-none">{t('sync.tr.title')}</TabsTrigger>
          <TabsTrigger value="bourso" className="flex-none">{t('sync.bourso.title')}</TabsTrigger>
          <TabsTrigger value="bourse-direct" className="flex-none">{t('sync.bourseDirect.title')}</TabsTrigger>
          <TabsTrigger value="degiro" className="flex-none">{t('sync.degiro.title')}</TabsTrigger>
          <TabsTrigger value="ibkr" className="flex-none">{t('sync.ibkr.title')}</TabsTrigger>
          <TabsTrigger value="amundi" className="flex-none">{t('sync.amundi.title')}</TabsTrigger>
          <TabsTrigger value="finary" className="flex-none">{t('sync.finary.title')}</TabsTrigger>
        </TabsList>
        <TabsContent value="banks" className="mt-6">
          <BankSyncTab />
        </TabsContent>
        <TabsContent value="exchanges" className="mt-6">
          <CryptoExchangeTab />
        </TabsContent>
        <TabsContent value="wallets" className="mt-6">
          <CryptoWalletTab />
        </TabsContent>
        <TabsContent value="tr" className="mt-6">
          <TradeRepublicTab />
        </TabsContent>
        <TabsContent value="revolut" className="mt-6">
          <RevolutTab />
        </TabsContent>
        <TabsContent value="bourso" className="mt-6">
          <BoursoTab />
        </TabsContent>
        <TabsContent value="bourse-direct" className="mt-6">
          <BourseDirectTab />
        </TabsContent>
        <TabsContent value="degiro" className="mt-6">
          <DegiroTab />
        </TabsContent>
        <TabsContent value="ibkr" className="mt-6">
          <IbkrTab />
        </TabsContent>
        <TabsContent value="amundi" className="mt-6">
          <AmundiTab />
        </TabsContent>
        <TabsContent value="fortuneo" className="mt-6">
          <FortuneoTab />
        </TabsContent>
        <TabsContent value="finary" className="mt-6">
          <FinaryTab />
        </TabsContent>
        <TabsContent value="visibility" className="mt-6">
          <AccountsVisibilityTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
