@echo off
rem ============================================================
rem dataforge All-in-One compose 封装脚本 (Windows/Cmd)
rem 与 startup/macos/compose.sh 功能对齐
rem
rem 用法:
rem   compose.bat up -d                  无卷模式(默认): 数据写在容器内, 不挂载命名卷
rem   set PERSIST=1 && compose.bat up -d 持久化模式: 挂载 df-pgdata / df-data 命名卷
rem   compose.bat down                   停止容器(不删卷/容器数据)
rem   compose.bat reset                  停止容器并删除命名卷数据(不可恢复)
rem   compose.bat ps / logs -f ...       其余命令透传 docker compose
rem
rem 关于旧数据卷:
rem   * 本机已存在旧卷想复用数据, 用 set PERSIST=1 && compose.bat up -d
rem   * 想彻底清除旧卷数据重新开始, 用 compose.bat reset
rem ============================================================
setlocal EnableDelayedExpansion

set "PROJECT_ROOT=%~dp0..\.."
set "COMPOSE_DIR=%~dp0..\compose"
cd /d "%PROJECT_ROOT%"

set "CMD=%1"
if "%CMD%"=="" set "CMD=up"

if /i "%CMD%"=="reset" goto :reset

set "FILES=-f "%COMPOSE_DIR%\docker-compose.yml""
if "%PERSIST%"=="1" (
  set "FILES=!FILES! -f "%COMPOSE_DIR%\docker-compose.persist.yml""
  echo ==^> 持久化模式: 挂载命名卷 df-pgdata / df-data
) else (
  echo ==^> 无卷模式: 数据写入容器内 (如需持久化请 set PERSIST=1)
)

docker compose !FILES! %*
goto :eof

:reset
echo ==^> 停止容器并清除命名卷(不可恢复)
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" -f "%COMPOSE_DIR%\docker-compose.persist.yml" down --remove-orphans
docker volume rm dataforge-docker_df-pgdata dataforge-docker_df-data 2>nul
echo ==^> 完成: 旧卷数据已清除 (set PERSIST=1 && compose.bat up -d 可重建持久化环境)
goto :eof