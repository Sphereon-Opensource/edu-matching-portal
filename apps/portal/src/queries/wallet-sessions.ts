import { useQuery } from '@tanstack/react-query'

export function useWalletSessionStatus(sessionId: string | null, enabled: boolean = false) {
  return useQuery({
    queryKey: ['wallet-session-status', sessionId],
    queryFn: async () => {
      if (!sessionId) throw new Error('No session ID')
      const res = await fetch(`/api/wallet/sessions/${sessionId}/status`)
      if (!res.ok) throw new Error('Failed to fetch status')
      return res.json()
    },
    enabled: enabled && !!sessionId,
    retry: 2,
    refetchInterval: (query) => {
      if (query.state.error) return false
      const status = query.state.data?.status
      if (
        status === 'CREATED' ||
        status === 'PENDING' ||
        status === 'INTERACTION_STARTED' ||
        status === 'RECONCILING' ||
        status === 'VERIFYING'
      ) {
        return 2000
      }
      // Stop polling once we reach a terminal or actionable status
      return false
    },
  })
}
