# Multiplayer Trivia Game - Run Guide
## Requirements
- JDK 17 (or newer Java 17-compatible JDK)
- Windows PowerShell (or any shell)

## Compile
Run from project root:

```bash
javac -cp "lib/gson-2.10.1.jar" -d out $(find src -name '*.java')
```
## Start Lookup Server
Open terminal 1:
```powershell
java -cp "out;lib/gson-2.10.1.jar" Main lookup 6000
```

## Start Game Server
Open terminal 2:
```powershell
java -cp "out;lib/gson-2.10.1.jar" Main server
```

## Start Client(s)
Open terminal 3 (and more for multiplayer):
```powershell
java -cp "out;lib/gson-2.10.1.jar" Main client localhost 5000
```

## Usage Flow
1. Type `login` or `register`.
2. For login: enter username and password.
3. For registration: enter name, username, and password.
4. From menu:
   - `1` Single Player
   - `2` Random Trivia
   - `3` Multiplayer  // for teams or join room
   - `4` Leaderboard
   - `5` Admin Panel (admin only)
5. Quit anytime using `-`.

## Test Accounts (from data)
- admin / admin123
- mohamed / mohamed123
- mark / mark123
- ahmed / ahmed123
- youssef / youssef123