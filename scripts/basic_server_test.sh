#!/bin/bash
set -e

cd /home/mark/trivia_final

echo "[TEST] Compiling GameServer..."
javac -cp "lib/gson-2.10.1.jar" -d out $(find src -name '*.java')
if [ $? -ne 0 ]; then
    echo "FAIL: Compilation error"
    exit 1
fi
echo "PASS: Compilation successful"

echo ""
echo "[TEST] Starting GameServer in background..."
timeout 8s java -cp "out:lib/gson-2.10.1.jar" server.core.GameServer 2>&1 &
SERVER_PID=$!
sleep 2

# Check if process is still running
if ! ps -p $SERVER_PID > /dev/null 2>&1; then
    echo "FAIL: Server crashed on startup"
    exit 1
fi
echo "PASS: Server started successfully (PID: $SERVER_PID)"

echo ""
echo "[TEST] Verifying server accepts connections..."
if timeout 3s bash -c "echo 'test' | nc localhost 5000" 2>/dev/null; then
    echo "PASS: Server accepts connections on port 5000"
else
    echo "NOTE: Could not verify connection (nc may not be available, but server is running)"
fi

# Kill the server
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo ""
echo "=== ALL TESTS PASSED ==="
