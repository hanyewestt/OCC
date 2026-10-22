import { RolePage } from '@/components/RolePage'
export function ControllerPage({ email }: { email: string }) {
  return <RolePage title="Controller" email={email} role="Controller" />
}
