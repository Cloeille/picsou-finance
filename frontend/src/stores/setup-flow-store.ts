import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'

export type IntegrationKey =
  | 'enablebanking'
  | 'boursobank'
  | 'boursedirect'
  | 'fortuneo'
  | 'traderepublic'
  | 'revolut'
  | 'finary'
  | 'crypto'

export const ALL_INTEGRATIONS: IntegrationKey[] = [
  'enablebanking',
  'boursobank',
  'boursedirect',
  'fortuneo',
  'traderepublic',
  'revolut',
  'finary',
  'crypto',
]

interface EnableBankingDraft {
  applicationId: string
  redirectUri: string
  publicKeyPem: string | null
  tested: boolean
}

interface SetupFlowState {
  /** Integrations the user ticked on the picker step. */
  selectedIntegrations: IntegrationKey[]
  /** Integrations whose sub-step is fully done (config written + test passed). */
  completedIntegrations: IntegrationKey[]
  /** In-progress EB config kept alive across the 5 substeps. */
  ebDraft: EnableBankingDraft
  /** The admin display name we just created — used on the Done screen. */
  adminDisplayName: string | null

  toggleIntegration: (key: IntegrationKey) => void
  setIntegrations: (keys: IntegrationKey[]) => void
  markIntegrationDone: (key: IntegrationKey) => void
  updateEbDraft: (patch: Partial<EnableBankingDraft>) => void
  setAdminDisplayName: (name: string) => void
  reset: () => void
}

const blankEbDraft: EnableBankingDraft = {
  applicationId: '',
  redirectUri: '',
  publicKeyPem: null,
  tested: false,
}

/**
 * Wizard navigation state — persisted to sessionStorage so a refresh
 * mid-wizard doesn't drop what the user just typed (EB paste of a 36-char
 * KEY_ID is easy to lose). sessionStorage scope, not localStorage: a new
 * tab session starts clean, and an unrelated user on the same machine
 * doesn't see the previous operator's half-filled form.
 */
export const useSetupFlowStore = create<SetupFlowState>()(
  persist(
    (set) => ({
      selectedIntegrations: [],
      completedIntegrations: [],
      ebDraft: blankEbDraft,
      adminDisplayName: null,

      toggleIntegration: (key) =>
        set((state) => ({
          selectedIntegrations: state.selectedIntegrations.includes(key)
            ? state.selectedIntegrations.filter((k) => k !== key)
            : [...state.selectedIntegrations, key],
        })),

      setIntegrations: (keys) => set({ selectedIntegrations: keys }),

      markIntegrationDone: (key) =>
        set((state) => ({
          completedIntegrations: state.completedIntegrations.includes(key)
            ? state.completedIntegrations
            : [...state.completedIntegrations, key],
        })),

      updateEbDraft: (patch) =>
        set((state) => ({ ebDraft: { ...state.ebDraft, ...patch } })),

      setAdminDisplayName: (name) => set({ adminDisplayName: name }),

      reset: () =>
        set({
          selectedIntegrations: [],
          completedIntegrations: [],
          ebDraft: blankEbDraft,
          adminDisplayName: null,
        }),
    }),
    {
      name: 'picsou_setup_flow',
      storage: createJSONStorage(() => sessionStorage),
    }
  )
)
