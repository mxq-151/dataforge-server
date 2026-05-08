#!/bin/bash

BASE_URL="http://localhost:8080/api"
PASS=0
FAIL=0
SKIP=0
TOTAL=0
ERRORS=""
WARNINGS=""

test_api() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expected="$5"
    local timeout="${6:-30}"
    local retry="${7:-1}"

    TOTAL=$((TOTAL + 1))

    for i in $(seq 1 $retry); do
        if [ "$method" = "GET" ]; then
            response=$(curl -s --max-time "$timeout" "$BASE_URL$url" 2>&1)
        elif [ "$method" = "POST" ]; then
            response=$(curl -s --max-time "$timeout" -X POST "$BASE_URL$url" -H "Content-Type: application/json; charset=UTF-8" -d "$data" 2>&1)
        elif [ "$method" = "PUT" ]; then
            response=$(curl -s --max-time "$timeout" -X PUT "$BASE_URL$url" -H "Content-Type: application/json; charset=UTF-8" -d "$data" 2>&1)
        elif [ "$method" = "DELETE" ]; then
            response=$(curl -s --max-time "$timeout" -X DELETE "$BASE_URL$url" 2>&1)
        fi

        if echo "$response" | grep -q "$expected"; then
            PASS=$((PASS + 1))
            echo "✅ $name${retry:+ (尝试 $i/$retry)}"
            return 0
        fi

        if [ $i -lt $retry ]; then
            sleep 2
        fi
    done

    FAIL=$((FAIL + 1))
    echo "❌ $name"
    echo "   期望包含: $expected"
    if [ -z "$response" ] || [ "$response" = "" ]; then
        echo "   实际响应: (空/超时)"
    else
        echo "   实际响应: ${response:0:200}..."
    fi
    ERRORS="$ERRORS\n❌ $name"
    return 1
}

echo "=========================================="
echo "🚀 大数据平台完整端到端测试 v2.0"
echo "=========================================="
echo "📅 测试时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "📋 测试策略: 基本功能 + 集成测试(带重试)"
echo ""

echo "═════════════════════════════════════════"
echo "【模块一：数据源配置】(DatasourceConfig)"
echo "═════════════════════════════════════════"

echo "--- 1.1 数据源类型枚举 ---"
test_api "获取支持的数据源类型列表" "GET" "/devops/datasource/types" "" '"code":200'

echo ""
echo "--- 1.2 数据源基础CRUD ---"
test_api "获取空数据源列表" "GET" "/devops/datasource/list?current=1&size=10" "" '"total":0'
test_api "获取所有可用数据源(空)" "GET" "/devops/datasource/all" "" '"code":200'

echo ""
echo "--- 1.3 创建Kyuubi数据源 ---"
DS_CREATE_RESP=$(curl -s --max-time 15 -X POST "$BASE_URL/devops/datasource" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{"datasourceName":"E2E-Kyuubi-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":"","description":"端到端自动化测试"}')
test_api "创建Kyuubi数据源(含描述)" "POST" "/devops/datasource" '{"datasourceName":"E2E-Kyuubi-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":"","description":"端到端自动化测试"}' '"code":200' "15"

DS_ID=$(curl -s "$BASE_URL/devops/datasource/list?current=1&size=1" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
echo "   📝 数据源ID: $DS_ID"

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo ""
    echo "--- 1.4 数据源详情查询 ---"
    test_api "获取数据源详情" "GET" "/devops/datasource/$DS_ID" "" '"code":200'
    test_api "验证数据源名称" "GET" "/devops/datasource/$DS_ID" "" 'E2E-Kyuubi-Test'
    test_api "验证数据源类型" "GET" "/devops/datasource/$DS_ID" "" 'kyuubi'
    test_api "验证URL地址" "GET" "/devops/datasource/$DS_ID" "" '192.168.1.8:10009'

    echo ""
    echo "--- 1.5 连接测试(配置方式) ---"
    TEST_CONN_DATA='{"datasourceName":"E2E-Conn-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":""}'
    test_api "测试Kyuubi连接(20s超时)" "POST" "/devops/datasource/test" "$TEST_CONN_DATA" '"code":200' "25" "2"

    echo ""
    echo "--- 1.6 更新数据源 ---"
    UPDATE_DS_DATA="{\"id\":$DS_ID,\"datasourceName\":\"E2E-Kyuubi-Prod\",\"datasourceType\":\"kyuubi\",\"url\":\"jdbc:hive2://192.168.1.8:10009/\",\"username\":\"mxq\",\"password\":\"\",\"description\":\"已更新的生产环境\"}"
    test_api "更新数据源名称和描述" "PUT" "/devops/datasource" "$UPDATE_DS_DATA" '"code":200'

    echo ""
    echo "--- 1.7 验证更新结果 ---"
    test_api "验证更新后名称" "GET" "/devops/datasource/$DS_ID" "" 'E2E-Kyuubi-Prod'
    test_api "验证更新后描述" "GET" "/devops/datasource/$DS_ID" "" '已更新的生产环境'
else
    echo "⚠️  无法获取数据源ID，跳过后续测试"
fi

echo ""
echo "═════════════════════════════════════════"
echo "【模块二：库表管理】(Metadata)"
echo "═════════════════════════════════════════"

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo "--- 2.1 元数据同步 ---"
    test_api "触发全量元数据同步(60s)" "POST" "/metadata/sync/$DS_ID" "" '"success":true' "65" "2"
    test_api "查询最新同步日志" "GET" "/metadata/sync-log/$DS_ID" "" '"code":200'

    echo ""
    echo "--- 2.2 数据库列表 ---"
    test_api "获取数据库列表" "GET" "/metadata/databases?datasourceId=$DS_ID" '"code":200'

    echo ""
    echo "--- 2.3 表元数据查询 ---"
    test_api "获取表列表(分页)" "GET" "/metadata/tables?datasourceId=$DS_ID&size=20" '"code":200'
    test_api "搜索表(keyword=test)" "GET" "/metadata/tables?datasourceId=$DS_ID&keyword=test&size=10" '"code":200'

    TABLE_RESP=$(curl -s "$BASE_URL/metadata/tables?datasourceId=$DS_ID&size=1")
    TABLE_ID=$(echo "$TABLE_RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    TABLE_TOTAL=$(echo "$TABLE_RESP" | grep -o '"total":[0-9]*' | head -1 | cut -d':' -f2)
    echo "   📊 表总数: ${TABLE_TOTAL:-0}"

    if [ -n "$TABLE_ID" ] && [ "$TABLE_ID" != "" ] && [ "$TABLE_TOTAL" != "0" ]; then
        echo "   📝 测试用表ID: $TABLE_ID"

        echo ""
        echo "--- 2.4 表详情和字段 ---"
        test_api "获取表详情" "GET" "/metadata/table/$TABLE_ID" "" '"code":200'
        test_api "获取表的字段信息" "GET" "/metadata/table/$TABLE_ID/columns" '"code":200'

        echo ""
        echo "--- 2.5 DDL执行操作 ---"
        DB_NAME="default"

        DDL_CREATE="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"CREATE TABLE IF NOT EXISTS e2e_auto_test (id INT COMMENT \\\"主键\\\", name STRING COMMENT \\\"名称\\\", created_at TIMESTAMP) COMMENT \\\"自动化测试表\\\"\"}"
        test_api "DDL-CREATE创建表" "POST" "/metadata/ddl/execute" "$DDL_CREATE" '"success":true' "30" "2"

        DDL_ADD_COL="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"ALTER TABLE e2e_auto_test ADD COLUMN age INT COMMENT \\\"年龄\\\"\"}"
        test_api "DDL-ALTER添加列" "POST" "/metadata/ddl/execute" "$DDL_ADD_COL" '"success":true' "30" "2"

        DDL_MODIFY_COL="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"ALTER TABLE e2e_auto_test CHANGE COLUMN name user_name STRING COMMENT \\\"用户名\\\"\"}"
        test_api "DDL-ALTER修改列" "POST" "/metadata/ddl/execute" "$DDL_MODIFY_COL" '"success":true' "30" "2"

        echo ""
        echo "--- 2.6 DDL执行日志查询 ---"
        test_api "查询DDL执行日志" "GET" "/metadata/ddl/logs?datasourceId=$DS_ID&size=10" '"code":200'
        test_api "筛选CREATE类型日志" "GET" "/metadata/ddl/logs?datasourceId=$DS_ID&sqlType=CREATE&size=5" '"code":200'

        echo ""
        echo "--- 2.7 清理测试表 ---"
        DDL_DROP="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"DROP TABLE IF EXISTS e2e_auto_test\"}"
        test_api "DDL-DROP删除表" "POST" "/metadata/ddl/execute" "$DDL_DROP" '"success":true' "30" "2"
    else
        echo "⚠️  无可用表数据(总数=${TABLE_TOTAL:-0})，跳过表相关测试"
        SKIP=$((SKIP + 9))
        WARNINGS="$WARNINGS\n⚠️  库表管理模块：无元数据，跳过9项测试"
    fi
else
    echo "⚠️  无有效数据源，跳过库表管理测试"
    SKIP=$((SKIP + 12))
    WARNINGS="$WARNINGS\n⚠️  库表管理模块：数据源无效，跳过12项测试"
fi

echo ""
echo "═════════════════════════════════════════"
echo "【模块三：库表权限管理】(TablePermission)"
echo "═════════════════════════════════════════"

if [ -n "$TABLE_ID" ] && [ "$TABLE_ID" != "" ] && [ "${TABLE_TOTAL:-0}" != "0" ]; then
    echo "--- 3.1 权限CRUD ---"
    test_api "获取初始权限列表" "GET" "/system/permission/table/list" "" '"code":200'

    PERM_CREATE_DATA="{\"tableMetadataId\":$TABLE_ID,\"permissionName\":\"E2E-Select-Only\",\"canSelect\":1,\"canInsert\":0,\"canUpdate\":0,\"canDelete\":0,\"description\":\"只读权限-自动化测试\"}"
    test_api "创建表权限(只读)" "POST" "/system/permission/table" "$PERM_CREATE_DATA" '"code":200'

    PERM_RESP=$(curl -s "$BASE_URL/system/permission/table/list")
    PERM_ID=$(echo "$PERM_RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    echo "   🔐 权限ID: ${PERM_ID:-N/A}"

    if [ -n "$PERM_ID" ] && [ "$PERM_ID" != "" ]; then
        echo ""
        PERM_UPDATE_DATA="{\"id\":$PERM_ID,\"tableMetadataId\":$TABLE_ID,\"permissionName\":\"E2E-Full-Access\",\"canSelect\":1,\"canInsert\":1,\"canUpdate\":1,\"canDelete\":1,\"description\":\"完全访问权限\"}"
        test_api "更新为完全访问权限" "PUT" "/system/permission/table" "$PERM_UPDATE_DATA" '"code":200'

        echo ""
        test_api "删除表权限" "DELETE" "/system/permission/table/$PERM_ID" "" '"code":200'

        echo ""
        echo "--- 3.2 验证清理结果 ---"
        test_api "验证权限已清除" "GET" "/system/permission/table/list" "" '"total":0'
    else
        echo "⚠️  权限创建失败或无法获取ID"
        SKIP=$((SKIP + 3))
    fi
else
    echo "⚠️  无可用表，跳过权限测试"
    SKIP=$((SKIP + 5))
    WARNINGS="$WARNINGS\n⚠️  库表权限模块：无可用表，跳过5项测试"
fi

echo ""
echo "═════════════════════════════════════════"
echo "【模块四：清理与验证】"
echo "═════════════════════════════════════════"

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo "--- 4.1 清理测试数据源 ---"
    test_api "删除数据源" "DELETE" "/devops/datasource/$DS_ID" "" '"code":200' "15" "2"

    echo ""
    echo "--- 4.2 最终验证 ---"
    sleep 1
    test_api "验证数据源已删除" "GET" "/devops/datasource/list?current=1&size=10" "" '"total":0'
else
    echo "⚠️  无需清理"
fi

echo ""
echo "=========================================="
echo "📊 端到端测试报告"
echo "=========================================="
echo "✅ 通过: $PASS"
echo "❌ 失败: $FAIL"
echo "⏭️  跳过: $SKIP"
echo "📋 总计: $TOTAL"
echo ""

if [ $TOTAL -gt 0 ]; then
    RATE=$((PASS * 100 / TOTAL))
else
    RATE=0
fi
echo "📈 通过率: ${RATE}%"

if [ $FAIL -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "⚠️  失败用例详情"
    echo "=========================================="
    echo -e "$ERRORS"
fi

if [ -n "$WARNINGS" ]; then
    echo ""
    echo "=========================================="
    echo "💡 跳过原因说明"
    echo "=========================================="
    echo -e "$WARNINGS"
fi

echo ""
echo "=========================================="
if [ $FAIL -eq 0 ]; then
    echo "🎉 测试完成！所有执行的测试均通过！"
    if [ $SKIP -gt 0 ]; then
        echo "ℹ️  有 $SKIP 项测试因依赖条件未满足而跳过"
    fi
    echo "=========================================="
    exit 0
else
    echo "⚠️  测试完成！有 $FAIL 项测试需要关注！"
    echo "=========================================="
    exit 1
fi
