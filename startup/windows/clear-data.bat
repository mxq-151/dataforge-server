@echo off
rem ============================================================
rem Clear dataforge named-volume data (IRREVERSIBLE)
rem Stops/removes containers first, then deletes the
rem df-pgdata / df-data named volumes.
rem To restart a persistent environment: startup\windows\start-persist.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> Stopping containers...
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" -f "%COMPOSE_DIR%\docker-compose.persist.yml" down --remove-orphans
echo ==^> Removing named volumes...
docker volume rm dataforge-docker_df-pgdata dataforge-docker_df-data 2>nul
echo ==^> Done: old volume data cleared (run startup\windows\start-persist.bat to persist again)
endlocal