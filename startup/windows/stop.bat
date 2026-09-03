@echo off
rem ============================================================
rem 停止并移除 dataforge 容器(不删除数据卷/容器数据)
rem 数据保留; 如需彻底清除命名卷数据, 请用 clear-data.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> 停止容器
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" down --remove-orphans
echo ==^> 容器已停止 (数据保留; 如需彻底清除数据卷请运行 startup\windows\clear-data.bat)
endlocal