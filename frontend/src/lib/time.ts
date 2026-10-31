const singaporeTime = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Singapore', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

const singaporeDateTime = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Singapore', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

const singaporeDateTimeInput = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Singapore', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

export function formatSgtTime(value: string | null | undefined): string {
  const date = value ? new Date(value) : null
  return date && Number.isFinite(date.getTime()) ? `${singaporeTime.format(date)} SGT` : '—'
}

export function formatSgtDateTime(value: string | null | undefined): string {
  const date = value ? new Date(value) : null
  return date && Number.isFinite(date.getTime()) ? `${singaporeDateTime.format(date)} SGT` : '—'
}

export function sgtDateTimeInputToUtc(value: string): string {
  const localValue = value.length === 16 ? `${value}:00` : value
  return new Date(`${localValue}+08:00`).toISOString()
}

export function sgtDateTimeInputFromUtc(value: string | null | undefined): string {
  const date = value ? new Date(value) : null
  return date && Number.isFinite(date.getTime()) ? singaporeDateTimeInput.format(date).replace(' ', 'T') : ''
}
