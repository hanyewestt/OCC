# Plan 2 — Frontend UI

## Goal

Deliver a usable Next.js shell that completes Cognito sign-in, enforces role-aware navigation, supports basic profile management, and displays persisted observation-only bus-feed status and arrivals.

## Owners and dependencies

- Suggested staffing: one person, with design review from a second teammate.
- Starts on Day 1 against committed OpenAPI fixtures.
- Depends on Plan 1 for final Cognito and profile integration and Plan 3 for live ingestion data.

## Application structure

```text
apps/web/src/
├─ app/
│  ├─ (public)/
│  │  ├─ sign-in/page.tsx
│  │  └─ auth/callback/route.ts
│  ├─ (protected)/
│  │  ├─ layout.tsx
│  │  ├─ dashboard/page.tsx
│  │  ├─ profile/page.tsx
│  │  └─ admin/profiles/page.tsx
│  ├─ forbidden/page.tsx
│  ├─ error.tsx
│  └─ layout.tsx
├─ features/
│  ├─ auth/
│  ├─ profile/
│  └─ observations/
├─ components/
│  ├─ layout/
│  ├─ feedback/
│  └─ ui/
├─ lib/
│  ├─ api/
│  │  ├─ generated/
│  │  └─ client.ts
│  ├─ auth/
│  ├─ env/
│  └─ formatting/
└─ test/
```

Use TypeScript strict mode and the Next.js App Router. Use Auth.js with Cognito's OIDC provider, authorization code with PKCE, and secure HTTP-only cookie sessions. Backend API calls run through server components, server actions, or same-origin route handlers so Cognito tokens are not persisted in browser storage. Keep feature-specific components and hooks inside `features`; reserve `components/ui` for genuinely reusable primitives. Server-only authentication and secrets must not enter client bundles.

## Routes and permissions

| Route | Access | Week 1 content |
|---|---|---|
| `/sign-in` | Public | Email/password and Google entry points through Cognito |
| `/dashboard` | Any authenticated role | Observation-only feed health, monitored stops, recent arrivals |
| `/profile` | Any authenticated role | Identity summary and permitted profile form |
| `/admin/profiles` | `ADMIN` | Paginated profiles and role-management control |
| `/forbidden` | Authenticated | Clear explanation without exposing protected data |

Navigation visibility follows the session role, but every data request still relies on backend authorization.

## UI data contracts

Generate TypeScript types from `services/operations/openapi/operations-v1.yaml` and `services/ingestion/openapi/ingestion-v1.yaml`. Wrap generated clients in feature-level server-only functions; components must not call backend APIs directly.

Use a consistent query-state model:

```ts
type ViewState<T> =
  | { status: "loading" }
  | { status: "success"; data: T; refreshedAt: string }
  | { status: "empty"; message: string }
  | { status: "error"; code: string; message: string; requestId?: string };
```

The observation dashboard must label all live values as **Observation only — not used for simulated planning**.

## Tasks

### Day 1 — Frontend foundation

- Scaffold `apps/web` with TypeScript strict mode, App Router, linting, formatting, unit testing, and Playwright smoke tests.
- Implement validated runtime environment access; fail startup when required public configuration is absent.
- Add the application shell, responsive sidebar/header, loading, empty, error, forbidden, and not-found states.
- Generate API clients from initial OpenAPI contracts and add fixture-backed handlers for parallel development.
- Define accessible typography, spacing, colors, focus styles, and status semantics; do not encode status using color alone.

Verification:

- Lint, type-check, unit tests, and production build pass.
- No secret or server-only variable appears in the client build.

### Day 2 — Authentication flow

- Integrate Cognito managed sign-in through Auth.js using authorization code with PKCE.
- Implement callback, logout, session expiry, and return-to-original-page behavior.
- Protect the authenticated route group on the server where supported.
- Add role-aware navigation for `ADMIN`, `CONTROLLER`, and `DRIVER`.
- Handle missing, invalid, and ambiguous role claims by ending the protected session and showing a safe error.

Verification:

- Email/password and Google entry points reach Cognito.
- An unauthenticated user cannot render protected page data.
- Admin navigation is hidden for other roles, and a direct request still receives backend `403`.

### Day 3 — Profile experience

- Implement the profile summary using token-derived verified identity and API-owned mutable fields.
- Add a form for `displayName` and `contactPreference` only.
- Carry the optimistic-lock `version` and handle `409` by reloading with a clear conflict message.
- Display field validation, global errors, and request IDs from problem responses.
- Implement the admin profile list and role control against fixture data, then the real endpoint.

Verification:

- Form cannot submit role, subject, email, or assignment fields.
- Successful updates survive reload.
- Error and concurrency states remain usable and accessible.

### Day 4 — Observation dashboard

- Display status cards for configured feeds, including last attempt, last success, failure count, and stale state.
- Display monitored stops and recent normalized arrivals grouped by `serviceId` and `directionId`.
- Add manual refresh without creating a polling command; the page reads persisted data only.
- Show explicit loading, empty, stale, partial-data, and backend-unavailable states.
- Do not describe observations as whole-fleet tracking or operational truth.

Verification:

- Dashboard works against fixtures and the real Ingestion service.
- Stale data remains visible with a warning instead of disappearing.
- Bus-stop codes preserve leading zeroes.

### Day 5 — End-to-end and deployment

- Run Playwright flows for login redirect, profile update, role denial, feed status, and stale-data display.
- Deploy to Vercel and configure exact Cognito callback/logout URLs.
- Verify browser-to-public-API CORS and authorization behavior.
- Capture screenshots and acceptance evidence for the sprint review.
- Document frontend setup, generated-client refresh, test commands, and deployment rollback.

## Required tests

- Unit tests for environment validation, problem-response mapping, and role navigation.
- Component tests for profile form validation and every dashboard state.
- Generated-client compile check against committed OpenAPI.
- Playwright smoke tests using deterministic fixture identities in CI.
- Manual deployed tests for Cognito email/password and Google federation.

## Exit criteria

- All routes render correctly on desktop and narrow screens.
- Authenticated users can view and update permitted profile fields.
- Admin-only UI is role-aware and backend denial is handled correctly.
- Observation dashboard shows feed health and normalized arrivals with an observation-only label.
- Lint, type-check, tests, production build, and Playwright smoke suite pass.
- Vercel deployment works with the public backend and Cognito callbacks.
