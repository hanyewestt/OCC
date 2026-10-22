import type { ReactNode } from 'react'

export function ErrorPage({ code, title, description, children }: {
  code: 401 | 403 | 404
  title: string
  description: string
  children: ReactNode
}) {
  return <section className="max-w-sm space-y-4">
    <p className="text-sm text-muted-foreground">{code}</p>
    <h1 className="text-2xl font-semibold">{title}</h1>
    <p className="text-sm text-muted-foreground">{description}</p>
    <div className="flex flex-wrap items-center gap-3">{children}</div>
  </section>
}
