@echo off
rem ============================================================
rem 清除 dataforge 命名卷数据(不可恢复)
rem 先停止/移除容器, 再删除 df-pgdata / df-data 命名卷。
rem 重新开始持久化环境: startup\windows\start-persist.bat
rem ============================================================
setlocal
set "COMPOSE_DIR=%~dp0..\compose"
echo ==^> 停止容器
docker compose -f "%COMPOSE_DIR%\docker-compose.yml" -f "%COMPOSE_DIR%\docker-compose.persist.yml" down --remove-orphans
echo ==^> 清除命名卷数据
docker volume rm dataforge-docker_df-pgdata dataforge-docker_df-data 2>nul
echo ==^> 完成: 旧卷数据已清除 (重新持久化请运行 startup\windows\start-persist.bat)
endlocal