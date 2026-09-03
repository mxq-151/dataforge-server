@echo off
rem ============================================================
rem Start dataforge containers (without named volumes)
rem Data is written inside the container and is lost when the
rem container is removed; for persistence use start-persist.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> No-volume mode: data is written inside the container (run startup\windows\start-persist.bat for persistence)
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" up -d
echo ==^> Started
endlocal