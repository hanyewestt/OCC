export type PublicConfig = { projectUrl: string; publishableKey: string }

export async function loadConfig(): Promise<PublicConfig> {
  const response = await fetch('/frontend-config', { cache: 'no-store', redirect: 'error' })
  if (!response.ok) throw new Error('Sign-in configuration is unavailable. Try again.')
  const config = await response.json() as Partial<PublicConfig>
  const url = new URL(config.projectUrl ?? '')
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) {
    throw new Error('Sign-in requires a secure provider URL.')
  }
  if (typeof config.publishableKey !== 'string' || !config.publishableKey.startsWith('sb_publishable_')) {
    throw new Error('The public sign-in key is not configured.')
  }
  return { projectUrl: url.href.replace(/\/$/, ''), publishableKey: config.publishableKey }
}
