# OCC authentication app

Minimal React/TypeScript app using Vite, Tailwind and shadcn. Only login and
exclusive controller/captain pages are implemented; there are no operational UI features.

## Build and run through Nginx

Use Node.js 22.12+ (CI uses Node 22), npm and the repository's Docker prerequisites.
From this directory:

```shell
npm ci
npm run build
```

Then from the repository root, configure the ignored `.env` using `.env.example`
and run `docker compose --env-file .env up -d --build --wait`. The root app
start scripts also install/build the frontend automatically. Open
`http://localhost/login`, adjusting the port to `OCC_NGINX_PORT`.

Compose mounts `frontend/dist/` read-only into Nginx. Rebuild after frontend
source changes; Node/Vite do not run in the production container. Production
deployment must stage this complete build artifact alongside Compose files.
After changing Nginx templates, restart Nginx.

The shared error layout displays 401 (sign-in required), 403 (access denied)
and 404 (page not found). `/401` and `/403` are SPA screens; rejected sessions
show 401, and wrong/unsupported roles show 403. Their actions use the existing
sign-in, return-to-own-page and sign-out flows. API status codes and bodies are
unchanged. Unknown website URLs show the 404 page while retaining HTTP 404.
Vite builds a separate `404.html` entry using the shared `NotFoundPage` component,
so it needs no auth bootstrap or API connection. Nginx keeps API responses and
missing-asset errors separate from this website error page.

## Development

Set `OCC_FRONTEND_DEV_PORT` and `OCC_FRONTEND_EDGE_PUBLIC_URL` in the root `.env`
or shell environment. Defaults are `5173` and the example Nginx URL
`http://localhost:80`. The root `.env` is read by Vite for these development-only
settings; no private environment values are exposed to browser code.

```shell
npm run dev
```

Open `http://127.0.0.1:5173`. The Vite server forwards `/frontend-config` and
`/api/v1` to the configured Nginx URL. Restart Vite after changing its settings.
Do not use Vite as a production server.

## Accounts and access

Use existing Supabase email/password accounts. The trusted top-level `occ_role`
claim determines access; there is no role selector:

| Claim | Page |
| --- | --- |
| `CONTROLLER` | `/controller` |
| `DRIVER` | `/captain` (displayed as Captain) |
| `ADMIN`, `UNASSIGNED`, unknown | Access-not-available message and sign-out |

Each protected page waits for Gateway session and page-access verification.
Other-role URLs show access denied. Business API permissions are unchanged.
Supabase persists sessions in browser storage and refreshes them. Local sign-out
clears this browser session and propagates to other open tabs; it does not sign
out every device. This explicitly replaces the old memory-only demo behavior.
Only use this app on a trusted browser; browser storage is accessible to scripts
running on this origin. Never commit saved browser sessions or test credentials.

## Checks

```shell
npm run lint
npm run typecheck
npm run build
npx playwright install chromium
npm test
```

The browser suite mocks Supabase and Gateway responses; it proves frontend
behavior, not real provider or backend authorization. By default it starts Vite.
To run the same browser journeys against a running Nginx build, set
`OCC_FRONTEND_TEST_PUBLIC_URL` to its host-facing URL. Test deadlines use
`OCC_FRONTEND_TEST_TIMEOUT_MS` and `OCC_FRONTEND_TEST_EXPECT_TIMEOUT_MS`, with
documented defaults in the root environment inventory.

For actual Gateway HTTP/HTTPS checks, run the root
`tests/system/nginx_edge_smoke.py`. For opt-in live Supabase sign-in, refresh,
role checks and local sign-out, see `docs/AUTHENTICATION.md` and
`tests/system/supabase_live_auth_smoke.mjs`. Ordinary tests require no live credentials.
