"""Black-box checks for the Phase 1 Nginx edge using a disposable test certificate."""

from __future__ import annotations

import argparse
import concurrent.futures
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.request
from collections import Counter


def request_status(base_url: str, path: str, headers: dict[str, str] | None = None,
                   method: str = "GET") -> int:
    request = urllib.request.Request(base_url + path, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(request, context=ssl._create_unverified_context(), timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as response:
        return response.code


def assert_status(base_url: str, label: str, path: str, expected: int,
                  headers: dict[str, str] | None = None, method: str = "GET") -> None:
    actual = request_status(base_url, path, headers, method)
    if actual != expected:
        raise AssertionError(f"{label}: expected HTTP {expected}, got {actual}")
    print(f"PASS {label}: HTTP {actual}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://127.0.0.1:18443")
    parser.add_argument("--token-dir", type=Path)
    parser.add_argument("--static-only", action="store_true")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    root = Path(__file__).resolve().parents[2]
    html = (root / "frontend/dist/index.html").read_bytes()
    try:
        urllib.request.urlopen(base_url + "/not-a-page", context=ssl._create_unverified_context(), timeout=10)
        raise AssertionError("Unknown website route did not return 404")
    except urllib.error.HTTPError as response:
        if response.code != 404 or response.read() != (root / "frontend/dist/404.html").read_bytes():
            raise AssertionError("Unknown website route does not serve the custom 404 artifact")
    print("PASS custom website error page retains HTTP 404")
    for route in ("/", "/login", "/notices", "/controller", "/controller/disruptions",
                  "/controller/planning", "/controller/audit", "/captain", "/401", "/403", "/404"):
        with urllib.request.urlopen(base_url + route, context=ssl._create_unverified_context(), timeout=10) as response:
            if response.read() != html or "text/html" not in response.headers.get("Content-Type", ""):
                raise AssertionError(f"{route}: response differs from the React entry HTML")
            if response.headers.get("X-Content-Type-Options") != "nosniff":
                raise AssertionError("Security headers missing")
        print(f"PASS React entry route {route}")
    for asset in (root / "frontend/dist/assets").iterdir():
        if not asset.is_file():
            continue
        with urllib.request.urlopen(base_url + "/assets/" + asset.name, context=ssl._create_unverified_context(), timeout=10) as response:
            if response.read() != asset.read_bytes():
                raise AssertionError("Built asset mismatch")
            mime = response.headers.get("Content-Type", "")
            if asset.suffix == ".js" and "javascript" not in mime:
                raise AssertionError("JavaScript MIME mismatch")
            if asset.suffix == ".css" and "text/css" not in mime:
                raise AssertionError("CSS MIME mismatch")
    for removed_asset in ("demo.html", "auth.js"):
        if (root / "frontend/dist" / removed_asset).exists():
            raise AssertionError(f"Retired asset remains in the build: {removed_asset}")
    for route in ("/demo.html", "/auth.js", "/obu-pacing.html", "/auth-test", "/auth-test/", "/auth-test/index.html", "/auth-test/callback", "/assets/missing.js", "/admin", "/controller/unknown"):
        assert_status(base_url, "retired or unknown path " + route, route, 404)
    if args.static_only:
        return 0

    cases = [
        ("edge health", "/_occ_edge_health", 204, None, "GET"),
        ("public frontend configuration", "/frontend-config", 200, None, "GET"),
        ("React login page", "/login", 200, None, "GET"),
        ("anonymous public notice read", "/api/v1/planning/public/notices?page=0&size=1", 200, None, "GET"),
        ("public notice management requires a token", "/api/v1/planning/notices?page=0&size=1", 401, None, "GET"),
        ("captain instructions require a token", "/api/v1/planning/captain/instructions?page=0&size=1", 401, None, "GET"),
        ("protected API without token", "/api/v1/arrivals?page=0&size=1", 401, None, "GET"),
        ("forged peer token cannot authorize a public API", "/api/v1/arrivals", 401,
         {"X-OCC-Peer-Token": "forged"}, "GET"),
        ("internal API hidden", "/internal/v1/arrival-snapshots", 404, None, "GET"),
        ("management API hidden", "/actuator/health/readiness", 404, None, "GET"),
        ("OpenAPI hidden", "/v3/api-docs", 404, None, "GET"),
        ("Swagger UI hidden", "/swagger-ui/index.html", 404, None, "GET"),
        ("temporary auth harness policy", "/auth-test/index.html", 404, None, "GET"),
        ("encoded traversal to internal API blocked", "/api/v1/%2e%2e/internal/v1/arrival-snapshots", 404, None, "GET"),
        ("double-slash internal path blocked", "/internal//v1/arrival-snapshots", 404, None, "GET"),
        ("internal API remains hidden for mutations", "/internal/v1/arrival-snapshots", 404, None, "POST"),
        ("unknown top-level path hidden", "/admin", 404, None, "GET"),
    ]
    for label, path, expected, headers, method in cases:
        assert_status(base_url, label, path, expected, headers, method)

    if args.token_dir:
        token_dir = args.token_dir
        for role in ("admin", "controller"):
            token = (token_dir / f"{role}.jwt").read_text(encoding="utf-8").strip()
            assert_status(base_url, f"valid {role.upper()} JWT passes gateway and Ingestion",
                          "/api/v1/arrivals?page=0&size=1", 200,
                          {"Authorization": f"Bearer {token}"})
        for role in ("captain", "unassigned"):
            token = (token_dir / f"{role}.jwt").read_text(encoding="utf-8").strip()
            assert_status(base_url, f"valid {role.upper()} JWT is forbidden from Ingestion API",
                          "/api/v1/arrivals?page=0&size=1", 403,
                          {"Authorization": f"Bearer {token}"})
        for role in ("controller", "captain", "admin", "unassigned"):
            token = (token_dir / f"{role}.jwt").read_text(encoding="utf-8").strip()
            headers = {"Authorization": f"Bearer {token}"}
            assert_status(base_url, f"{role} verified session", "/api/v1/auth/session", 200, headers)
            for page, allowed in (("controller", "controller"), ("captain", "captain")):
                assert_status(base_url, f"{role} page access {page}", f"/api/v1/auth/access/{page}",
                              200 if role == allowed else 403, headers)
        captain_token = (token_dir / "captain.jwt").read_text(encoding="utf-8").strip()
        controller_token = (token_dir / "controller.jwt").read_text(encoding="utf-8").strip()
        assert_status(base_url, "CAPTAIN can read assigned instruction inbox",
                      "/api/v1/planning/captain/instructions?page=0&size=1", 200,
                      {"Authorization": f"Bearer {captain_token}"})
        assert_status(base_url, "CAPTAIN cannot read controller advisories",
                      "/api/v1/planning/advisories?page=0&size=1", 403,
                      {"Authorization": f"Bearer {captain_token}"})
        assert_status(base_url, "CONTROLLER cannot read captain instruction inbox",
                      "/api/v1/planning/captain/instructions?page=0&size=1", 403,
                      {"Authorization": f"Bearer {controller_token}"})
        assert_status(base_url, "CONTROLLER can read planning advisories",
                      "/api/v1/planning/advisories?page=0&size=1", 200,
                      {"Authorization": f"Bearer {controller_token}"})
        unknown_instruction = "00000000-0000-0000-0000-000000000001"
        assert_status(base_url, "CAPTAIN acknowledgement is ownership checked",
                      f"/api/v1/planning/captain/instructions/{unknown_instruction}/acknowledgements",
                      404, {"Authorization": f"Bearer {captain_token}"}, "POST")
        assert_status(base_url, "CONTROLLER cannot acknowledge a captain instruction",
                      f"/api/v1/planning/captain/instructions/{unknown_instruction}/acknowledgements",
                      403, {"Authorization": f"Bearer {controller_token}"}, "POST")
        invalid_token = (token_dir / "invalid-signature.jwt").read_text(encoding="utf-8").strip()
        assert_status(base_url, "tampered ES256 signature is rejected", "/api/v1/arrivals?page=0&size=1", 401,
                      {"Authorization": f"Bearer {invalid_token}"})

    api_path = "/api/v1/arrivals"
    with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
        outcomes = Counter(pool.map(lambda _: request_status(base_url, api_path), range(100)))
    if outcomes[401] == 0 or outcomes[429] == 0:
        raise AssertionError(f"rate-limit burst must produce both 401 and 429 outcomes; got {dict(outcomes)}")
    print(f"PASS rate limit burst: {outcomes[401]} unauthorized, {outcomes[429]} rate-limited")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, urllib.error.URLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        raise SystemExit(1)
