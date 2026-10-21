# Frontend conventions for the MVP

Use React, TypeScript, Vite, Tailwind CSS and shadcn/ui. Start small; add folders
and abstractions only when actual code needs them. These conventions describe
the intended frontend; React hosting still needs implementation and verification.

## 1. Build and hosting

- Vite runs the development server while we edit the app.
- `npm run build` produces static HTML, JavaScript, CSS and assets in `dist/`.
- Nginx serves `dist/` in production and proxies API requests to Gateway.
  Production does not need Vite or a Node.js application server.
- Commit `package-lock.json` and use `npm ci` for reproducible installs. Document
  the supported Node version and commands in `frontend/README.md`.
- Keep TypeScript strict mode, React Strict Mode and React Hooks lint rules.
  Use the scaffold's tooling rather than adding extra build infrastructure.

[React with Vite](https://react.dev/learn/build-a-react-app-from-scratch),
[Vite static hosting](https://vite.dev/guide/static-deploy).

## 2. Small directory structure

```text
frontend/
  README.md                 # installation, commands and configuration
  package.json
  package-lock.json
  index.html                # loads main.tsx; Vite builds this HTML for Nginx
  vite.config.ts
  tsconfig*.json
  eslint.config.js
  components.json           # shadcn setup and aliases
  public/                   # only stable-name public files, e.g. favicon
  src/
    main.tsx                # starts React and imports index.css
    App.tsx                 # shared page layout and routes
    index.css               # Tailwind setup, shadcn tokens and base styles
    pages/                  # screen components, e.g. IncidentsPage.tsx
    components/             # shared app components
      ui/                   # shadcn components added as needed
    lib/
      api.ts                # API calls, auth headers and shared error handling
      config.ts             # loads and validates public runtime settings
      utils.ts              # shadcn's cn() styling helper
    assets/                 # imported images/fonts, when needed
  test/
    e2e/                    # browser tests for complete user journeys
    # Add unit/component tests and shared setup here only when needed.
  dist/                     # generated build output; ignored by Git
```

A page assembles one screen. Put reusable UI in `components/`; keep code used
by only one page with that page until extraction makes it clearer. Use
PascalCase for application component files and retain shadcn's generated names.
If using `@/`, configure it consistently in Vite, TypeScript and shadcn.

Start API calls in one `lib/api.ts`; split by resource only when it becomes
hard to navigate. Do not start with `features/`, multiple hook locations,
generated clients, provider layers or a global store. Extract a custom hook
only when it removes real duplication or clarifies behavior.

Keep all frontend tests under `frontend/test/`. There is no second `src/test/`
directory or requirement for one test file per component. The React app replaces
the temporary static demo/auth pages; do not carry those into the new structure.

Commit source, configuration and the lockfile. Ignore `node_modules/`, `dist/`,
coverage, test reports and saved sessions. Never put secrets in `public/`.

## 3. UI and styling

- Reuse project components first, then shadcn components or blocks. Compose
  them before considering custom primitives. Do not recreate buttons, dialogs,
  inputs or tables that the chosen components cover.
- Add only needed shadcn components. Keep business logic and API calls outside
  `components/ui/`. Avoid wrappers that only rename existing components.
- Use Tailwind utilities in JSX. Keep shared theme tokens in `src/index.css`;
  prefer semantic classes like `bg-background` and `text-destructive`.
- Follow matching Tailwind/shadcn Vite setup. For Tailwind v4, use
  `@tailwindcss/vite` and `@import "tailwindcss";`, not a mixed v3 setup.
- Use `cn()` for conditional classes. Write complete class names instead of
  constructing strings like `text-${color}-500`.
- Use responsive Flexbox/Grid layouts and consistent spacing. Keep form labels,
  keyboard navigation, dialog focus and visible focus indicators. Show status
  in text as well as color. Check narrow and desktop screens.
- Add custom CSS only when utilities cannot reasonably express the requirement.

[shadcn setup](https://ui.shadcn.com/docs/installation/vite),
[components](https://ui.shadcn.com/docs/components),
[theming](https://ui.shadcn.com/docs/theming),
[Tailwind setup](https://tailwindcss.com/docs/installation/using-vite),
[class detection](https://tailwindcss.com/docs/detecting-classes-in-source-files).

## 4. React and API behavior

- Use function components and typed props. Keep state local; derive filtered
  lists/counts rather than duplicating state. Use stable IDs as list keys.
- Keep requests and shared error handling in `lib/api.ts`. Match backend OpenAPI
  request/response shapes; code generation is not required for the MVP.
- Use Effects for external synchronization, not calculations or button commands.
  Clean up timers/requests and ignore stale results.
- Show loading, empty, error and success states. Disable duplicate submissions
  while pending. Do not automatically retry operational mutations without the
  backend's documented idempotency support.
- Handle expired sessions (`401`), forbidden actions (`403`), validation and
  conflicts with useful feedback. Backend services enforce permissions;
  hiding a button is only a UI convenience.
- Format UTC timestamps for display. Label simulation versus observation-only
  data. Add a query library only if repeated fetching, polling or caching becomes
  difficult to manage with the existing code.

[React state](https://react.dev/learn/choosing-the-state-structure),
[Effects](https://react.dev/learn/you-might-not-need-an-effect).

## 5. Configuration and authentication

Use same-origin `/api/v1/...` requests for OCC APIs. Never put Compose DNS names
or backend host ports in browser requests. Centralize token attachment and send
credentials only to their intended destinations.

Load public runtime settings from `GET /frontend-config`; its existing response
contains `projectUrl` and `publishableKey`. Validate the response and show a
configuration error if unavailable. Do not assume extra fields exist.

Deployment-varying settings, including polling intervals and timeouts, must use
environment-backed configuration. Extend the public config contract when the
browser needs new settings. Bind service settings with typed
`@ConfigurationProperties`; update root `.env.example` and the relevant README
in the same change. Keep one environment inventory and expose only safe values.

`VITE_*` values are public and baked in at build time; container environment
changes do not update the bundle. Never bundle private keys or service secrets.
Follow [AUTHENTICATION.md](AUTHENTICATION.md): retain memory-only access tokens
until the session design is explicitly changed, and clear protected data on
sign-out/session change. Replacing the temporary pages does not itself complete
production login, refresh or logout.
[Vite environment behavior](https://vite.dev/guide/env-and-mode).

## 6. Nginx requirements

The current setup serves temporary files from `frontend/static/` through an
explicit allowlist. It does not yet serve React. Update Compose and
`infra/nginx/routes.inc.template` together when implementing the app:

1. Serve the complete `dist/` at `/usr/share/nginx/html` using a read-only mount
   or an image containing the build. Preserve the unprivileged/read-only setup.
   Replace the temporary pages and their serving exceptions.
2. Use Vite `base: '/'`. Serve `/`, `index.html`, `/assets/` and needed public
   files. Import source assets so Vite produces their built URLs.
3. Fall back to `index.html` for actual UI routes so direct links and refresh
   work. Keep other top-level routes and missing assets returning 404. Include
   a React not-found screen for unmatched client routes.
4. Preserve `/api/v1/**` and `/frontend-config` proxying, request paths and auth
   headers. Keep internal, management and API documentation routes blocked,
   plus the production `/auth-test` block. Retired demo URLs return 404.
   Do not use a global 404-to-index rewrite.
5. Preserve TLS, rate limits and security headers. Keep the existing `no-store`
   policy for the MVP. Publish HTML and assets together as one artifact.

Nginx and React routes must agree. A working Vite development server does not
prove production compatibility.
[Nginx try_files](https://nginx.org/en/docs/http/ngx_http_core_module.html#try_files),
[proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass),
[Vite assets](https://vite.dev/guide/assets).

## 7. Minimum verification

- Run lint, TypeScript checking and the production build. Vite's build alone
  does not type-check TypeScript. Document the actual commands in the README.
- Test important custom logic and risky behavior; do not test every shadcn
  primitive or add snapshots just to increase coverage.
- Keep a few browser tests in `test/e2e/`: open a screen, filter results and
  submit an action. Use isolated data, accessible selectors and waits for
  outcomes instead of fixed sleeps. State whether APIs are mocked or real.
  Supply test settings through inventoried environment variables; keep
  credentials and saved sessions out of Git.
- Check the built app through local HTTP and production-style HTTPS Nginx:
  direct links/refresh, assets, missing-file 404s, protected APIs, blocked routes
  and useful feedback when configuration or APIs are unavailable.
- Update existing edge tests for React and remove expectations that retired
  pages remain available. See [TESTING.md](TESTING.md) and
  [DEPLOYMENT.md](DEPLOYMENT.md) for existing checks and hosting details.

Defer generated clients, elaborate feature layers, global stores, comprehensive
component suites, advanced caching and performance tuning until a concrete need
justifies them.
