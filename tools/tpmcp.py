#!/usr/bin/env python3
"""Minimal Streamable-HTTP MCP client for the Tripoder phone engine.

Uses the same HTTP-only URL configuration as agent/transport.mjs.
MCP session ids are negotiated at initialize; no certificate is configured.

Usage:
  tpmcp.py tools                      # tools/list -> JSON
  tpmcp.py call <tool> <json>|<file>  # tools/call -> JSON result payload
  tpmcp.py raw '<jsonrpc>'            # arbitrary frame
"""
import json
import os
import socket
import sys
import uuid

CONNECTION = os.environ.get(
    "TRIPODER_CONNECTION",
    os.path.join(os.environ.get("HOME", ""), ".tripoder", "tripoder-connection.json"),
)
MCP_VERSION = "2025-11-25"


class McpError(RuntimeError):
    pass


class Mcp:
    def __init__(self, connection=CONNECTION, timeout=180.0):
        with open(connection, "r", encoding="utf-8") as handle:
            self.config = json.load(handle)
        self.url = self.config["url"]
        from urllib.parse import urlparse
        url = urlparse(self.url)
        if set(self.config) != {"url"} or url.scheme != "http" or url.path != "/mcp" or url.username or url.password or url.query or url.fragment:
            raise McpError("CONNECTION_HTTP_MCP_REQUIRED")
        host_port = self.url.split("://", 1)[1].split("/", 1)[0]
        self.host, self.port = host_port.rsplit(":", 1)
        self.port = int(self.port)
        self.path = "/" + self.url.split("://", 1)[1].split("/", 1)[1]
        self.session = None
        self.sequence = 0
        self.timeout = timeout

    # ---- transport -------------------------------------------------------
    def _post(self, payload):
        body = json.dumps(payload).encode("utf-8")
        stream = socket.create_connection((self.host, self.port), timeout=self.timeout)
        try:
            lines = [
                f"POST {self.path} HTTP/1.1",
                f"Host: {self.host}:{self.port}",
                "content-type: application/json",
                "accept: application/json, text/event-stream",
                f"content-length: {len(body)}",
                "connection: close",
            ]
            if self.session:
                lines.append(f"mcp-session-id: {self.session}")
                lines.append(f"mcp-protocol-version: {MCP_VERSION}")
            request = ("\r\n".join(lines) + "\r\n\r\n").encode("utf-8") + body
            stream.sendall(request)
            chunks = []
            while True:
                piece = stream.recv(65536)
                if not piece:
                    break
                chunks.append(piece)
        finally:
            try:
                stream.close()
            except OSError:
                pass
        raw_response = b"".join(chunks)
        head, _, rest = raw_response.partition(b"\r\n\r\n")
        header_lines = head.decode("latin-1").split("\r\n")
        # The engine occasionally closes the socket without writing a status line. Raising a
        # plain IndexError here escapes Engine.call's retry loop and aborts the whole campaign,
        # so normalise it into the transport error type that triggers re-initialisation.
        if not raw_response or len(header_lines[0].split(" ")) < 2:
            raise McpError("MCP_EMPTY_RESPONSE")
        status = int(header_lines[0].split(" ")[1])
        headers = {}
        for line in header_lines[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.strip().lower()] = value.strip()
        if "chunked" in headers.get("transfer-encoding", "").lower():
            rest = _dechunk(rest)
        return status, headers, rest

    def _frame(self, method, params=None, notify=False):
        self.sequence += 1
        frame = {"jsonrpc": "2.0", "method": method}
        if not notify:
            frame["id"] = self.sequence
        if params is not None:
            frame["params"] = params
        return frame

    def rpc(self, method, params=None):
        frame = self._frame(method, params)
        status, headers, body = self._post(frame)
        if status == 202:
            return None
        if status != 200:
            raise McpError(f"MCP_HTTP_{status} {body[:200]!r}")
        content_type = headers.get("content-type", "")
        value = None
        if content_type.startswith("text/event-stream"):
            for line in body.decode("utf-8", "replace").splitlines():
                if not line.startswith("data:"):
                    continue
                try:
                    candidate = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    continue
                if candidate.get("id") == frame["id"]:
                    value = candidate
                    break
        else:
            value = json.loads(body.decode("utf-8"))
        if value is None:
            raise McpError("MCP_RESPONSE_CORRELATION_MISMATCH")
        if "error" in value:
            raise McpError(f"MCP_ERROR {value['error']}")
        return value.get("result")

    def notify(self, method, params=None):
        self._post(self._frame(method, params, notify=True))

    def init(self):
        result = self.rpc(
            "initialize",
            {
                "protocolVersion": MCP_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "tripoder-qa", "version": "1"},
            },
        )
        # session id arrives in the initialize response headers
        frame = self._frame("initialize")  # placeholder to keep ids monotonic
        del frame
        self.notify("notifications/initialized", {})
        return result

    # ---- tools -----------------------------------------------------------
    def call(self, tool, arguments=None, timeout=None):
        previous = self.timeout
        if timeout:
            self.timeout = timeout
        try:
            result = self.rpc("tools/call", {"name": tool, "arguments": arguments or {}})
        finally:
            self.timeout = previous
        content = (result or {}).get("content") or []
        text = "\n".join(item.get("text", "") for item in content if item.get("type") == "text")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = {"text": text, "raw": result}
        if (result or {}).get("isError"):
            payload = {"ok": False, "isError": True, **payload} if isinstance(payload, dict) else {"ok": False, "text": text}
        return payload


def _dechunk(data):
    out = b""
    while data:
        size_line, _, data = data.partition(b"\r\n")
        try:
            size = int(size_line.strip().split(b";")[0], 16)
        except ValueError:
            break
        if size == 0:
            break
        out += data[:size]
        data = data[size + 2 :]
    return out


class _Session(Mcp):
    """Keeps the session id issued during initialize."""

    def _post(self, payload):
        status, headers, body = super()._post(payload)
        session = headers.get("mcp-session-id")
        if session:
            self.session = session
        return status, headers, body


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    mcp = _Session()
    mcp.init()
    if argv[1] == "tools":
        result = mcp.rpc("tools/list", {})
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return 0
    if argv[1] == "call":
        argument_text = argv[3] if len(argv) > 3 else "{}"
        if argument_text != "{}" and os.path.isfile(argument_text):
            with open(argument_text, "r", encoding="utf-8") as handle:
                arguments = json.load(handle)
        else:
            arguments = json.loads(argument_text)
        print(json.dumps(mcp.call(argv[2], arguments), ensure_ascii=False, indent=1))
        return 0
    if argv[1] == "raw":
        print(json.dumps(mcp.rpc(json.loads(argv[2]).get("method"), json.loads(argv[2]).get("params")), ensure_ascii=False, indent=1))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
