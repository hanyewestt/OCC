import type { BunchingAlert } from './api'

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

// Prediction slots are not vehicle identities. Never fill
// a missing detection-time prediction using current live or another run's data.
export function bunchingArrivals(alert: BunchingAlert): string[] {
  return allBunchingArrivals(alert).slice(0, 3)
}

export function allBunchingArrivals(alert: BunchingAlert): string[] {
  if (!alert.evidence) return ['Unavailable', 'Unavailable', 'Unavailable']
  try {
    const evidence: unknown = JSON.parse(alert.evidence)
    const snapshot = isObject(evidence) ? evidence.snapshot : null
    if (!isObject(snapshot) || !Array.isArray(snapshot.arrivals) || snapshot.classification !== alert.classification) {
      return ['Unavailable', 'Unavailable', 'Unavailable']
    }
    const arrivals = snapshot.arrivals.filter(isObject).filter(arrival =>
      arrival.serviceId === alert.serviceId && arrival.stopCode === alert.stopCode && arrival.directionId === alert.directionId)
    const count = Math.min(100, arrivals.reduce((maximum, arrival) =>
      typeof arrival.visitOrder === 'number' && Number.isInteger(arrival.visitOrder) && arrival.visitOrder > 0
        ? Math.max(maximum, arrival.visitOrder) : maximum, 3))
    return Array.from({ length: count }, (_, index) => index + 1).map(order => {
      const matches = arrivals.filter(arrival => arrival.visitOrder === order)
      if (matches.length > 1) return 'Unavailable'
      const time = matches[0]?.estimatedArrivalAt
      return typeof time === 'string' && !Number.isNaN(Date.parse(time)) ? time : 'No prediction'
    })
  } catch { return ['Unavailable', 'Unavailable', 'Unavailable'] }
}
