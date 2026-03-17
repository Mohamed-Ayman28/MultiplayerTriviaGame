#!/usr/bin/env bash
set -euo pipefail

# Dedicated auth regression test.
# Verifies that after failed login attempts the server sends:
# - the correct error code/message
# - a fresh auth prompt: Type [login] or [register]:
#
# Usage:
#   bash scripts/auth_reprompt_test.sh
#
# Exit codes:
#   0 = all assertions passed
#   1 = one or more assertions failed
#   2 = environment/setup failure

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found in PATH"
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found in PATH"
  exit 2
fi

export TRIVIA_ROOT="$ROOT_DIR"

python3 - <<'PY'
import os
import socket
import subprocess
import threading
import time
import sys
from pathlib import Path

ROOT = Path(os.environ["TRIVIA_ROOT"])
CP = "out:lib/gson-2.10.1.jar"

processes = []
logs = {}


def start_process(name, args):
    p = subprocess.Popen(
        args,
        cwd=ROOT,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    processes.append((name, p))
    logs[name] = []

    def reader():
        for line in p.stdout:
            logs[name].append(line.rstrip("\n"))

    t = threading.Thread(target=reader, daemon=True)
    t.start()
    return p


def send(p, msg, delay=0.25):
    try:
        p.stdin.write(msg + "\n")
        p.stdin.flush()
    except Exception:
        pass
    time.sleep(delay)


def joined_output(name):
    return "\n".join(logs.get(name, []))


def wait_for(name, needle, timeout=6):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if needle in joined_output(name):
            return True
        time.sleep(0.1)
    return False


def count_occurrences_after(text, anchor, needle):
    idx = text.find(anchor)
    if idx == -1:
        return 0
    return text[idx:].count(needle)


def port_available(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def stop_all():
    for _, p in processes[::-1]:
        if p.poll() is None:
            try:
                p.terminate()
            except Exception:
                pass
    time.sleep(0.5)
    for _, p in processes[::-1]:
        if p.poll() is None:
            try:
                p.kill()
            except Exception:
                pass


passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        print(f"PASS: {name}")
        passed += 1
    else:
        print(f"FAIL: {name}")
        if detail:
            print(f"  {detail}")
        failed += 1


try:
    compile_cmd = "javac -cp lib/gson-2.10.1.jar -d out $(find src -name '*.java')"
    compiled = subprocess.run(compile_cmd, cwd=ROOT, shell=True)
    if compiled.returncode != 0:
        print("FAIL: compile")
        sys.exit(2)

    if not port_available(5000):
        print("FAIL: server startup")
        print("  Port 5000 is already in use. Stop any running game server/client terminals first.")
        sys.exit(2)

    server = start_process("server", ["java", "-cp", CP, "Main", "server"])
    if not wait_for("server", "Game Server started", timeout=8):
        print("FAIL: server startup")
        server_output = joined_output("server")
        if server_output:
            print("  Server output:")
            for line in server_output.splitlines()[-10:]:
                print("   " + line)
        sys.exit(2)

    client_404 = start_process("client_404", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    if not wait_for("client_404", "Type [login] or [register]:", timeout=8):
        print("FAIL: client_404 startup prompt")
        sys.exit(2)

    send(client_404, "login")
    send(client_404, "ghost_user_auth_test")
    send(client_404, "badpass")
    time.sleep(1.0)

    out_404 = joined_output("client_404")
    check("404 error returned", "ERROR 404: Username not found." in out_404)
    check(
        "404 path re-prompts auth",
        count_occurrences_after(out_404, "ERROR 404: Username not found.", "Type [login] or [register]:") >= 1,
        "Expected auth prompt after 404 error"
    )

    client_401 = start_process("client_401", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    if not wait_for("client_401", "Type [login] or [register]:", timeout=8):
        print("FAIL: client_401 startup prompt")
        sys.exit(2)

    send(client_401, "login")
    send(client_401, "mark")
    send(client_401, "wrongpass")
    time.sleep(1.0)

    out_401 = joined_output("client_401")
    check("401 error returned", "ERROR 401: Wrong password." in out_401)
    check(
        "401 path re-prompts auth",
        count_occurrences_after(out_401, "ERROR 401: Wrong password.", "Type [login] or [register]:") >= 1,
        "Expected auth prompt after 401 error"
    )

    for _, p in processes:
        try:
            if p.poll() is None:
                p.stdin.write("-\n")
                p.stdin.flush()
        except Exception:
            pass
    time.sleep(0.3)

    print("\nSummary:")
    print(f"  total assertions: {passed + failed}")
    print(f"  passed: {passed}")
    print(f"  failed: {failed}")

    if failed == 0:
        print("AUTH_REPROMPT_TESTS_PASS")
        sys.exit(0)
    else:
        print("AUTH_REPROMPT_TESTS_HAVE_FAILURES")
        sys.exit(1)
finally:
    stop_all()
PY
