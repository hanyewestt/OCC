import { Button } from '@/components/ui/button'
import { signOut } from '@/lib/auth'

export function RolePage({ title, email, role }: { title: string; email: string; role: string }) {
  return <section className="w-full max-w-lg space-y-8">
    <header className="flex items-center justify-between border-b pb-5">
      <span className="text-sm font-medium">OCC</span>
      <Button variant="outline" onClick={() => void signOut()}>Sign out</Button>
    </header>
    <div className="space-y-2"><h1 className="text-2xl font-semibold">{title}</h1>
      <p role="status" className="text-sm text-muted-foreground">Access verified</p></div>
    <dl className="space-y-4 text-sm">
      <div><dt className="text-muted-foreground">Signed in as</dt><dd className="mt-1 break-all">{email}</dd></div>
      <div><dt className="text-muted-foreground">Role</dt><dd className="mt-1">{role}</dd></div>
    </dl>
  </section>
}
