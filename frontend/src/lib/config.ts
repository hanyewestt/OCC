export type PublicConfig = {
  projectUrl: string
  publishableKey: string
  controllerRefreshIntervalSeconds: number
  ingestionPollIntervalSeconds: number
  routeIncidentGapToleranceSeconds: number
  captainRefreshIntervalSeconds: number
  publicNoticeRefreshIntervalSeconds: number
}

let configRequest: Promise<PublicConfig> | undefined

export function loadConfig(): Promise<PublicConfig> {
  if (!configRequest) {
    configRequest = readConfig().catch(error => {
      configRequest = undefined
      throw error
    })
  }
  return configRequest
}

async function readConfig(): Promise<PublicConfig> {
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
  if (!Number.isInteger(config.controllerRefreshIntervalSeconds) || config.controllerRefreshIntervalSeconds! < 1 ||
    !Number.isInteger(config.ingestionPollIntervalSeconds) || config.ingestionPollIntervalSeconds! < 1 ||
    !Number.isInteger(config.routeIncidentGapToleranceSeconds) || config.routeIncidentGapToleranceSeconds! < 1 ||
    !Number.isInteger(config.captainRefreshIntervalSeconds) || config.captainRefreshIntervalSeconds! < 1 ||
    !Number.isInteger(config.publicNoticeRefreshIntervalSeconds) || config.publicNoticeRefreshIntervalSeconds! < 1) {
    throw new Error('Controller refresh settings are unavailable.')
  }
  return {
    projectUrl: url.href.replace(/\/$/, ''), publishableKey: config.publishableKey,
    controllerRefreshIntervalSeconds: config.controllerRefreshIntervalSeconds!,
    ingestionPollIntervalSeconds: config.ingestionPollIntervalSeconds!,
    routeIncidentGapToleranceSeconds: config.routeIncidentGapToleranceSeconds!,
    captainRefreshIntervalSeconds: config.captainRefreshIntervalSeconds!,
    publicNoticeRefreshIntervalSeconds: config.publicNoticeRefreshIntervalSeconds!,
  }
}
