#!/usr/bin/env bash
# 生成器：由 Olist CSV 生成自包含的 PostgreSQL 初始化脚本 (test_db-postgresql.sql)
set -euo pipefail

OLIST_DIR="$HOME/Downloads/olist"
DDL="/Users/mxq/Desktop/dataforge-docker/dataforge-server/build/sql/olist-pg-ddl.sql"
OUT="/Users/mxq/Desktop/dataforge-docker/dataforge-server/src/main/resources/sql/testdata/test_db-postgresql.sql"

# ---------- 文件头：建库建账号(幂等)、切库、会话设置、清理被替换的广告源表 ----------
{
cat <<'HDR'
-- ============================================================
-- test_db - PostgreSQL 演示业务库 (Olist 巴西电商数据集)
-- 数据来源: https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce
-- 数据目录: ~/download/olist
-- 说明: 替换原「广告初始化数据」(ad_events/ads/campaigns/users 合成数据)，
--       改为 Olist 巴西电商真实业务数据(9 张表)，数据以 COPY FROM STDIN 内嵌。
--       该文件自包含，执行时无需额外依赖 CSV 文件。
-- 执行方式: psql -U <superuser> -d dataforge -f test_db-postgresql.sql
-- ============================================================

-- 1. 建库 test_db 与账号 test_tenant（密码 123456），均幂等
SELECT 'CREATE DATABASE test_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'test_db')\gexec
SELECT 'CREATE ROLE test_tenant LOGIN PASSWORD ''123456''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'test_tenant')\gexec
ALTER DATABASE test_db OWNER TO test_tenant;

-- 2. 切换到 test_db
\connect test_db

-- 3. 会话设置与基础授权
SET standard_conforming_strings = off;
GRANT CONNECT ON DATABASE test_db TO test_tenant;

-- 3.1 使用与数据源 URL 约定一致的 schema(test_db) 并授权，保证 currentSchema=test_db 可解析
CREATE SCHEMA IF NOT EXISTS test_db;
GRANT USAGE, CREATE ON SCHEMA test_db TO test_tenant;
SET search_path = test_db, public;

-- 4. 清理被替换的原广告源表（历史广告初始化数据）
DROP TABLE IF EXISTS ad_events;
DROP TABLE IF EXISTS ads;
DROP TABLE IF EXISTS campaigns;
DROP TABLE IF EXISTS users;

-- 4.1 清理 Olist 表（保证脚本可重复执行，全量重建）
DROP TABLE IF EXISTS product_category_name_translation;
DROP TABLE IF EXISTS olist_geolocation_dataset;
DROP TABLE IF EXISTS olist_order_reviews_dataset;
DROP TABLE IF EXISTS olist_order_payments_dataset;
DROP TABLE IF EXISTS olist_order_items_dataset;
DROP TABLE IF EXISTS olist_orders_dataset;
DROP TABLE IF EXISTS olist_products_dataset;
DROP TABLE IF EXISTS olist_sellers_dataset;
DROP TABLE IF EXISTS olist_customers_dataset;

---------------------------------------------------------------
-- 5. Olist 建表 (含表/字段注释)
---------------------------------------------------------------
HDR

# 追加 DDL：只保留 "CREATE TABLE 关键字" 之后的内容(建表+注释+索引)
awk 'BEGIN{p=0} /^CREATE TABLE /{p=1} p{print}' "$DDL"

echo ""
echo "---------------------------------------------------------------"
echo "-- 6. 数据导入 (COPY FROM STDIN, 内嵌 CSV)"
echo "---------------------------------------------------------------"

# 导入顺序（先维度后事实）
TABLES="olist_customers_dataset olist_sellers_dataset olist_products_dataset product_category_name_translation olist_orders_dataset olist_order_items_dataset olist_order_payments_dataset olist_order_reviews_dataset olist_geolocation_dataset"

# 归一化每个 CSV：去 BOM、去 \r(CRLF->LF)、保证末行换行，避免 PG COPY(csv) 报"unquoted newline"
NORM_DIR="${TMPDIR:-/tmp}/olist_norm_$$"
mkdir -p "$NORM_DIR"
for tbl in $TABLES; do
  python3 - "$OLIST_DIR/$tbl.csv" "$NORM_DIR/$tbl.csv" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
raw = open(src, 'rb').read()
if raw.startswith(b'\xef\xbb\xbf'):
    raw = raw[3:]
raw = raw.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
if not raw.endswith(b'\n'):
    raw += b'\n'
open(dst, 'wb').write(raw)
PY
done

cols_for() {
  case "$1" in
    olist_customers_dataset)       echo "customer_id,customer_unique_id,customer_zip_code_prefix,customer_city,customer_state";;
    olist_sellers_dataset)         echo "seller_id,seller_zip_code_prefix,seller_city,seller_state";;
    olist_products_dataset)        echo "product_id,product_category_name,product_name_length,product_description_length,product_photos_qty,product_weight_g,product_length_cm,product_height_cm,product_width_cm";;
    product_category_name_translation) echo "product_category_name,product_category_name_english";;
    olist_orders_dataset)          echo "order_id,customer_id,order_status,order_purchase_timestamp,order_approved_at,order_delivered_carrier_date,order_delivered_customer_date,order_estimated_delivery_date";;
    olist_order_items_dataset)     echo "order_id,order_item_id,product_id,seller_id,shipping_limit_date,price,freight_value";;
    olist_order_payments_dataset)  echo "order_id,payment_sequential,payment_type,payment_installments,payment_value";;
    olist_order_reviews_dataset)   echo "review_id,order_id,review_score,review_comment_title,review_comment_message,review_creation_date,review_answer_timestamp";;
    olist_geolocation_dataset)     echo "geolocation_zip_code_prefix,geolocation_lat,geolocation_lng,geolocation_city,geolocation_state";;
  esac
}

for tbl in $TABLES; do
  cols=$(cols_for "$tbl")
  echo ""
  echo "COPY $tbl ($cols) FROM STDIN WITH (FORMAT csv, HEADER true);"
  # awk 逐行输出；END 处保证 \. 独立成行（即使 CSV 末行无换行也不会粘连）
  awk '{print} END{print "\\."}' "$NORM_DIR/$tbl.csv"
done

cat <<'TRAILER'

-- 数据导入完成
-- 授权：使 test_tenant 数据源账号可读写 test_db schema 下所有表
GRANT ALL ON ALL TABLES IN SCHEMA test_db TO test_tenant;
TRAILER
} > "$OUT"

rm -rf "$NORM_DIR"

BYTES=$(wc -c < "$OUT")
echo "Generated: $OUT ($(numfmt --to=iec $BYTES) / $(wc -l < "$OUT") lines)"