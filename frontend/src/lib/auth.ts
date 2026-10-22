import { createClient, type Session, type SupabaseClient } from '@supabase/supabase-js'
import { useSyncExternalStore } from 'react'
import { ApiError, identityRequest, type Identity } from './api'
import { loadConfig } from './config'

type AuthState = {
  status: 'loading' | 'signedOut' | 'unauthorized' | 'ready' | 'error'
  session: Session | null
  identity: Identity | null
  message: string
}
let state: AuthState = { status: 'loading', session: null, identity: null, message: '' }
let client: SupabaseClient | undefined
let initializing: Promise<void> | undefined
let verification: AbortController | undefined
let revision = 0
const listeners = new Set<() => void>()
function publish(next: AuthState) {
  state = next
  listeners.forEach(listener => listener())
}

async function verify(session: Session | null) {
  const attempt = ++revision
  verification?.abort()
  verification = new AbortController()
  publish({ status: session ? 'loading' : 'signedOut', session, identity: null, message: '' })
  if (!session) return
  try {
    const identity = await identityRequest('/api/v1/auth/session', session.access_token, verification.signal)
    if (attempt !== revision) return
    if (identity.subject !== session.user.id) throw new Error('Session identity does not match.')
    publish({ status: 'ready', session, identity, message: '' })
  } catch (error) {
    if (attempt !== revision) return
    if (error instanceof ApiError && error.status === 401) {
      // Clear invalid sessions outside the SDK's auth-event callback lock.
      void signOut('Your session has expired. Sign in again.')
    } else {
      publish({ status: 'error', session, identity: null, message: 'Unable to verify your session. Try again.' })
    }
  }
}

export function initializeAuth(): Promise<void> {
  if (initializing) return initializing
  publish({ status: 'loading', session: null, identity: null, message: '' })
  initializing = (async () => {
    try {
      const config = await loadConfig()
      client = createClient(config.projectUrl, config.publishableKey, {
        auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false },
      })
      client.auth.onAuthStateChange((_event, session) => {
        // No awaited SDK calls here: callbacks execute inside the SDK's auth lock.
        void verify(session)
      })
    } catch {
      initializing = undefined
      publish({ status: 'error', session: null, identity: null, message: 'Sign-in configuration is unavailable. Try again.' })
    }
  })()
  return initializing
}

export async function signIn(email: string, password: string) {
  if (!client) throw new Error('Sign-in is unavailable. Reload and try again.')
  const { error } = await client.auth.signInWithPassword({ email: email.trim(), password })
  if (error) throw new Error('Unable to sign in. Check your email and password and try again.')
}

export async function signOut(message = '') {
  ++revision
  verification?.abort()
  publish({ status: 'loading', session: null, identity: null, message: '' })
  const result = await client?.auth.signOut({ scope: 'local' })
  if (result?.error) {
    publish({ status: 'error', session: null, identity: null, message: 'Sign-out could not complete. Try signing out again.' })
    return
  }
  publish({ status: message ? 'unauthorized' : 'signedOut', session: null, identity: null, message })
}

export function retryAuth() {
  if (client) void verify(state.session)
  else void initializeAuth()
}

export function useAuth() {
  return useSyncExternalStore(listener => {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }, () => state)
}
