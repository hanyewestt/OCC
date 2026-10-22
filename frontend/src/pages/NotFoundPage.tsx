import { Button } from '@/components/ui/button'
import { ErrorPage } from '@/components/ErrorPage'

export function NotFoundPage() {
  return <ErrorPage code={404} title="Page not found" description="The page you’re looking for doesn’t exist.">
    <Button variant="outline" asChild><a href="/">Return home</a></Button>
  </ErrorPage>
}
