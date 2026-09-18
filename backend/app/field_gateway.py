"""Small authenticated entry point for a temporary Jeonju field tunnel.

Run on loopback; the full local API and its review/admin routes stay private.
This is a single-team PoC access code, not multi-user production authentication.
"""
from __future__ import annotations

import asyncio
import hmac
import http.client
import json
import os
import re
from urllib.parse import urlsplit

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

MAX_BODY_BYTES = 64 * 1024
REROUTE_PATH = re.compile(r"/route/sessions/[A-Za-z0-9_-]{8,80}/reroute")


def create_gateway(*, token: str | None = None, upstream: str | None = None) -> FastAPI:
    secret = token if token is not None else os.environ.get("NAVI_FIELD_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", secret):
        raise RuntimeError("NAVI_FIELD_TOKEN must be a random 32-128 character URL-safe access code")
    origin = urlsplit(upstream or os.environ.get("NAVI_FIELD_UPSTREAM", "http://127.0.0.1:8000"))
    if (origin.scheme != "http" or origin.hostname != "127.0.0.1" or origin.path not in ("", "/")
            or origin.username or origin.password or origin.query or origin.fragment):
        raise RuntimeError("The field gateway upstream must be a loopback HTTP origin")
    port = origin.port or 8000
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    def forward(method: str, path: str, body: bytes) -> Response:
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        try:
            connection.request(method, path, body=body or None, headers={
                "Accept": "application/json", "Content-Type": "application/json",
            })
            result = connection.getresponse()
            # No redirects, cookies, authorization headers or arbitrary origins are forwarded.
            if 300 <= result.status < 400:
                return JSONResponse({"detail": "Upstream redirect refused"}, status_code=502)
            return Response(result.read(), status_code=result.status, media_type="application/json",
                            headers={"Cache-Control": "no-store"})
        except (OSError, http.client.HTTPException):
            return JSONResponse({"detail": "Local Jeonju server unavailable"}, status_code=503)
        finally:
            connection.close()

    @app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"])
    async def handle(request: Request, path: str):
        route = "/" + path
        allowed = (request.method == "GET" and route in ("/health", "/demo/jeonju")) or (
            request.method == "POST" and (route == "/route" or REROUTE_PATH.fullmatch(route)))
        if not allowed or request.url.query:
            return JSONResponse({"detail": "Not found"}, status_code=404)
        authorization = request.headers.get("authorization", "").encode("utf-8")
        if not hmac.compare_digest(authorization, ("Bearer " + secret).encode("ascii")):
            return JSONResponse({"detail": "접속 코드를 확인하세요."}, status_code=401,
                                headers={"WWW-Authenticate": "Bearer"})
        body = bytearray()
        try:
            async with asyncio.timeout(15):
                async for chunk in request.stream():
                    body.extend(chunk)
                    if len(body) > MAX_BODY_BYTES:
                        return JSONResponse({"detail": "Request too large"}, status_code=413)
        except TimeoutError:
            return JSONResponse({"detail": "Request timeout"}, status_code=408)
        if request.method == "POST":
            try:
                payload = json.loads(body)
            except (ValueError, UnicodeError):
                return JSONResponse({"detail": "Invalid JSON"}, status_code=400)
            if not isinstance(payload, dict):
                return JSONResponse({"detail": "JSON object required"}, status_code=400)
            if route == "/route" and payload.get("profile") != "demo_jeonju":
                return JSONResponse({"detail": "Only demo_jeonju is enabled"}, status_code=403)
            if route != "/route" and not all(key in payload for key in (
                "current_position", "region_id", "dataset_revision", "scope_revision", "graph_sha256",
                "graph_revision", "expected_route_revision", "event_id",
            )):
                return JSONResponse({"detail": "Jeonju reroute contract required"}, status_code=403)
        return await run_in_threadpool(forward, request.method, route, bytes(body))

    return app
