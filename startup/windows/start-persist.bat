@echo off
rem ============================================================
rem 启动 dataforge 容器(带数据卷)
rem 挂载命名卷 df-pgdata(PostgreSQL) / df-data(/opt/dataforge),
rem 容器删除/重建后数据依旧保留。
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> 持久化模式: 挂载命名卷 df-pgdata / df-data
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" -f "%COMPOSE_DIR%\docker-compose.persist.yml" up -d
echo ==^> 启动完成(带数据卷)
endlocal