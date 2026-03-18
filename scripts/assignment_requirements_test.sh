#!/usr/bin/env bash
set -euo pipefail

# Assignment requirements compliance test harness
#
# Usage:
#   bash scripts/assignment_requirements_test.sh
#   bash scripts/assignment_requirements_test.sh R3
#   bash scripts/assignment_requirements_test.sh A2
#
# Groups:
#   R1..R11  -> main requirements
#   A1..A4   -> additional features
#   BUILD    -> compile check

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILTER="${1:-ALL}"

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found"
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found"
  exit 2
fi

export TRIVIA_ROOT="$ROOT_DIR"
export TRIVIA_FILTER="$FILTER"

python3 - <<'PY'
import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(os.environ["TRIVIA_ROOT"])
FILTER = os.environ.get("TRIVIA_FILTER", "ALL").upper()
CP = "out:lib/gson-2.10.1.jar"

PASS = 0
FAIL = 0
SKIP = 0
FAILS = []

processes = []
logs = {}


def selected(group: str) -> bool:
    return FILTER == "ALL" or FILTER == group.upper()


def check(name: str, condition: bool, details: str = ""):
    global PASS, FAIL
    if condition:
        print(f"PASS [{name}]")
        PASS += 1
    else:
        print(f"FAIL [{name}]" + (f" :: {details}" if details else ""))
        FAIL += 1
        FAILS.append(name)


def skip(name: str, details: str = ""):
    global SKIP
    print(f"SKIP [{name}]" + (f" :: {details}" if details else ""))
    SKIP += 1


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


def wait_for(proc_name, needle, timeout=8):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if needle in "\n".join(logs.get(proc_name, [])):
            return True
        time.sleep(0.1)
    return False


def contains(proc_name, needle):
    return needle in "\n".join(logs.get(proc_name, []))


def contains_any(proc_names, needle):
    return any(contains(p, needle) for p in proc_names)


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


# ---------- BUILD ----------
if selected("BUILD") or FILTER == "ALL":
    res = subprocess.run("javac -cp lib/gson-2.10.1.jar -d out $(find src -name '*.java')", shell=True, cwd=ROOT)
    check("BUILD_compile", res.returncode == 0, "Compilation failed")

# ---------- Static checks (R2, R4, A1 wiring, A4 data) ----------
users_path = ROOT / "src/data/users.json"
questions_path = ROOT / "src/data/questions.json"
config_path = ROOT / "src/data/config.json"

if selected("R2") or FILTER == "ALL":
    try:
        questions = json.loads(questions_path.read_text(encoding="utf-8"))
        check("R2_questions_nonempty", isinstance(questions, list) and len(questions) > 0)
        schema_ok = True
        for q in questions[:20]:
            if not all(k in q for k in ["text", "category", "difficultyLevel", "choices", "correctAnswer"]):
                schema_ok = False
                break
            if not isinstance(q.get("choices"), list) or len(q["choices"]) < 4:
                schema_ok = False
                break
        check("R2_questions_schema", schema_ok, "Missing required MCQ fields")
    except Exception as e:
        check("R2_questions_json_parse", False, str(e))

if selected("R4") or FILTER == "ALL":
    check("R4_users_file_exists", users_path.exists())
    check("R4_scores_file_exists", (ROOT / "src/data/scores.json").exists())
    check("R4_config_file_exists", config_path.exists())
    check("R4_questions_file_exists", questions_path.exists())
    try:
        cfg = json.loads(config_path.read_text(encoding="utf-8"))
        ok = all(k in cfg for k in ["minPlayers", "maxPlayers", "questionTime", "warningTimes", "defaultQuestionCount"])
        check("R4_config_keys", ok)
    except Exception as e:
        check("R4_config_parse", False, str(e))

if selected("A1") or FILTER == "ALL":
    gs = (ROOT / "src/server/GameServer.java").read_text(encoding="utf-8")
    ls = (ROOT / "src/lookup/LookupServer.java").read_text(encoding="utf-8")
    check("A1_lookup_client_called", "lookupClient.fetchQuestions(" in gs)
    check("A1_lookup_concurrency_pool", "Executors.newFixedThreadPool" in ls)

if selected("A4") or FILTER == "ALL":
    try:
        users = json.loads(users_path.read_text(encoding="utf-8"))
        has_admin = any(u.get("username") == "admin" and u.get("isAdmin") for u in users)
        check("A4_admin_user_exists", has_admin)
    except Exception as e:
        check("A4_admin_user_parse", False, str(e))

# ---------- Live system checks ----------
need_live = FILTER == "ALL" or FILTER in {
    "R1", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "R11", "A2", "A3", "A4"
}

TMP = ROOT / ".tmp_assignment_req"
USERS_BAK = TMP / "users.bak"
SCORES_BAK = TMP / "scores.bak"

if need_live:
    TMP.mkdir(exist_ok=True)
    shutil.copy2(users_path, USERS_BAK)
    shutil.copy2(ROOT / "src/data/scores.json", SCORES_BAK)

    try:
        lookup = start_process("lookup", ["java", "-cp", CP, "Main", "lookup", "6000"])
        server = start_process("server", ["java", "-cp", CP, "Main", "server"])
        time.sleep(2.0)

        c1 = start_process("c1", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
        c2 = start_process("c2", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
        c3 = start_process("c3", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
        c4 = start_process("c4", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
        time.sleep(1.5)

        # R1: at least 4 concurrent clients.
        if selected("R1") or FILTER == "ALL":
            c1_ok = wait_for("c1", "Welcome to Trivia Game!", timeout=5)
            c2_ok = wait_for("c2", "Welcome to Trivia Game!", timeout=5)
            c3_ok = wait_for("c3", "Welcome to Trivia Game!", timeout=5)
            c4_ok = wait_for("c4", "Welcome to Trivia Game!", timeout=5)
            check("R1_four_clients_connected", c1_ok and c2_ok and c3_ok and c4_ok,
                  "Not all 4 clients received server welcome concurrently")

        # R3 + R4(auth) : login/register and error handling.
        if selected("R3") or selected("R11") or selected("R4") or FILTER == "ALL":
            temp_user = f"tempuser_req_{int(time.time())}"
            for m in [
                "login", "ghost_user_req", "badpass",
                "login", "mark", "wrongpass",
                "register", "Temp Req", "mark", "x",
                "register", "Temp Req", temp_user, "temp123",
                "login", temp_user, "temp123",
            ]:
                send(c1, m)
            time.sleep(1.0)
            if selected("R3") or FILTER == "ALL":
                check("R3_404_not_found", contains("c1", "ERROR 404: Username not found."))
                check("R3_401_wrong_password", contains("c1", "ERROR 401: Wrong password."))
                check("R3_register_conflict", contains("c1", "ERROR 409: Username already taken"))
                check("R3_register_success", contains("c1", "Registered successfully! Please login."))

        # R5 menu options after login.
        if selected("R5") or selected("A3") or FILTER == "ALL":
            check("R5_menu_single", contains("c1", "[1] Single Player"))
            check("R5_menu_random", contains("c1", "[2] Random Trivia"))
            check("R5_menu_multi", contains("c1", "[3] Multiplayer"))
            check("R5_menu_leaderboard", contains("c1", "[4] Leaderboard"))

        # R4 quit anytime via '-'
        if selected("R4") or FILTER == "ALL":
            send(c4, "-")
            time.sleep(0.5)
            check("R4_quit_dash", contains("c4", "Goodbye!"))
            c4 = start_process("c4b", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
            time.sleep(0.8)

        # c2/c3/c4 login known users.
        for m in ["login", "ahmed", "ahmed123"]:
            send(c2, m)
        for m in ["login", "youssef", "youssef123"]:
            send(c3, m)
        for m in ["login", "mohamed", "mohamed123"]:
            send(c4, m)

        # R6 team-mode setup validation + valid setup.
        if selected("R6") or selected("R7") or selected("R8") or selected("R9") or selected("R10") or selected("R11") or selected("A2") or FILTER == "ALL":
            # Create room with 3 players first to force invalid team setup (odd player count)
            send(c2, "3", 0.3)
            send(c2, "1", 0.3)
            send(c2, "req_room", 0.8)
            send(c3, "3", 0.3)
            send(c3, "2", 0.3)
            send(c3, "req_room", 0.8)
            send(c4, "3", 0.3)
            send(c4, "2", 0.3)
            send(c4, "req_room", 0.8)

            # Host configure with teams while odd players -> expected invalid setup
            send(c2, "2", 0.2)
            send(c2, "any", 0.2)
            send(c2, "any", 0.2)
            send(c2, "1", 0.2)
            send(c2, "yes", 0.2)
            send(c2, "TeamA", 0.2)
            send(c2, "TeamB", 0.2)
            send(c2, "A", 0.1)
            send(c2, "B", 0.1)
            send(c2, "A", 0.6)

            if selected("R6") or FILTER == "ALL":
                check("R6_unequal_team_rejected", contains("c2", "Invalid setup."))

            # c1 joins to make player count even = 4
            send(c1, "3", 0.3)
            send(c1, "2", 0.3)
            send(c1, "req_room", 1.0)

            # Valid team setup now
            send(c2, "2", 0.2)
            send(c2, "any", 0.2)
            send(c2, "any", 0.2)
            send(c2, "1", 0.2)
            send(c2, "yes", 0.2)
            send(c2, "Red", 0.2)
            send(c2, "Blue", 0.2)
            send(c2, "A", 0.1)
            send(c2, "B", 0.1)
            send(c2, "A", 0.1)
            send(c2, "B", 0.7)

            if selected("R6") or FILTER == "ALL":
                check("R6_valid_team_setup", contains("c2", "Game setup updated successfully."))

            # Start game
            send(c2, "1", 1.5)

            # R7/R11 answer semantics + malformed answer
            send(c1, "a", 0.15)   # case-insensitive input
            send(c1, "b", 0.2)    # duplicate answer from same user
            send(c3, "Z", 0.2)    # malformed
            send(c4, "B", 0.2)
            send(c2, "C", 0.3)

            # Wait for game stream
            time.sleep(3.0)

            if selected("R7") or FILTER == "ALL":
                check("R7_case_insensitive_accept", contains("c1", "Answer locked: A"))
                check("R7_duplicate_ignored", contains("c1", "You already answered this question"))
            if selected("R11") or FILTER == "ALL":
                check("R11_malformed_input_handled", contains("c3", "Invalid answer format. Use only A/B/C/D."))

            # R8 warning updates: force one single-player question timeout later

            if selected("R9") or FILTER == "ALL":
                deadline = time.time() + 15
                while time.time() < deadline and not contains_any(["c1", "c2", "c3", "c4"], "=== GAME OVER ==="):
                    time.sleep(0.25)
                check("R9_game_over_broadcast", contains_any(["c1", "c2", "c3", "c4"], "=== GAME OVER ==="))
                check("R9_player_details_broadcast", contains_any(["c1", "c2", "c3", "c4"], "=== PLAYER DETAILS ==="))

        # R8 + A3 + R10 via fresh dedicated user session
        if selected("R8") or selected("A3") or selected("R10") or FILTER == "ALL":
            c5 = start_process("c5", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
            time.sleep(0.8)

            for m in [
                "register", "Temp Hist", "tempuser_hist", "hist123",
                "login", "tempuser_hist", "hist123",
            ]:
                send(c5, m, 0.3)

            send(c5, "1", 0.3)  # single player
            send(c5, "any", 0.2)
            send(c5, "any", 0.2)
            # Wait long enough to trigger warning messages (questionTime default 15 => 10s warning at +5s)
            time.sleep(6.0)
            send(c5, "A", 0.15)
            for _ in range(9):
                send(c5, "A", 0.08)

            send(c5, "2", 0.3)  # random trivia
            for _ in range(10):
                send(c5, "B", 0.08)

            send(c5, "4", 1.0)  # leaderboard

            if selected("R8") or FILTER == "ALL":
                check("R8_time_warning_updates", contains("c5", "seconds remaining!"))
            if selected("A3") or FILTER == "ALL":
                check("A3_random_trivia_played", contains("c5", "=== RANDOM TRIVIA ==="))
            if selected("R10") or FILTER == "ALL":
                check("R10_history_section", contains("c5", "=== YOUR LAST GAMES ==="))

        # A2 public room
        if selected("A2") or FILTER == "ALL":
            send(c2, "3", 0.3)
            send(c2, "3", 0.8)
            send(c3, "3", 0.3)
            send(c3, "3", 1.2)
            check("A2_public_room_join", contains("c2", "Joined public room 'public-room-") and contains("c3", "Joined public room 'public-room-"))
            check("A2_public_room_auto_start", contains("c2", "=== GAME STARTING!") or contains("c3", "=== GAME STARTING!"))

        # A4 admin panel runtime behavior
        if selected("A4") or FILTER == "ALL":
            cadmin = start_process("cadmin", ["java", "-cp", CP, "Main", "client", "localhost", "5000"])
            time.sleep(0.8)
            for m in ["login", "admin", "admin123", "5", "1"]:
                send(cadmin, m, 0.4)
            check("A4_admin_panel_access", contains("cadmin", "=== ADMIN PANEL ==="))
            check("A4_admin_stats_access", contains("cadmin", "--- Stats ---"))

        # tidy exits
        for nm, p in list(processes):
            if nm.startswith("c"):
                send(p, "-", 0.05)

    finally:
        stop_all()
        if USERS_BAK.exists():
            shutil.move(str(USERS_BAK), str(users_path))
        if SCORES_BAK.exists():
            shutil.move(str(SCORES_BAK), str(ROOT / "src/data/scores.json"))
        if TMP.exists():
            try:
                TMP.rmdir()
            except OSError:
                pass

print("\nSummary:")
print(f"  passed: {PASS}")
print(f"  failed: {FAIL}")
print(f"  skipped: {SKIP}")
if FAILS:
    print("  failing tests:")
    for f in FAILS:
        print("   -", f)

if FAIL == 0:
    print("ALL_REQUIREMENT_TESTS_PASS")
    raise SystemExit(0)
else:
    print("REQUIREMENT_TESTS_HAVE_FAILURES")
    raise SystemExit(1)
PY