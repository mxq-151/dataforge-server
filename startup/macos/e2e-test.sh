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
            echo "[PASS] $name${retry:+ (attempt $i/$retry)}"
            return 0
        fi

        if [ $i -lt $retry ]; then
            sleep 2
        fi
    done

    FAIL=$((FAIL + 1))
    echo "[FAIL] $name"
    echo "    expected to contain: $expected"
    if [ -z "$response" ] || [ "$response" = "" ]; then
        echo "    actual response: (empty/timeout)"
    else
        echo "    actual response: ${response:0:200}..."
    fi
    ERRORS="$ERRORS\n[FAIL] $name"
    return 1
}

echo "=========================================="
echo "DataForge Full End-to-End Test v2.0"
echo "=========================================="
echo "Test time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "Strategy: basic functionality + integration tests (with retry)"
echo ""

echo "========================================="
echo "[Module 1: Datasource Config] (DatasourceConfig)"
echo "========================================="

echo "--- 1.1 Datasource type enums ---"
test_api "List supported datasource types" "GET" "/devops/datasource/types" "" '"code":200'

echo ""
echo "--- 1.2 Datasource basic CRUD ---"
test_api "Get empty datasource list" "GET" "/devops/datasource/list?current=1&size=10" "" '"total":0'
test_api "Get all available datasources (empty)" "GET" "/devops/datasource/all" "" '"code":200'

echo ""
echo "--- 1.3 Create Kyuubi datasource ---"
DS_CREATE_RESP=$(curl -s --max-time 15 -X POST "$BASE_URL/devops/datasource" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d '{"datasourceName":"E2E-Kyuubi-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":"","description":"end-to-end automated test"}')
test_api "Create Kyuubi datasource (with description)" "POST" "/devops/datasource" '{"datasourceName":"E2E-Kyuubi-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":"","description":"end-to-end automated test"}' '"code":200' "15"

DS_ID=$(curl -s "$BASE_URL/devops/datasource/list?current=1&size=1" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
echo "   Datasource ID: $DS_ID"

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo ""
    echo "--- 1.4 Datasource detail query ---"
    test_api "Get datasource detail" "GET" "/devops/datasource/$DS_ID" "" '"code":200'
    test_api "Verify datasource name" "GET" "/devops/datasource/$DS_ID" "" 'E2E-Kyuubi-Test'
    test_api "Verify datasource type" "GET" "/devops/datasource/$DS_ID" "" 'kyuubi'
    test_api "Verify URL address" "GET" "/devops/datasource/$DS_ID" "" '192.168.1.8:10009'

    echo ""
    echo "--- 1.5 Connection test (config based) ---"
    TEST_CONN_DATA='{"datasourceName":"E2E-Conn-Test","datasourceType":"kyuubi","url":"jdbc:hive2://192.168.1.8:10009/","username":"mxq","password":""}'
    test_api "Test Kyuubi connection (20s timeout)" "POST" "/devops/datasource/test" "$TEST_CONN_DATA" '"code":200' "25" "2"

    echo ""
    echo "--- 1.6 Update datasource ---"
    UPDATE_DS_DATA="{\"id\":$DS_ID,\"datasourceName\":\"E2E-Kyuubi-Prod\",\"datasourceType\":\"kyuubi\",\"url\":\"jdbc:hive2://192.168.1.8:10009/\",\"username\":\"mxq\",\"password\":\"\",\"description\":\"updated production environment\"}"
    test_api "Update datasource name and description" "PUT" "/devops/datasource" "$UPDATE_DS_DATA" '"code":200'

    echo ""
    echo "--- 1.7 Verify update result ---"
    test_api "Verify updated name" "GET" "/devops/datasource/$DS_ID" "" 'E2E-Kyuubi-Prod'
    test_api "Verify updated description" "GET" "/devops/datasource/$DS_ID" "" 'updated production environment'
else
    echo "[WARN] Could not get datasource ID, skipping subsequent tests"
fi

echo ""
echo "========================================="
echo "[Module 2: Table Management] (Metadata)"
echo "========================================="

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo "--- 2.1 Metadata sync ---"
    test_api "Trigger full metadata sync (60s)" "POST" "/metadata/sync/$DS_ID" "" '"success":true' "65" "2"
    test_api "Get latest sync log" "GET" "/metadata/sync-log/$DS_ID" "" '"code":200'

    echo ""
    echo "--- 2.2 Database list ---"
    test_api "Get database list" "GET" "/metadata/databases?datasourceId=$DS_ID" '"code":200'

    echo ""
    echo "--- 2.3 Table metadata query ---"
    test_api "Get table list (paged)" "GET" "/metadata/tables?datasourceId=$DS_ID&size=20" '"code":200'
    test_api "Search tables (keyword=test)" "GET" "/metadata/tables?datasourceId=$DS_ID&keyword=test&size=10" '"code":200'

    TABLE_RESP=$(curl -s "$BASE_URL/metadata/tables?datasourceId=$DS_ID&size=1")
    TABLE_ID=$(echo "$TABLE_RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    TABLE_TOTAL=$(echo "$TABLE_RESP" | grep -o '"total":[0-9]*' | head -1 | cut -d':' -f2)
    echo "   Total tables: ${TABLE_TOTAL:-0}"

    if [ -n "$TABLE_ID" ] && [ "$TABLE_ID" != "" ] && [ "$TABLE_TOTAL" != "0" ]; then
        echo "   Test table ID: $TABLE_ID"

        echo ""
        echo "--- 2.4 Table detail and columns ---"
        test_api "Get table detail" "GET" "/metadata/table/$TABLE_ID" "" '"code":200'
        test_api "Get table columns" "GET" "/metadata/table/$TABLE_ID/columns" '"code":200'

        echo ""
        echo "--- 2.5 DDL execution ---"
        DB_NAME="default"

        DDL_CREATE="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"CREATE TABLE IF NOT EXISTS e2e_auto_test (id INT COMMENT \\\"primary key\\\", name STRING COMMENT \\\"name\\\", created_at TIMESTAMP) COMMENT \\\"automated test table\\\"\"}"
        test_api "DDL-CREATE create table" "POST" "/metadata/ddl/execute" "$DDL_CREATE" '"success":true' "30" "2"

        DDL_ADD_COL="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"ALTER TABLE e2e_auto_test ADD COLUMN age INT COMMENT \\\"age\\\"\"}"
        test_api "DDL-ALTER add column" "POST" "/metadata/ddl/execute" "$DDL_ADD_COL" '"success":true' "30" "2"

        DDL_MODIFY_COL="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"ALTER TABLE e2e_auto_test CHANGE COLUMN name user_name STRING COMMENT \\\"user name\\\"\"}"
        test_api "DDL-ALTER modify column" "POST" "/metadata/ddl/execute" "$DDL_MODIFY_COL" '"success":true' "30" "2"

        echo ""
        echo "--- 2.6 DDL execution log query ---"
        test_api "Get DDL execution logs" "GET" "/metadata/ddl/logs?datasourceId=$DS_ID&size=10" '"code":200'
        test_api "Filter logs by type CREATE" "GET" "/metadata/ddl/logs?datasourceId=$DS_ID&sqlType=CREATE&size=5" '"code":200'

        echo ""
        echo "--- 2.7 Clean up test table ---"
        DDL_DROP="{\"datasourceId\":$DS_ID,\"databaseName\":\"$DB_NAME\",\"sql\":\"DROP TABLE IF EXISTS e2e_auto_test\"}"
        test_api "DDL-DROP drop table" "POST" "/metadata/ddl/execute" "$DDL_DROP" '"success":true' "30" "2"
    else
        echo "[WARN] No table metadata available (total=${TABLE_TOTAL:-0}), skipping table-related tests"
        SKIP=$((SKIP + 9))
        WARNINGS="$WARNINGS\n[WARN] Table management module: no metadata, skipped 9 tests"
    fi
else
    echo "[WARN] No valid datasource, skipping table management tests"
    SKIP=$((SKIP + 12))
    WARNINGS="$WARNINGS\n[WARN] Table management module: invalid datasource, skipped 12 tests"
fi

echo ""
echo "========================================="
echo "[Module 3: Table Permission Management] (TablePermission)"
echo "========================================="

if [ -n "$TABLE_ID" ] && [ "$TABLE_ID" != "" ] && [ "${TABLE_TOTAL:-0}" != "0" ]; then
    echo "--- 3.1 Permission CRUD ---"
    test_api "Get initial permission list" "GET" "/system/permission/table/list" "" '"code":200'

    PERM_CREATE_DATA="{\"tableMetadataId\":$TABLE_ID,\"permissionName\":\"E2E-Select-Only\",\"canSelect\":1,\"canInsert\":0,\"canUpdate\":0,\"canDelete\":0,\"description\":\"read-only permission - automated test\"}"
    test_api "Create table permission (read-only)" "POST" "/system/permission/table" "$PERM_CREATE_DATA" '"code":200'

    PERM_RESP=$(curl -s "$BASE_URL/system/permission/table/list")
    PERM_ID=$(echo "$PERM_RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    echo "   Permission ID: ${PERM_ID:-N/A}"

    if [ -n "$PERM_ID" ] && [ "$PERM_ID" != "" ]; then
        echo ""
        PERM_UPDATE_DATA="{\"id\":$PERM_ID,\"tableMetadataId\":$TABLE_ID,\"permissionName\":\"E2E-Full-Access\",\"canSelect\":1,\"canInsert\":1,\"canUpdate\":1,\"canDelete\":1,\"description\":\"full access\"}"
        test_api "Update to full access permission" "PUT" "/system/permission/table" "$PERM_UPDATE_DATA" '"code":200'

        echo ""
        test_api "Delete table permission" "DELETE" "/system/permission/table/$PERM_ID" "" '"code":200'

        echo ""
        echo "--- 3.2 Verify cleanup result ---"
        test_api "Verify permission cleared" "GET" "/system/permission/table/list" "" '"total":0'
    else
        echo "[WARN] Permission creation failed or ID could not be obtained"
        SKIP=$((SKIP + 3))
    fi
else
    echo "[WARN] No table available, skipping permission tests"
    SKIP=$((SKIP + 5))
    WARNINGS="$WARNINGS\n[WARN] Table permission module: no table available, skipped 5 tests"
fi

echo ""
echo "========================================="
echo "[Module 4: Cleanup and Verification]"
echo "========================================="

if [ -n "$DS_ID" ] && [ "$DS_ID" != "" ]; then
    echo "--- 4.1 Clean up test datasource ---"
    test_api "Delete datasource" "DELETE" "/devops/datasource/$DS_ID" "" '"code":200' "15" "2"

    echo ""
    echo "--- 4.2 Final verification ---"
    sleep 1
    test_api "Verify datasource deleted" "GET" "/devops/datasource/list?current=1&size=10" "" '"total":0'
else
    echo "[WARN] Nothing to clean up"
fi

echo ""
echo "=========================================="
echo "End-to-End Test Report"
echo "=========================================="
echo "[PASS] Passed: $PASS"
echo "[FAIL] Failed: $FAIL"
echo "[SKIP] Skipped: $SKIP"
echo "Total: $TOTAL"
echo ""

if [ $TOTAL -gt 0 ]; then
    RATE=$((PASS * 100 / TOTAL))
else
    RATE=0
fi
echo "Pass rate: ${RATE}%"

if [ $FAIL -gt 0 ]; then
    echo ""
    echo "=========================================="
    echo "Failed test details"
    echo "=========================================="
    echo -e "$ERRORS"
fi

if [ -n "$WARNINGS" ]; then
    echo ""
    echo "=========================================="
    echo "Skip reasons"
    echo "=========================================="
    echo -e "$WARNINGS"
fi

echo ""
echo "=========================================="
if [ $FAIL -eq 0 ]; then
    echo "Test finished! All executed tests passed!"
    if [ $SKIP -gt 0 ]; then
        echo "Info: $SKIP test(s) skipped because their preconditions were not met"
    fi
    echo "=========================================="
    exit 0
else
    echo "Test finished! $FAIL test(s) need attention!"
    echo "=========================================="
    exit 1
fi