import type { BunchingAlert } from './api'

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

// Bus 1/2/3 are the saved prediction slots, not vehicle identities. Never fill
// a missing detection-time prediction using current live or another run's data.
export function bunchingArrivals(alert: BunchingAlert): string[] {
  if (!alert.evidence) return ['Unavailable', 'Unavailable', 'Unavailable']
  try {
    const evidence: unknown = JSON.parse(alert.evidence)
    const snapshot = isObject(evidence) ? evidence.snapshot : null
    if (!isObject(snapshot) || !Array.isArray(snapshot.arrivals) || snapshot.classification !== alert.classification) {
      return ['Unavailable', 'Unavailable', 'Unavailable']
    }
    const arrivals = snapshot.arrivals.filter(isObject).filter(arrival =>
      arrival.serviceId === alert.serviceId && arrival.stopCode === alert.stopCode && arrival.directionId === alert.directionId)
    return [1, 2, 3].map(order => {
      const matches = arrivals.filter(arrival => arrival.visitOrder === order)
      if (matches.length > 1) return 'Unavailable'
      const time = matches[0]?.estimatedArrivalAt
      return typeof time === 'string' && !Number.isNaN(Date.parse(time)) ? time : 'No prediction'
    })
  } catch { return ['Unavailable', 'Unavailable', 'Unavailable'] }
}
