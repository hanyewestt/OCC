import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { NotFoundPage } from './pages/NotFoundPage'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode><main className="flex min-h-svh items-center justify-center px-5 py-12"><NotFoundPage /></main></StrictMode>,
)
