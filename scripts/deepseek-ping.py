#!/usr/bin/env python3
"""
Verify DeepSeek API connectivity. Reads .env, sends a minimal chat request,
prints response or error. Exit 0 = success, non-zero = failure.
"""
import os
import sys
import json
import urllib.request
import urllib.error
from pathlib import Path

def load_env(path):
    if not path.exists():
        return {}
    env = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        env[k.strip()] = v.strip()
    return env

def main():
    repo_root = Path(__file__).resolve().parent.parent
    env = load_env(repo_root / ".env")
    base = env.get("DEEPSEEK_API_BASE") or os.environ.get("DEEPSEEK_API_BASE")
    key = env.get("DEEPSEEK_API_KEY") or os.environ.get("DEEPSEEK_API_KEY")
    model = env.get("DEEPSEEK_MODEL") or os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")

    missing = [n for n, v in [("DEEPSEEK_API_BASE", base), ("DEEPSEEK_API_KEY", key)] if not v]
    if missing:
        print(f"ERROR: missing env vars: {missing}", file=sys.stderr)
        return 2

    url = base.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a connectivity test."},
            {"role": "user", "content": "Reply with exactly: PONG"},
        ],
        "max_tokens": 16,
        "temperature": 0.0,
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"HTTPError {e.code}: {e.read().decode('utf-8', errors='ignore')}", file=sys.stderr)
        return 3
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 4

    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError):
        print(f"Unexpected response shape: {body}", file=sys.stderr)
        return 5

    print(f"OK model={model} response={content!r}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
