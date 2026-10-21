import { RolePage } from '@/components/RolePage'
export function CaptainPage({ email }: { email: string }) {
  return <RolePage title="Captain" email={email} role="Captain (DRIVER)" />
}
