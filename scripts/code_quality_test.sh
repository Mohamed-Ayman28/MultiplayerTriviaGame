#!/bin/bash
# scripts/code_quality_test.sh
#
# Pre-/post-fix assertions for code quality issues B1-B3 and D1-D6.
#
# Usage:
#   bash scripts/code_quality_test.sh              # run all source + compile groups
#   bash scripts/code_quality_test.sh --runtime    # also run live server behavioral test
#   bash scripts/code_quality_test.sh B1           # run ONLY fix-group B1 assertions
#   bash scripts/code_quality_test.sh D1           # run ONLY fix-group D1 assertions
#   bash scripts/code_quality_test.sh CMP          # run ONLY compile check
#
# Incremental workflow:
#   Run BEFORE applying any fix  → several tests FAIL (expected).
#   Apply each fix group, re-run → that group flips to PASS.
#   When all groups pass + CMP → you are done.
#
# Fix groups tested:
#   B1   ScoreEntry fields must be private (not package-private)
#   B2   AuthController login success message: correct spelling + spacing
#   B3   LookupServer must cache questions at startup, not reload per request
#   D1   GameServer.getRandomQuestions() dead method removed
#   D2   GameServer.saveQuestions() dead method removed
#   D3   GameServer.broadcastToAll() dead method removed
#   D4   GameServer.main() duplicate entry point removed
#   D5   Config.maxTeams dead field/getter/setter removed
#   D6   Config 6-param constructor removed
#   CMP  Project compiles cleanly
#   RT   Runtime: login response text (requires --runtime)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
cd "$ROOT"

RED='\033[0;31m'; GRN='\033[0;32m'; CYN='\033[0;36m'
YLW='\033[1;33m'; BOLD='\033[1m'; RST='\033[0m'

PASS=0; FAIL=0
declare -a FAILURES

# ── argument parsing ─────────────────────────────────────────────────────────
FILTER=""
RUN_RUNTIME=0
for arg in "$@"; do
    case "$arg" in
        --runtime) RUN_RUNTIME=1 ;;
        B[0-9]|D[0-9]|CMP|RT) FILTER="$arg" ;;
    esac
done

# ── helpers ───────────────────────────────────────────────────────────────────
section() {
    echo -e "\n${CYN}${BOLD}──── $1 ────${RST}"
}

# assert file matches regex pattern
assert_contains() {
    local name="$1" pattern="$2" file="$3"
    [[ -n "$FILTER" && "${name%%_*}" != "$FILTER" ]] && return
    if grep -qE "$pattern" "$file" 2>/dev/null; then
        echo -e "  ${GRN}PASS${RST} [$name]"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${RST} [$name]"
        echo -e "       expected pattern: ${YLW}$pattern${RST}"
        echo -e "       in file:          $file"
        FAILURES+=("$name"); ((FAIL++))
    fi
}

# assert file does NOT match regex pattern
assert_absent() {
    local name="$1" pattern="$2" file="$3"
    [[ -n "$FILTER" && "${name%%_*}" != "$FILTER" ]] && return
    if ! grep -qE "$pattern" "$file" 2>/dev/null; then
        echo -e "  ${GRN}PASS${RST} [$name]"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${RST} [$name]"
        echo -e "       pattern must NOT exist: ${YLW}$pattern${RST}"
        echo -e "       in file:                $file"
        FAILURES+=("$name"); ((FAIL++))
    fi
}

# ════════════════════════════════════════════════════════════════════════════
# B1 · ScoreEntry field encapsulation
# Fields user/score/date/gametype were package-private; must be private.
# ════════════════════════════════════════════════════════════════════════════
SE="src/models/ScoreEntry.java"
section "B1 · ScoreEntry field encapsulation  ($SE)"
assert_contains "B1_user_private"     "private[[:space:]]+User[[:space:]]+user"      "$SE"
assert_contains "B1_score_private"    "private[[:space:]]+int[[:space:]]+score"      "$SE"
assert_contains "B1_date_private"     "private[[:space:]]+Date[[:space:]]+date"      "$SE"
assert_contains "B1_gametype_private" "private[[:space:]]+String[[:space:]]+gametype" "$SE"

# ════════════════════════════════════════════════════════════════════════════
# B2 · AuthController login success message
# Bug: "Login successfuly! Welcome , " — typo + spurious space before comma.
# Fix: "Login successfully! Welcome, "
# ════════════════════════════════════════════════════════════════════════════
AC="src/server/AuthController.java"
section "B2 · AuthController login message  ($AC)"
assert_absent   "B2_no_typo"           "successfuly[^l]"   "$AC"
assert_absent   "B2_no_spurious_space" "Welcome[[:space:]]+,[[:space:]]"  "$AC"
assert_contains "B2_correct_spelling"  "successfully"       "$AC"
assert_contains "B2_correct_format"    "Welcome,[[:space:]]" "$AC"

# ════════════════════════════════════════════════════════════════════════════
# B3 · LookupServer question caching
# Bug: loadQuestions() called inside handleClient on every request.
# Fix: load once in constructor into a private final field.
# ════════════════════════════════════════════════════════════════════════════
LS="src/lookup/LookupServer.java"
section "B3 · LookupServer question caching  ($LS)"
assert_contains "B3_field_declared"   "private final List<Question> questions" "$LS"
assert_absent   "B3_no_per_req_load"  "List<Question> questions = jsonLoader\.loadQuestions" "$LS"

# ════════════════════════════════════════════════════════════════════════════
# D1–D4 · GameServer dead methods removed
# ════════════════════════════════════════════════════════════════════════════
GS="src/server/GameServer.java"
section "D1–D4 · GameServer dead methods removed  ($GS)"
assert_absent "D1_no_getRandomQuestions" "public List<Question> getRandomQuestions\(" "$GS"
assert_absent "D2_no_saveQuestions"      "public void saveQuestions\(\)"              "$GS"
assert_absent "D3_no_broadcastToAll"     "public void broadcastToAll\("              "$GS"
assert_absent "D4_no_main"               "public static void main\("                 "$GS"

# ════════════════════════════════════════════════════════════════════════════
# D5–D6 · Config dead code removed
# D5: maxTeams field/getter/setter never read anywhere
# D6: 6-param constructor never called (Gson uses no-arg)
# ════════════════════════════════════════════════════════════════════════════
CF="src/models/Config.java"
section "D5–D6 · Config dead code removed  ($CF)"
assert_absent "D5_no_maxTeams_field"     "private int maxTeams"           "$CF"
assert_absent "D5_no_maxTeams_getter"    "getMaxTeams\(\)"                "$CF"
assert_absent "D5_no_maxTeams_setter"    "setMaxTeams\("                  "$CF"
assert_absent "D6_no_6param_constructor" "public Config\(int minPlayers"  "$CF"

# ════════════════════════════════════════════════════════════════════════════
# CMP · Project compiles cleanly
# ════════════════════════════════════════════════════════════════════════════
if [[ -z "$FILTER" || "$FILTER" == "CMP" ]]; then
    section "CMP · Project compiles cleanly"
    compile_out=$(javac -cp lib/gson-2.10.1.jar -d out $(find src -name '*.java') 2>&1)
    if [[ $? -eq 0 ]]; then
        echo -e "  ${GRN}PASS${RST} [CMP_compile]"
        ((PASS++))
    else
        echo -e "  ${RED}FAIL${RST} [CMP_compile]"
        echo "$compile_out" | sed 's/^/    /'
        FAILURES+=("CMP_compile"); ((FAIL++))
    fi
fi

# ════════════════════════════════════════════════════════════════════════════
# RT · Runtime behavioral check for B2 (live server)
# Requires --runtime flag.  Starts lookup + server, logs in as mark/mark123,
# asserts that the response text is "Login successfully! Welcome, mark".
# ════════════════════════════════════════════════════════════════════════════
if [[ $RUN_RUNTIME -eq 1 ]] && [[ -z "$FILTER" || "$FILTER" == "RT" ]]; then
    section "RT · Runtime: login success message  (live server)"
    if ! command -v python3 &>/dev/null; then
        echo -e "  ${YLW}SKIP${RST} [RT] — python3 not available"
    else
        python3 - <<'PYEOF'
import subprocess, time, sys, threading, os

CP = "out:lib/gson-2.10.1.jar"

class Proc:
    """Wraps a java subprocess; daemon thread collects all stdout into a buffer."""
    def __init__(self, *args):
        self.p = subprocess.Popen(
            ["java", "-cp", CP, "Main"] + list(args),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1
        )
        self._lines = []
        self._lock = threading.Lock()
        t = threading.Thread(target=self._read, daemon=True)
        t.start()

    def _read(self):
        try:
            for line in self.p.stdout:
                with self._lock:
                    self._lines.append(line.rstrip("\n"))
        except Exception:
            pass

    def send(self, msg):
        try:
            self.p.stdin.write(msg + "\n")
            self.p.stdin.flush()
        except BrokenPipeError:
            pass

    def output(self):
        with self._lock:
            return "\n".join(self._lines)

    def wait_for(self, text, timeout=5):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if text in self.output():
                return True
            time.sleep(0.1)
        return False

    def stop(self):
        try:
            self.p.terminate()
            self.p.wait(timeout=3)
        except Exception:
            pass

passed = 0
failed = 0
failures = []

def rt_pass(name):
    global passed
    print(f"  \033[0;32mPASS\033[0m [RT_{name}]")
    passed += 1

def rt_fail(name, reason):
    global failed
    print(f"  \033[0;31mFAIL\033[0m [RT_{name}]")
    print(f"       {reason}")
    failures.append(name)
    failed += 1

# ── start services ────────────────────────────────────────────────────────
lookup = Proc("lookup")
server = Proc("server")
time.sleep(2.5)

client = Proc("client")
if not client.wait_for("login]", timeout=5):
    rt_fail("server_ready", "server did not send welcome prompt within 5 s")
    lookup.stop(); server.stop(); client.stop()
    sys.exit(1)

# ── perform login ─────────────────────────────────────────────────────────
client.send("login")
client.wait_for("username", timeout=3)
client.send("mark")
client.wait_for("password", timeout=3)
client.send("mark123")
client.wait_for("MAIN MENU", timeout=4)

response = client.output()

# ── assertions ────────────────────────────────────────────────────────────

if "successfuly" in response:
    rt_fail("no_typo", "response still contains misspelled 'successfuly'")
else:
    rt_pass("no_typo")

if "Welcome , " in response:
    rt_fail("no_spurious_space", "response still contains 'Welcome , ' (spurious space before comma)")
else:
    rt_pass("no_spurious_space")

if "successfully" in response:
    rt_pass("correct_spelling")
else:
    rt_fail("correct_spelling", f"'successfully' not found in response\n       got: {repr(response[-300:])}")

if "Welcome, mark" in response:
    rt_pass("welcome_format")
else:
    rt_fail("welcome_format", f"'Welcome, mark' not found in response\n       got: {repr(response[-300:])}")

# ── teardown ──────────────────────────────────────────────────────────────
client.send("-")
time.sleep(0.3)
for proc in [client, server, lookup]:
    proc.stop()

print(f"\n  Runtime sub-total: {passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
PYEOF
        rt_exit=$?
        if [[ $rt_exit -ne 0 ]]; then
            FAILURES+=("RT_runtime_behavioral")
            ((FAIL++))
        fi
    fi
fi

# ════════════════════════════════════════════════════════════════════════════
# Summary
# ════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}════════════════════════════════════════${RST}"
echo -e "${BOLD}  Code-Quality Fix Test Summary${RST}"
echo -e "${BOLD}════════════════════════════════════════${RST}"
total=$((PASS + FAIL))
echo -e "  Total assertions : ${BOLD}$total${RST}"
echo -e "  Passed           : ${GRN}${BOLD}$PASS${RST}"
echo -e "  Failed           : ${RED}${BOLD}$FAIL${RST}"

if [[ ${#FAILURES[@]} -gt 0 ]]; then
    echo ""
    echo -e "  ${RED}Failing tests:${RST}"
    for f in "${FAILURES[@]}"; do
        echo -e "    •  $f"
    done
    echo ""
    echo -e "  ${YLW}Tip: fix the corresponding group and re-run to see it flip to PASS.${RST}"
fi

echo -e "${BOLD}════════════════════════════════════════${RST}"

if [[ $FAIL -eq 0 ]]; then
    echo -e "\n${GRN}${BOLD}ALL_QUALITY_TESTS_PASS${RST}\n"
    exit 0
else
    echo -e "\n${YLW}${BOLD}SOME_TESTS_FAILING${RST}\n"
    exit 1
fi
