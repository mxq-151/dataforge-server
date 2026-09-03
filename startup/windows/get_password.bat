@echo off
rem ============================================================
rem Get the password matching the current Windows user
rem Purpose: return the preset password for the logged-in user
rem Counterpart: startup/macos/get_password.sh
rem ============================================================
setlocal

rem Config area: maintain the "username:password" map here (space-separated)
set "USER_PASSWORDS=root:password123 admin:abc456"

set "CURRENT_USER=%USERNAME%"
set "matched="

for %%e in (%USER_PASSWORDS%) do (
  for /f "tokens=1,2 delims=:" %%a in ("%%e") do (
    if /i "%%a"=="%CURRENT_USER%" set "matched=%%b"
  )
)

if defined matched (
  echo(%matched%
  exit /b 0
)

echo Current user '%CURRENT_USER%' has no matching password in the map 1>&2
exit /b 1