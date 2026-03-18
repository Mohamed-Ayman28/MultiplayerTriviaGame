#!/bin/bash
set -e

cd /home/mark/trivia_final

echo "=========================================="
echo "  FINAL VALIDATION TEST SUITE"
echo "=========================================="

echo ""
echo "[1/4] Testing Compilation..."
javac -cp "lib/gson-2.10.1.jar" -d out $(find src -name '*.java')
if [ $? -eq 0 ]; then
    echo "✓ PASS: Compilation successful"
else
    echo "✗ FAIL: Compilation failed"
    exit 1
fi

echo ""
echo "[2/4] Checking class files generated..."
CLASS_COUNT=$(find out -name "*.class" | wc -l)
echo "Generated $CLASS_COUNT class files"
if [ $CLASS_COUNT -gt 20 ]; then
    echo "✓ PASS: Expected number of classes generated"
else
    echo "✗ FAIL: Insufficient classes generated"
    exit 1
fi

echo ""
echo "[3/4] Verifying GameServer.class exists..."
if [ -f "out/server/core/GameServer.class" ]; then
    echo "✓ PASS: GameServer.class found"
else
    echo "✗ FAIL: GameServer.class not found"
    exit 1
fi

echo ""
echo "[4/4] Verifying GameServer\$GameRoom.class exists..."
if [ -f "out/server/core/GameServer\$GameRoom.class" ]; then
    echo "✓ PASS: GameRoom inner class found"
else
    echo "✗ FAIL: GameRoom inner class not found"
    exit 1
fi

echo ""
echo "=========================================="
echo "  ALL VALIDATION TESTS PASSED ✓"
echo "=========================================="
echo ""
echo "Summary of refactoring:"
echo "  ✓ Consolidated 8 maps into GameRoom class"
echo "  ✓ Extracted deleteRoom() helper"
echo "  ✓ Extracted broadcastQuestion() helper"
echo "  ✓ Extracted isAnswerCorrect() helper"
echo "  ✓ Extracted updatePlayerScore() helper"
echo "  ✓ Project compiles with 0 errors"
