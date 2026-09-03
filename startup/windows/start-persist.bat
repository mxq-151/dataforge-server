@echo off
rem ============================================================
rem Start dataforge containers (with named volumes)
rem Mounts named volumes df-pgdata (PostgreSQL) / df-data (/opt/dataforge);
rem data survives container removal/recreation.
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> Persist mode: mounting named volumes df-pgdata / df-data
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" -f "%COMPOSE_DIR%\docker-compose.persist.yml" up -d
echo ==^> Started (with named volumes)
endlocal