export type Identity = { subject: string; role: 'CONTROLLER' | 'CAPTAIN' | 'ADMIN' | 'UNASSIGNED' }

export class ApiError extends Error {
  constructor(public status: number) {
    super(status === 401 ? 'Your session has expired. Sign in again.' :
      status === 403 ? 'Access is not available for this role.' : 'Unable to verify access. Try again.')
  }
}

export async function identityRequest(path: string, token: string, signal: AbortSignal): Promise<Identity> {
  // This transport must never send a bearer token to an external URL.
  if (!path.startsWith('/api/v1/auth/')) throw new Error('Invalid authentication request.')
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', redirect: 'error', signal,
  })
  if (!response.ok) throw new ApiError(response.status)
  const body = await response.json() as Partial<Identity>
  if (typeof body.subject !== 'string' || !body.subject ||
    !['CONTROLLER', 'CAPTAIN', 'ADMIN', 'UNASSIGNED'].includes(body.role ?? '')) {
    throw new Error('Invalid session response. Try again.')
  }
  return body as Identity
}

export function homeFor(role: Identity['role']): string | null {
  return role === 'CONTROLLER' ? '/controller' : role === 'CAPTAIN' ? '/captain' : null
}
