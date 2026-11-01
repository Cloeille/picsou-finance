import { useState } from 'react'
import { useNavigate } from 'react-router'
import { EBStep1Explain } from './enablebanking/EBStep1Explain'
import { EBStep2Credentials } from './enablebanking/EBStep2Credentials'
import { EBStep3Keypair } from './enablebanking/EBStep3Keypair'
import { EBStep4Redirect } from './enablebanking/EBStep4Redirect'
import { EBStep5Test } from './enablebanking/EBStep5Test'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

type SubstepIdx = 1 | 2 | 3 | 4 | 5

/**
 * Single-page container for the 5-substep Enable Banking flow. We keep
 * substep state here (not in the Zustand store) because it's inherently
 * ephemeral — refreshing mid-EB rewinds to step 1, which is the right
 * behaviour: the store already remembers the credentials/public-key draft.
 */
export function SetupStepEnableBanking() {
  const navigate = useNavigate()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)

  const [substep, setSubstep] = useState<SubstepIdx>(1)

  const goNext = () => {
    if (substep < 5) {
      setSubstep(((substep + 1) as SubstepIdx))
    } else {
      navigate(nextIntegrationRoute('enablebanking', selected))
    }
  }

  const goBack = () => {
    if (substep > 1) setSubstep(((substep - 1) as SubstepIdx))
  }

  return (
    <div className="space-y-8">
      {substep === 1 && <EBStep1Explain onNext={goNext} />}
      {substep === 2 && <EBStep4Redirect onNext={goNext} onBack={goBack} />}
      {substep === 3 && <EBStep2Credentials onNext={goNext} onBack={goBack} />}
      {substep === 4 && <EBStep3Keypair onNext={goNext} onBack={goBack} />}
      {substep === 5 && <EBStep5Test onBack={goBack} />}
    </div>
  )
}
