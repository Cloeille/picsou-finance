import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { DefaultError, QueryKey, UseMutationOptions, UseMutationResult } from '@tanstack/react-query'

type Keys = readonly QueryKey[]

type BaseOptions<TData, TVars> = Omit<UseMutationOptions<TData, DefaultError, TVars>, 'mutationFn'> & {
  mutationFn: (variables: TVars) => Promise<TData>
  /**
   * Hold the mutation open until the invalidated queries have refetched, so
   * `mutateAsync` resolves on fresh data. Off by default: the mutation settles
   * as soon as the server answered and the refetches run in the background.
   */
  awaitRefresh?: boolean
}

/** Refresh after a successful call (the default). */
type OnSuccessOptions<TData, TVars> = BaseOptions<TData, TVars> & {
  when?: 'success'
  /** Query keys to invalidate, or a function of the result and variables returning them. */
  invalidates?: Keys | ((data: TData, variables: TVars) => Keys)
  /** Cache entry seeded with the response (`setQueryData`) before the invalidation. */
  writesTo?: QueryKey
}

/** Refresh whether the call succeeded or failed. */
type OnSettledOptions<TData, TVars> = BaseOptions<TData, TVars> & {
  when: 'settled'
  invalidates?: Keys
}

type InvalidatingMutationOptions<TData, TVars> =
  | OnSuccessOptions<TData, TVars>
  | OnSettledOptions<TData, TVars>

const isThenable = (value: unknown): value is PromiseLike<unknown> =>
  typeof (value as PromiseLike<unknown> | undefined)?.then === 'function'

/**
 * `useMutation` that invalidates query keys once the call succeeds (or settles).
 * The caller's own `onSuccess` / `onSettled` still run first.
 */
export function useInvalidatingMutation<TData = unknown, TVars = void>(
  options: InvalidatingMutationOptions<TData, TVars>,
): UseMutationResult<TData, DefaultError, TVars> {
  const queryClient = useQueryClient()
  const {
    when = 'success',
    invalidates = [],
    writesTo,
    awaitRefresh = false,
    onSuccess,
    onSettled,
    ...rest
  } = options as Omit<OnSuccessOptions<TData, TVars>, 'when'> & { when?: 'success' | 'settled' }
  if (when === 'settled' && typeof invalidates === 'function') {
    throw new Error("useInvalidatingMutation: with when: 'settled', invalidates must be a list of keys")
  }

  const refresh = (own: unknown, keys: Keys) => {
    const pending: PromiseLike<unknown>[] = isThenable(own) ? [own] : []
    for (const queryKey of keys) {
      const refetch = queryClient.invalidateQueries({ queryKey })
      if (awaitRefresh) pending.push(refetch)
    }
    return pending.length > 0 ? Promise.all(pending) : undefined
  }

  return useMutation<TData, DefaultError, TVars>({
    ...rest,
    onSuccess: (data, variables, ...tail) => {
      const own = onSuccess?.(data, variables, ...tail)
      if (when === 'settled') return own
      if (writesTo) queryClient.setQueryData(writesTo, data)
      return refresh(own, typeof invalidates === 'function' ? invalidates(data, variables) : invalidates)
    },
    onSettled: (data, error, variables, ...tail) => {
      const own = onSettled?.(data, error, variables, ...tail)
      if (when !== 'settled') return own
      return refresh(own, invalidates as Keys)
    },
  })
}
