import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { usePageRefresh } from '@/lib/page-refresh'

function Harness() {
  const [scope, setScope] = useState('alpha')
  const [paused, setPaused] = useState(false)
  const [result, setResult] = useState('Waiting')
  const { requestRefresh, isRefreshing } = usePageRefresh(1, async signal => {
    const response = await fetch(`/__test/refresh?scope=${scope}`, { signal })
    const text = await response.text()
    if (!signal.aborted) setResult(text)
  }, paused, scope)
  return <main>
    <select aria-label="Scope" value={scope} onChange={event => setScope(event.target.value)}>
      <option value="alpha">Alpha</option><option value="beta">Beta</option>
    </select>
    <button onClick={() => { void requestRefresh().catch(() => {}) }}>Refresh</button>
    <button onClick={() => { void requestRefresh('mutation').catch(() => {}) }}>Saved</button>
    <button onClick={() => setPaused(value => !value)}>{paused ? 'Resume' : 'Pause'}</button>
    <p role="status">{isRefreshing ? 'Refreshing' : 'Idle'}</p><p aria-label="Result">{result}</p>
  </main>
}
createRoot(document.getElementById('root')!).render(<Harness />)
