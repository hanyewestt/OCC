export function requestedCaptainId(selected: string | undefined, current: string | null): string | null {
  return (selected ?? current ?? '') || null
}

export function canEditAssignments(runStatus: string | null | undefined): boolean {
  return runStatus === 'RUNNING' || runStatus === 'REPLAY_COMPLETE'
}
