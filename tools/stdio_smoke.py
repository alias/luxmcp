#!/usr/bin/env python3
"""
End-to-end smoke test: start the jar as an MCP stdio server, run the JSON-RPC handshake and call
the read-only tools against the real controller.

    python tools/stdio_smoke.py --host innotecwp [--jar target/luxmcp.jar]
"""

import argparse
import json
import subprocess
import sys


class Stdio:
    def __init__(self, cmd):
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=sys.stderr, text=True,
                                  encoding="utf-8", bufsize=1)
        self.next_id = 1

    def request(self, method, params=None):
        rid = self.next_id
        self.next_id += 1
        msg = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params is not None:
            msg["params"] = params
        self.p.stdin.write(json.dumps(msg) + "\n")
        self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("server closed stdout")
            resp = json.loads(line)
            if resp.get("id") == rid:
                if "error" in resp:
                    raise RuntimeError(f"{method}: {resp['error']}")
                return resp["result"]

    def notify(self, method, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self.p.stdin.write(json.dumps(msg) + "\n")
        self.p.stdin.flush()

    def close(self):
        self.p.stdin.close()
        try:
            self.p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.p.kill()


def call(s, name, args):
    res = s.request("tools/call", {"name": name, "arguments": args})
    text = "\n".join(c.get("text", "") for c in res.get("content", []))
    print(f"\n=== {name} {json.dumps(args)} (isError={res.get('isError')}) ===")
    print(text)
    return res


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--jar", default="target/luxmcp.jar")
    ap.add_argument("--allow-write", action="store_true", help="only lists the tool; no write is performed")
    a = ap.parse_args()

    cmd = ["java", "-jar", a.jar, "--host", a.host]
    if a.allow_write:
        cmd.append("--allow-write")
    s = Stdio(cmd)
    try:
        init = s.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                        "clientInfo": {"name": "stdio_smoke", "version": "0"}})
        print("server:", init["serverInfo"], "protocol", init.get("protocolVersion"))
        print("instructions:", (init.get("instructions") or "")[:120], "...")
        s.notify("notifications/initialized")
        tools = s.request("tools/list")["tools"]
        print("tools:", [t["name"] for t in tools])
        call(s, "luxtronik_get_status", {})
        call(s, "luxtronik_search_registers", {"query": "Temperatur T", "kind": "calculations", "limit": 12})
        call(s, "luxtronik_get_registers", {"names": ["ID_WEB_Temperatur_TVL", "ID_Ba_Hz_akt", "10", "nope"]})
        call(s, "luxtronik_get_registers", {"names": ["10", "11"], "kind": "calculations"})
        call(s, "luxtronik_get_errors", {})
        call(s, "luxtronik_list_writable", {})
        call(s, "luxtronik_list_registers", {"kind": "parameters", "offset": 0, "limit": 5})
        names = {t["name"] for t in tools}
        if "luxtronik_search_docs" in names:
            call(s, "luxtronik_search_docs", {"query": "Heizkurve Parallelverschiebung", "limit": 3})
            call(s, "luxtronik_search_docs", {"query": "Einsatzgrenzen Wärmequelle SWC 102", "limit": 2})
            call(s, "luxtronik_get_doc_page", {"document": "Teil2", "page": 50})
        if a.allow_write:
            # refused by the policy: not on the allowlist / out of range - no flash write happens
            call(s, "luxtronik_set_parameter", {"name": "ID_Einst_Zugangscode", "value": "1"})
            call(s, "luxtronik_set_parameter", {"name": "ID_Einst_BWS_akt", "value": "90"})
    finally:
        s.close()


if __name__ == "__main__":
    main()
