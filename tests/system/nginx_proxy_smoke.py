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
    uwsgi_temp_path /tmp/uwsgi_temp;
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
                ("GET", "/api/v1/disruption/bunching-alerts?size=1", "18002", "/api/v1/disruption/bunching-alerts?size=1"),
                ("POST", "/api/v1/planning/advisory-groups/decisions", "18003", "/api/v1/planning/advisory-groups/decisions"),
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
    run_cloudfront_origin_cases()


def run_cloudfront_origin_cases() -> None:
    """Exercise the CloudFront origin gate and trusted viewer identity.

    The origin only ever accepts requests carrying the secret header. Behind
    that gate the client is the last X-Forwarded-For entry CloudFront appended
    and the protocol comes from CloudFront-Forwarded-Proto; the origin secret
    and peer tokens never reach the backends.
    """
    name = "occ-nginx-cf-" + uuid.uuid4().hex[:12]
    template = (ROOT / "infra/nginx/cloudfront.conf.template").read_text(encoding="utf-8")
    routes = (ROOT / "infra/nginx/routes.inc.template").read_text(encoding="utf-8")
    for service, port in (("INGESTION", 18001), ("DISRUPTION", 18002), ("PLANNING", 18003)):
        routes = routes.replace("${OCC_" + service + "_INTERNAL_URL}", f"http://127.0.0.1:{port}")
    template = template.replace("${OCC_NGINX_HTTP_INTERNAL_PORT}", "18080")
    template = template.replace("${OCC_ORIGIN_SECRET}", "synthetic-origin-secret")
    upstream = ("server { listen 18001; location / { return 200 "
                "'$http_x_forwarded_for|$http_x_forwarded_proto|$http_x_occ_origin_secret|"
                "$http_x_occ_peer_token|$http_idempotency_key'; } }")
    config = """pid /tmp/nginx.pid;
error_log /dev/stderr notice;
events { worker_connections 128; }
http {
    access_log off;
    client_body_temp_path /tmp/client_body;
    proxy_temp_path /tmp/proxy;
    fastcgi_temp_path /tmp/fastcgi;
    uwsgi_temp_path /tmp/uwsgi_temp;
    scgi_temp_path /tmp/scgi;
    include /fixture/cloudfront.conf;
""" + upstream + "\n}\n"
    with tempfile.TemporaryDirectory(prefix="occ-nginx-cf-") as directory:
        fixture = Path(directory)
        (fixture / "routes.inc").write_text(routes, encoding="utf-8")
        (fixture / "cloudfront.conf").write_text(template, encoding="utf-8")
        (fixture / "nginx.conf").write_text(config, encoding="utf-8")
        gate_dir = fixture / "gate"
        gate_dir.mkdir()
        (gate_dir / "traffic-gate.inc").write_text(
            'set $occ_traffic_gate "open";\nset $occ_gate_token "synthetic-gate-token";\n',
            encoding="utf-8")
        fixture.chmod(0o755)
        for path in fixture.rglob("*"):
            if path.is_file():
                path.chmod(0o644)
        try:
            subprocess.run(["docker", "run", "--detach", "--name", name,
                            "--user", "101:101", "--read-only", "--cap-drop", "ALL",
                            "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:mode=1777",
                            "--publish", "127.0.0.1::18080", "--mount",
                            f"type=bind,source={fixture.resolve()},target=/fixture,readonly",
                            "--mount",
                            f"type=bind,source={fixture.resolve()}/routes.inc,"
                            "target=/etc/nginx/conf.d/routes.inc,readonly",
                            "--mount", f"type=bind,source={gate_dir.resolve()},target=/etc/nginx/gate,readonly",
                            "--entrypoint", "nginx", IMAGE, "-c", "/fixture/nginx.conf",
                            "-g", "daemon off;"], check=True, capture_output=True, text=True)
            address = subprocess.check_output(["docker", "port", name, "18080/tcp"], text=True).strip()
            base = "http://" + address
            for attempt in range(30):
                try:
                    # The origin gate also guards the health endpoint, so the
                    # readiness probe presents the secret header.
                    request = urllib.request.Request(
                        base + "/_occ_edge_health",
                        headers={"X-OCC-Origin-Secret": "synthetic-origin-secret"})
                    urllib.request.urlopen(request, timeout=2)
                    break
                except (urllib.error.URLError, OSError):
                    if attempt == 29:
                        raise
                    time.sleep(0.1)

            def request(path, headers):
                request = urllib.request.Request(base + path, headers=headers)
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        return response.status, response.read().decode()
                except urllib.error.HTTPError as response:
                    return response.code, ""

            status, _ = request("/frontend-config", {})
            assert status == 403, f"origin without the secret header must be refused: {status}"
            print("PASS cloudfront origin refuses requests without the secret header")
            status, _ = request("/frontend-config", {"X-OCC-Origin-Secret": "wrong-value"})
            assert status == 403, f"origin with a wrong secret must be refused: {status}"
            print("PASS cloudfront origin refuses wrong secret values")
            status, body = request("/frontend-config", {
                "X-OCC-Origin-Secret": "synthetic-origin-secret",
                "X-Forwarded-For": "6.6.6.6, 9.9.9.9",
                "CloudFront-Forwarded-Proto": "https",
                "X-OCC-Peer-Token": "client-spoof",
                "Idempotency-Key": "synthetic-request",
            })
            assert status == 200, status
            fields = body.split("|")
            # The last XFF entry is the CloudFront viewer; earlier entries are
            # viewer-supplied and untrusted.
            assert fields[0] == "9.9.9.9", fields
            assert fields[1] == "https", fields
            assert fields[2] == "", fields
            assert fields[3] == "", fields
            assert fields[4] == "synthetic-request", fields
            print("PASS cloudfront origin trusts only the appended viewer address "
                  "and never forwards the origin secret")
            status, body = request("/frontend-config", {
                "X-OCC-Origin-Secret": "synthetic-origin-secret",
                "X-Forwarded-For": "10.0.0.1, 203.0.113.7",
                "CloudFront-Forwarded-Proto": "https",
            })
            assert status == 200 and body.split("|")[0] == "203.0.113.7", body
            status, body = request("/frontend-config", {
                "X-OCC-Origin-Secret": "synthetic-origin-secret",
                "X-Forwarded-For": "10.0.0.2, 203.0.113.8",
                "CloudFront-Forwarded-Proto": "https",
            })
            assert status == 200 and body.split("|")[0] == "203.0.113.8", body
            print("PASS cloudfront origin separates two viewers at one edge")
        except Exception:
            subprocess.run(["docker", "logs", name], check=False)
            raise
        finally:
            subprocess.run(["docker", "rm", "--force", name], check=False, capture_output=True)
    run_closed_gate_cases()


def run_closed_gate_cases() -> None:
    """While the traffic gate is closed only the secret-bearing readiness
    request is served; everything else, including the health endpoint, is 503."""
    name = "occ-nginx-gate-" + uuid.uuid4().hex[:12]
    template = (ROOT / "infra/nginx/cloudfront.conf.template").read_text(encoding="utf-8")
    routes = (ROOT / "infra/nginx/routes.inc.template").read_text(encoding="utf-8")
    routes = routes.replace("${OCC_INGESTION_INTERNAL_URL}", "http://127.0.0.1:18001")
    routes = routes.replace("${OCC_DISRUPTION_INTERNAL_URL}", "http://127.0.0.1:18002")
    routes = routes.replace("${OCC_PLANNING_INTERNAL_URL}", "http://127.0.0.1:18003")
    template = template.replace("${OCC_NGINX_HTTP_INTERNAL_PORT}", "18081")
    template = template.replace("${OCC_ORIGIN_SECRET}", "synthetic-origin-secret")
    upstream = ("server { listen 18001; location / { return 200 'upstream'; } }")
    config = """pid /tmp/nginx.pid;
error_log /dev/stderr notice;
events { worker_connections 128; }
http {
    access_log off;
    client_body_temp_path /tmp/client_body;
    proxy_temp_path /tmp/proxy;
    fastcgi_temp_path /tmp/fastcgi;
    uwsgi_temp_path /tmp/uwsgi_temp;
    scgi_temp_path /tmp/scgi;
    include /fixture/cloudfront.conf;
""" + upstream + "\n}\n"
    with tempfile.TemporaryDirectory(prefix="occ-nginx-gate-") as directory:
        fixture = Path(directory)
        (fixture / "routes.inc").write_text(routes, encoding="utf-8")
        (fixture / "cloudfront.conf").write_text(template, encoding="utf-8")
        (fixture / "nginx.conf").write_text(config, encoding="utf-8")
        gate_dir = fixture / "gate"
        gate_dir.mkdir()
        (gate_dir / "traffic-gate.inc").write_text(
            'set $occ_traffic_gate "closed";\nset $occ_gate_token "synthetic-gate-token";\n',
            encoding="utf-8")
        fixture.chmod(0o755)
        for path in fixture.rglob("*"):
            if path.is_file():
                path.chmod(0o644)
        try:
            subprocess.run(["docker", "run", "--detach", "--name", name,
                            "--user", "101:101", "--read-only", "--cap-drop", "ALL",
                            "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:mode=1777",
                            "--publish", "127.0.0.1::18081",
                            "--mount", f"type=bind,source={fixture.resolve()},target=/fixture,readonly",
                            "--mount",
                            f"type=bind,source={fixture.resolve()}/routes.inc,"
                            "target=/etc/nginx/conf.d/routes.inc,readonly",
                            "--mount", f"type=bind,source={gate_dir.resolve()},target=/etc/nginx/gate,readonly",
                            "--entrypoint", "nginx", IMAGE, "-c", "/fixture/nginx.conf",
                            "-g", "daemon off;"], check=True, capture_output=True, text=True)
            address = subprocess.check_output(["docker", "port", name, "18081/tcp"], text=True).strip()
            base = "http://" + address

            def request(path, headers):
                request = urllib.request.Request(base + path, headers=headers)
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        return response.status
                except urllib.error.HTTPError as response:
                    return response.code

            for attempt in range(30):
                try:
                    # Both deployment secrets are required: the origin gate
                    # and the closed-traffic readiness path.
                    probe = urllib.request.Request(
                        base + "/_occ_gate_readiness",
                        headers={"X-OCC-Origin-Secret": "synthetic-origin-secret",
                                 "X-OCC-Gate-Token": "synthetic-gate-token"})
                    urllib.request.urlopen(probe, timeout=2)
                    break
                except urllib.error.HTTPError as response:
                    if response.code == 204:
                        break
                    if attempt == 29:
                        raise
                    time.sleep(0.1)
                except (urllib.error.URLError, OSError):
                    if attempt == 29:
                        raise
                    time.sleep(0.1)
            token = {"X-OCC-Origin-Secret": "synthetic-origin-secret",
                     "X-OCC-Gate-Token": "synthetic-gate-token"}
            origin_only = {"X-OCC-Origin-Secret": "synthetic-origin-secret"}
            status = request("/_occ_gate_readiness", token)
            assert status == 204, f"gate readiness with the token must be served: {status}"
            for path in ("/", "/frontend-config", "/_occ_edge_health", "/api/v1/arrivals"):
                status = request(path, token)
                assert status == 503, f"gate must block {path} while closed: {status}"
                # With the origin secret but no gate token the request passes
                # the origin gate and is still blocked by the closed gate.
                status = request(path, origin_only)
                assert status == 503, f"gate must block {path} without a token: {status}"
            wrong = request("/_occ_gate_readiness", {"X-OCC-Origin-Secret": "synthetic-origin-secret",
                                                     "X-OCC-Gate-Token": "wrong"})
            assert wrong == 503, f"gate readiness with a wrong token must be blocked: {wrong}"
            print("PASS closed traffic gate serves only the secret-bearing readiness request")
        except Exception:
            subprocess.run(["docker", "logs", name], check=False)
            raise
        finally:
            subprocess.run(["docker", "rm", "--force", name], check=False, capture_output=True)


if __name__ == "__main__":
    main()
