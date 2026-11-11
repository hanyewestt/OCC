"""Exercise the real edge routes against isolated header-echo upstreams."""

from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
IMAGE = "nginx:1.30.6-alpine-slim@sha256:f65ed258206d1eb6c4eb6141111715a2cf94ac0a4ec9675fc2da186f7aadb163"


def main() -> None:
    name = "occ-nginx-proxy-" + uuid.uuid4().hex[:12]
    routes = (ROOT / "infra/nginx/routes.inc.template").read_text(encoding="utf-8")
    for service, port in (("INGESTION", 18001), ("DISRUPTION", 18002), ("PLANNING", 18003)):
        routes = routes.replace("${OCC_" + service + "_INTERNAL_URL}", f"http://127.0.0.1:{port}")
    upstreams = "\n".join(
        f"server {{ listen {port}; location / {{ return 200 "
        "'$server_port|$request_method|$request_uri|$http_authorization|"
        "$http_idempotency_key|$http_x_correlation_id|$http_x_occ_peer_token|$http_forwarded'; } }"
        for port in (18001, 18002, 18003)
    )
    config = """pid /tmp/nginx.pid;
error_log /dev/stderr notice;
events { worker_connections 128; }
http {
    access_log off;
    client_body_temp_path /tmp/client_body;
    proxy_temp_path /tmp/proxy;
    fastcgi_temp_path /tmp/fastcgi;
    uwsgi_temp_path /tmp/uwsgi;
    scgi_temp_path /tmp/scgi;
    limit_req_zone $binary_remote_addr zone=occ_api:1m rate=20r/s;
    server { listen 8080; include /fixture/routes.inc; }
""" + upstreams + "\n}\n"
    with tempfile.TemporaryDirectory(prefix="occ-nginx-proxy-") as directory:
        fixture = Path(directory)
        (fixture / "routes.inc").write_text(routes, encoding="utf-8")
        (fixture / "nginx.conf").write_text(config, encoding="utf-8")
        fixture.chmod(0o755)
        for path in fixture.iterdir():
            path.chmod(0o644)
        try:
            subprocess.run(["docker", "run", "--detach", "--name", name,
                            "--user", "101:101", "--read-only", "--cap-drop", "ALL",
                            "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:mode=1777",
                            "--publish", "127.0.0.1::8080", "--mount",
                            f"type=bind,source={fixture.resolve()},target=/fixture,readonly",
                            "--entrypoint", "nginx", IMAGE, "-c", "/fixture/nginx.conf",
                            "-g", "daemon off;"], check=True, capture_output=True, text=True)
            address = subprocess.check_output(["docker", "port", name, "8080/tcp"], text=True).strip()
            base = "http://" + address
            for attempt in range(30):
                try:
                    with urllib.request.urlopen(base + "/_occ_edge_health", timeout=2):
                        break
                except (urllib.error.URLError, OSError):
                    if attempt == 29:
                        raise
                    time.sleep(0.1)
            cases = (
                ("GET", "/frontend-config", "18001", "/frontend-config"),
                ("GET", "/api/v1/auth/session", "18001", "/api/v1/auth/session"),
                ("GET", "/api/v1/auth/access/captain", "18001", "/api/v1/auth/access/captain"),
                ("GET", "/api/v1/arrivals?page=2&size=1", "18001", "/api/v1/arrivals?page=2&size=1"),
                ("PUT", "/api/v1/ingestion/demo-mode?enabled=true", "18001", "/api/v1/demo-mode?enabled=true"),
                ("GET", "/api/v1/ingestion/admin/users?page=0&size=20", "18001", "/api/v1/admin/users?page=0&size=20"),
                ("GET", "/api/v1/ingestion/admin/audit-events?category=ACCOUNT&page=0", "18001", "/api/v1/admin/audit-events?category=ACCOUNT&page=0"),
                ("GET", "/api/v1/ingestion/admin/demo-runs/active/assignments", "18001", "/api/v1/admin/demo-runs/active/assignments"),
                ("GET", "/api/v1/ingestion/captains?available=true&size=100", "18001", "/api/v1/captains?available=true&size=100"),
                ("GET", "/api/v1/disruption/bunching-alerts?size=1", "18002", "/api/v1/disruption/bunching-alerts?size=1"),
                ("POST", "/api/v1/planning/advisory-groups/decisions", "18003", "/api/v1/planning/advisory-groups/decisions"),
                ("PUT", "/api/v1/planning/admin/demo-runs/run-1/buses/bus-1/assignment", "18003", "/api/v1/planning/admin/demo-runs/run-1/buses/bus-1/assignment"),
                ("GET", "/api/v1/planning/admin/assignment-operations/operation-1", "18003", "/api/v1/planning/admin/assignment-operations/operation-1"),
                ("GET", "/api/v1/pacing-snapshots/stream?serviceId=190", "18001", "/api/v1/pacing-snapshots/stream?serviceId=190"),
                ("GET", "/api/v1/ingestion/pacing-snapshots/stream?serviceId=190", "18001", "/api/v1/pacing-snapshots/stream?serviceId=190"),
            )
            for method, path, port, upstream_path in cases:
                request = urllib.request.Request(base + path, method=method, headers={
                    "Authorization": "Bearer synthetic-proxy-test", "Idempotency-Key": "synthetic-request",
                    "X-Correlation-ID": "client-spoof", "X-OCC-Peer-Token": "client-spoof",
                    "Forwarded": "for=client-spoof"})
                with urllib.request.urlopen(request, timeout=5) as response:
                    fields = response.read().decode().split("|")
                assert fields[:5] == [port, method, upstream_path, "Bearer synthetic-proxy-test", "synthetic-request"], fields
                assert re.fullmatch(r"[0-9a-f]{32}", fields[5]), fields
                assert fields[6:] == ["", ""], fields
                print(f"PASS proxy method, path, query and trusted headers: {method} {path}")
        except Exception:
            subprocess.run(["docker", "logs", name], check=False)
            raise
        finally:
            subprocess.run(["docker", "rm", "--force", name], check=False, capture_output=True)


if __name__ == "__main__":
    main()
