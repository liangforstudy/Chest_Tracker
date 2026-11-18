"""Call the MCPFabric bridge: python mcprpc.py <method> '<json params>'. Never prints the token.

The token is read from the dev client's MCPFabric config: set MCPFABRIC_CONFIG to its path
(<run dir>/config/mcpfabric.config.json), or run this from the run directory.
"""
import json, os, sys, urllib.request

cfg = json.load(open(os.environ.get('MCPFABRIC_CONFIG', os.path.join('config', 'mcpfabric.config.json'))))
method = sys.argv[1]
params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
req = urllib.request.Request(f"http://127.0.0.1:{cfg.get('port', 25599)}/rpc",
                             data=json.dumps({"method": method, "params": params}).encode(),
                             headers={"content-type": "application/json", "authorization": "Bearer " + cfg["token"]})
try:
    body = urllib.request.urlopen(req, timeout=60).read().decode()
except urllib.error.HTTPError as e:
    body = e.read().decode()
out = json.loads(body)
if method == "vision.screenshot" and out.get("ok"):
    import base64
    r = out["result"]
    data = r.get("base64") or r.get("data") or r.get("image")
    path = sys.argv[3] if len(sys.argv) > 3 else "shot.png"
    if data:
        open(path, "wb").write(base64.b64decode(data))
        print("saved", path)
    else:
        print({k: (v if len(str(v)) < 200 else "...") for k, v in r.items()})
else:
    print(json.dumps(out, ensure_ascii=False)[:6000])
