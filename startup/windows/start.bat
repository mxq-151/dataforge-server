@echo off
rem ============================================================
rem 启动 dataforge 容器(不带数据卷)
rem 数据写入容器内, 容器删除后数据随之丢失; 如需持久化请用 start-persist.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> 无卷模式: 数据写入容器内 (如需持久化请运行 startup\windows\start-persist.bat)
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" up -d
echo ==^> 启动完成
endlocal