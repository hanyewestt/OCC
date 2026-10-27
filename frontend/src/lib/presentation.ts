const diagnosisStatuses: Record<string, string> = {
  PENDING: 'Diagnosis pending',
  PROCESSING: 'Diagnosis in progress',
  COMPLETED: 'Diagnosis completed',
  FAILED_RETRYABLE: 'Diagnosis retrying',
  FAILED_TERMINAL: 'Diagnosis failed',
}

export function diagnosisStatusLabel(status: string): string {
  return diagnosisStatuses[status] ?? status.replaceAll('_', ' ').toLowerCase()
}

export function dataClassificationLabel(classification: string): string {
  if (classification === 'SIMULATION') return 'Simulation'
  if (classification === 'LIVE' || classification === 'OBSERVATION_ONLY') return 'Live'
  return 'Unknown'
}
