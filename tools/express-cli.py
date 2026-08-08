#!/usr/bin/env python3
"""ExpressAssistant local CLI.

Talks to the ExpressAssistant app's local HTTP API on the phone through an adb
port forward. The app must be running (started once after install) so the API
server on 127.0.0.1:8765 is alive.

Usage examples:
  python3 express-cli.py list
  python3 express-cli.py list --json
  python3 express-cli.py detail <mailNo>
  python3 express-cli.py sync
  python3 express-cli.py track <mailNo> on
  python3 express-cli.py rename <mailNo> "新名称"
  python3 express-cli.py export -o express.json
  python3 express-cli.py mcp-tools
  python3 express-cli.py mcp summarize '{"question":"汇总我的快递"}'
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.request

PORT = 8765
BASE = f"http://127.0.0.1:{PORT}"


def find_adb():
    candidates = [
        os.environ.get("ADB"),
        shutil_which("adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        "/usr/local/bin/adb",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return "adb"


def shutil_which(name):
    try:
        import shutil
        return shutil.which(name)
    except Exception:
        return None


def run_adb(args):
    cmd = [find_adb()] + args
    return subprocess.run(cmd, capture_output=True, text=True)


def ensure_forward(device=None):
    args = ["forward", f"tcp:{PORT}", f"tcp:{PORT}"]
    if device:
        args = ["-s", device] + args
    r = run_adb(args)
    if r.returncode != 0:
        print("adb forward failed:", r.stderr.strip(), file=sys.stderr)
        sys.exit(1)


def request(method, path, payload=None, raw=False):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        print("HTTP", e.code, body, file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print("请求失败，请确认 App 已打开且已允许本地接口:", e, file=sys.stderr)
        sys.exit(1)
    return body if raw else json.loads(body)


def cmd_health(args):
    ensure_forward(args.device)
    print(json.dumps(request("GET", "/api/health"), ensure_ascii=False, indent=2))


def cmd_list(args):
    ensure_forward(args.device)
    items = request("GET", "/api/express")
    if args.json:
        print(json.dumps(items, ensure_ascii=False, indent=2))
        return
    if not items:
        print("暂无快递")
        return
    print(f"{'公司':<12}{'单号':<20}{'状态':<10}{'跟踪':<4}{'预计':<14}最新动态")
    for it in items:
        eta = it.get("eta") or "-"
        track = "是" if it.get("tracked") else "否"
        latest = (it.get("latestText") or "").replace("\n", " ")[:24]
        print(f"{it.get('companyName','')[:12]:<12}{it.get('mailNo',''):<20}"
              f"{it.get('stateName','')[:10]:<10}{track:<4}{eta[:14]:<14}{latest}")


def cmd_detail(args):
    ensure_forward(args.device)
    data = request("GET", f"/api/express/{args.mail_no}")
    print(json.dumps(data, ensure_ascii=False, indent=2))


def cmd_sync(args):
    ensure_forward(args.device)
    print(json.dumps(request("POST", "/api/sync"), ensure_ascii=False, indent=2))


def cmd_track(args):
    ensure_forward(args.device)
    tracked = args.state.lower() in ("on", "true", "1", "yes")
    print(json.dumps(request("POST", "/api/track", {"mailNo": args.mail_no, "tracked": tracked}),
                     ensure_ascii=False, indent=2))


def cmd_rename(args):
    ensure_forward(args.device)
    print(json.dumps(request("POST", "/api/rename", {"mailNo": args.mail_no, "name": args.name}),
                     ensure_ascii=False, indent=2))


def cmd_export(args):
    ensure_forward(args.device)
    items = request("GET", "/api/express")
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)
    print(f"已导出 {len(items)} 条到 {args.output}")


def mcp_call(method, params=None):
    payload = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params is not None:
        payload["params"] = params
    return request("POST", "/mcp", payload)


def cmd_mcp_tools(args):
    ensure_forward(args.device)
    data = mcp_call("tools/list")
    tools = data.get("result", {}).get("tools", [])
    for t in tools:
        print(f"{t.get('name')}: {t.get('description')}")


def cmd_mcp(args):
    ensure_forward(args.device)
    args_json = args.args_json or "{}"
    try:
        arguments = json.loads(args_json)
    except json.JSONDecodeError:
        print("arguments 不是合法 JSON", file=sys.stderr)
        sys.exit(1)
    data = mcp_call("tools/call", {"name": args.tool, "arguments": arguments})
    if "error" in data:
        print(json.dumps(data["error"], ensure_ascii=False, indent=2))
        sys.exit(1)
    content = data.get("result", {}).get("content", [])
    for item in content:
        if item.get("type") == "text":
            print(item.get("text", ""))


def main():
    p = argparse.ArgumentParser(description="ExpressAssistant local CLI")
    p.add_argument("--device", default=None, help="adb 设备序列号（多设备时使用）")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("health", help="检查本地接口是否可用").set_defaults(func=cmd_health)

    lp = sub.add_parser("list", help="列出快递")
    lp.add_argument("--json", action="store_true", help="输出原始 JSON")
    lp.set_defaults(func=cmd_list)

    dp = sub.add_parser("detail", help="查看某个快递的完整轨迹")
    dp.add_argument("mail_no")
    dp.set_defaults(func=cmd_detail)

    sub.add_parser("sync", help="触发小米同步").set_defaults(func=cmd_sync)

    tp = sub.add_parser("track", help="开启/关闭快递跟踪")
    tp.add_argument("mail_no")
    tp.add_argument("state", choices=["on", "off"])
    tp.set_defaults(func=cmd_track)

    rp = sub.add_parser("rename", help="修改快递名称")
    rp.add_argument("mail_no")
    rp.add_argument("name")
    rp.set_defaults(func=cmd_rename)

    ep = sub.add_parser("export", help="导出快递列表 JSON")
    ep.add_argument("-o", "--output", default="express.json")
    ep.set_defaults(func=cmd_export)

    sub.add_parser("mcp-tools", help="列出 MCP 工具").set_defaults(func=cmd_mcp_tools)

    mp = sub.add_parser("mcp", help="调用 MCP 工具（如 summarize）")
    mp.add_argument("tool")
    mp.add_argument("args_json", nargs="?", help="工具参数的 JSON 字符串，如 '{\"question\":\"汇总\"}'")
    mp.set_defaults(func=cmd_mcp)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
