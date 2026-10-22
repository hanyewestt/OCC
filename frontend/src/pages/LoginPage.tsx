import { useState, type SubmitEvent } from 'react'
import { signIn } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export function LoginPage({ message }: { message: string }) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    setPending(true)
    setError('')
    try {
      await signIn(String(data.get('email')), String(data.get('password')))
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Unable to sign in.')
    } finally {
      (form.elements.namedItem('password') as HTMLInputElement).value = ''
      setPending(false)
    }
  }
  return <Card className="w-full max-w-sm shadow-none">
    <CardHeader><p className="mb-3 text-xs font-medium tracking-widest text-muted-foreground">OCC</p>
      <CardTitle className="text-xl"><h1>Sign in</h1></CardTitle>
      <CardDescription>Use your OCC account to continue.</CardDescription>
    </CardHeader>
    <CardContent><form onSubmit={submit} className="space-y-5">
      <div className="space-y-2"><Label htmlFor="email">Email</Label>
        <Input id="email" name="email" type="email" autoComplete="username" required disabled={pending} /></div>
      <div className="space-y-2"><Label htmlFor="password">Password</Label>
        <Input id="password" name="password" type="password" autoComplete="current-password" required disabled={pending} /></div>
      {(error || message) && <p role="alert" className="text-sm text-destructive">{error || message}</p>}
      <Button type="submit" className="w-full" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</Button>
    </form></CardContent>
  </Card>
}
