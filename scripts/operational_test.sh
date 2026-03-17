#!/usr/bin/env bash
set -euo pipefail

# Operational end-to-end test runner for the Trivia app.
#
# Usage:
#   bash scripts/operational_test.sh
#   bash scripts/operational_test.sh --demo-failure
#
# Exit codes:
#   0 = all assertions passed
#   1 = one or more assertions failed
#   2 = environment/setup failure

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_FAILURE=0

if [[ "${1:-}" == "--demo-failure" ]]; then
  DEMO_FAILURE=1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found in PATH"
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found in PATH"
  exit 2
fi

export TRIVIA_ROOT="$ROOT_DIR"
export TRIVIA_DEMO_FAILURE="$DEMO_FAILURE"

python3 - <<'PY'
import os
import shutil
import subprocess
import threading
import time
import sys
from pathlib import Path

ROOT = Path(os.environ["TRIVIA_ROOT"])
DEMO_FAILURE = os.environ.get("TRIVIA_DEMO_FAILURE", "0") == "1"
CP = "out:lib/gson-2.10.1.jar"

USERS = ROOT / "src/data/users.json"
SCORES = ROOT / "src/data/scores.json"
TMP_DIR = ROOT / ".tmp_operational_test"
USERS_BAK = TMP_DIR / "users.json.bak"
SCORES_BAK = TMP_DIR / "scores.json.bak"

TMP_DIR.mkdir(exist_ok=True)
shutil.copy2(USERS, USERS_BAK)
shutil.copy2(SCORES, SCORES_BAK)

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


def send(p, msg, delay=0.2):
    try:
        p.stdin.write(msg + "\n")
        p.stdin.flush()
    except Exception:
        pass
    time.sleep(delay)


def contains(proc_name, needle):
    return needle in "\n".join(logs.get(proc_name, []))


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


def restore_data():
    if USERS_BAK.exists():
        shutil.move(str(USERS_BAK), str(USERS))
    if SCORES_BAK.exists():
        shutil.move(str(SCORES_BAK), str(SCORES))
    if TMP_DIR.exists():
        try:
            TMP_DIR.rmdir()
        except OSError:
            pass


try:
    compile_cmd = "javac -cp lib/gson-2.10.1.jar -d out $(find src -name '*.java')"
    compiled = subprocess.run(compile_cmd, cwd=ROOT, shell=True)
    if compiled.returncode != 0:
        print("FAIL: compile")
        sys.exit(2)

    lookup = start_process("lookup", ["java", "-cp", CP, "Main", "lookup", "6000"])
    server = start_process("server", ["java", "-cp", CP, "Main", "server"])
    time.sleep(2)

    c1 = start_process("c1", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    c2 = start_process("c2", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    c3 = start_process("c3", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    c4 = start_process("c4", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    time.sleep(1.5)

    # Iteration 1: auth/register
    for m in [
        "login", "ghost_user", "badpass",
        "login", "mark", "wrongpass",
        "register", "Temp User", "mark", "x",
        "register", "Temp User", "tempuser_automation", "temp123",
        "login", "tempuser_automation", "temp123",
    ]:
        send(c1, m)

    # Iteration 2: single/random/leaderboard
    send(c1, "1")
    send(c1, "any")
    send(c1, "any", 0.4)
    for _ in range(10):
        send(c1, "A", 0.08)
    time.sleep(0.8)

    send(c1, "2", 0.4)
    for _ in range(10):
        send(c1, "B", 0.08)
    time.sleep(0.8)
    send(c1, "4", 0.8)

    # Iteration 3: multiplayer private room
    for m in ["login", "ahmed", "ahmed123"]:
        send(c2, m)
    for m in ["login", "youssef", "youssef123"]:
        send(c3, m)

    for m, d in [
        ("3", 0.3), ("1", 0.3), ("room_auto", 0.7),
        ("2", 0.3), ("any", 0.2), ("any", 0.2),
        ("1", 0.2), ("no", 0.8),
    ]:
        send(c2, m, d)

    for m, d in [("3", 0.3), ("2", 0.3), ("room_auto", 1.0)]:
        send(c3, m, d)

    send(c2, "1", 1.0)
    send(c2, "A")
    send(c3, "B", 1.8)

    # Iteration 4: multiplayer public room
    send(c2, "3")
    send(c2, "3", 0.7)
    send(c3, "3")
    send(c3, "3", 1.0)
    send(c2, "C")
    send(c3, "D", 1.5)

    # Iteration 5: admin + kick
    for m in ["login", "mark", "mark123"]:
        send(c4, m)

    send(c1, "-")
    time.sleep(0.7)
    c1_admin = start_process("c1_admin", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
    time.sleep(1.0)

    for m in ["login", "admin", "admin123"]:
        send(c1_admin, m)

    send(c1_admin, "5", 0.5)
    send(c1_admin, "1", 0.9)
    send(c1_admin, "5", 0.5)
    send(c1_admin, "2", 0.5)
    send(c1_admin, "mark", 0.8)

    for p in [c1_admin, c2, c3, c4]:
        send(p, "-", 0.1)

    time.sleep(1.0)

    checks = [
        ("c1", "ERROR 404: Username not found.", "login unknown user"),
        ("c1", "ERROR 401: Wrong password.", "login wrong password"),
        ("c1", "ERROR 409: Username already taken", "register existing username"),
        ("c1", "Registered successfully! Please login.", "register new user"),
        ("c1", "Login successfuly! Welcome , tempuser_automation", "login new user"),
        ("c1", "=== SINGLE PLAYER ===", "single player start"),
        ("c1", "=== RANDOM TRIVIA ===", "random trivia start"),
        ("c1", "=== LEADERBOARD (Top 10) ===", "leaderboard shown"),
        ("c2", "Room 'room_auto' created. You are the host.", "private room created"),
        ("c3", "Joined room 'room_auto'. Waiting for host to start", "private room joined"),
        ("c2", "=== GAME STARTING!", "private game started host"),
        ("c3", "=== GAME STARTING!", "private game started guest"),
        ("c2", "Joined public room 'public-room-", "public room joined c2"),
        ("c3", "Joined public room 'public-room-", "public room joined c3"),
        ("c1_admin", "=== ADMIN PANEL ===", "admin panel access"),
        ("c1_admin", "--- All Scores ---", "admin view scores"),
        ("c1_admin", "has been kicked.", "admin kick success"),
        ("c4", "You have been kicked by admin.", "kicked client notified"),
    ]

    # Optional switch to show how failures are reported.
    if DEMO_FAILURE:
        checks.append(("c1", "THIS_STRING_DOES_NOT_EXIST", "demo failure assertion"))

    failed = 0
    for proc, needle, name in checks:
        ok = contains(proc, needle)
        print(("PASS" if ok else "FAIL") + ": " + name)
        if not ok:
            failed += 1

    print("\nSummary:")
    print(f"  total assertions: {len(checks)}")
    print(f"  passed: {len(checks) - failed}")
    print(f"  failed: {failed}")

    if failed == 0:
        print("ALL_OPERATIONAL_TESTS_PASS")
        sys.exit(0)
    else:
        print("OPERATIONAL_TESTS_HAVE_FAILURES")
        for name in ["c1", "c1_admin", "c2", "c3", "c4", "server", "lookup"]:
            if name in logs:
                print(f"\n[{name}] tail:")
                for line in logs[name][-10:]:
                    print("  " + line)
        sys.exit(1)

finally:
    stop_all()
    restore_data()
PY