@echo off
rem ============================================================
rem Stop and remove dataforge containers (does NOT delete
rem volumes/container data)
rem Data is kept; to fully clear named-volume data, use clear-data.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> Stopping containers...
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" down --remove-orphans
echo ==^> Containers stopped (data kept; run startup\windows\clear-data.bat to fully clear volumes)
endlocal