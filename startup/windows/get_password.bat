@echo off
rem ============================================================
rem 获取当前运行脚本的用户，并根据用户名输出对应的密码
rem 用途：根据当前登录用户，返回与之匹配的预设密码
rem 对应 startup/macos/get_password.sh
rem ============================================================
setlocal

rem 配置区：在此处维护「用户名:密码」的映射(空格分隔)
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

echo 当前用户 '%CURRENT_USER%' 未在映射表中找到对应密码 1>&2
exit /b 1