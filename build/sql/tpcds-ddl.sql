-- ============================================================
-- TPC-DS 4.0.0 建表语句（SF=0.5，parquet，含字段中文注释）
-- 生成依据：DSGen-software-code-4.0.0/tools/tpcds.sql（VERSION 4 / RELEASE 0）
-- 数据位置：/opt/dataforge/warehouse/tpcds/<table>（容器内 df-data 卷，由镜像 /opt/tpcds 播种）
-- 用法：spark-sql -f tpcds-ddl.sql；或经 Kyuubi/beeline 执行
--       jdbc:hive2://localhost:10009/test_db（Hive Metastore: thrift://localhost:9083）
-- 说明：EXTERNAL 表只注册元数据，不移动/删除已有 parquet 文件；
--       类型映射 char/varchar->string、time->string、integer->int、decimal/date 保持。
-- ============================================================

CREATE DATABASE IF NOT EXISTS test_db
  COMMENT 'TPC-DS 4.0.0 基准测试数据集（SF=0.5，parquet）'
  LOCATION '/opt/dataforge/warehouse/tpcds/test_db.db';
USE test_db;

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.dbgen_version (
  dv_version               string       COMMENT '数据生成器版本号',
  dv_create_date           date         COMMENT '数据生成日期',
  dv_create_time           string       COMMENT '数据生成时间',
  dv_cmdline_args          string       COMMENT '数据生成命令行参数'
)
COMMENT '数据生成器版本信息表（记录造数工具版本、生成时间与命令行参数）'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/dbgen_version';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.customer_address (
  ca_address_sk            int          COMMENT '地址代理键（主键）',
  ca_address_id            string       COMMENT '地址业务标识（唯一）',
  ca_street_number         string       COMMENT '街道门牌号',
  ca_street_name           string       COMMENT '街道名称',
  ca_street_type           string       COMMENT '街道类型（Ave/Dr/Rd/St 等）',
  ca_suite_number          string       COMMENT '门牌/套间号',
  ca_city                  string       COMMENT '城市',
  ca_county                string       COMMENT '县/区',
  ca_state                 string       COMMENT '州/省（2 位代码）',
  ca_zip                   string       COMMENT '邮政编码',
  ca_country               string       COMMENT '国家',
  ca_gmt_offset            decimal(5,2) COMMENT '所在时区相对 GMT 的偏移（小时）',
  ca_location_type         string       COMMENT '地址类型（独栋/公寓/出租房等）'
)
COMMENT '客户地址维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/customer_address';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.customer_demographics (
  cd_demo_sk               int          COMMENT '人口统计代理键（主键）',
  cd_gender                string       COMMENT '性别（M/F）',
  cd_marital_status        string       COMMENT '婚姻状况（M/S/D/W/U）',
  cd_education_status      string       COMMENT '教育程度（Primary/Secondary/College/2 yr Degree/4 yr Degree/Advanced Degree/Unknown）',
  cd_purchase_estimate     int          COMMENT '年购买力估计（金额）',
  cd_credit_rating         string       COMMENT '信用等级（Low Risk/High Risk/Good/Unknown）',
  cd_dep_count             int          COMMENT '受抚养子女数',
  cd_dep_employed_count    int          COMMENT '受抚养且在职的子女人数',
  cd_dep_college_count     int          COMMENT '受抚养且在上大学的子女人数'
)
COMMENT '客户人口统计维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/customer_demographics';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.date_dim (
  d_date_sk                int          COMMENT '日期代理键（Julian 日序号）',
  d_date_id                string       COMMENT '日期业务标识（唯一）',
  d_date                   date         COMMENT '日历日期',
  d_month_seq              int          COMMENT '自起始日期起的月份序号',
  d_week_seq               int          COMMENT '自起始日期起的周序号',
  d_quarter_seq            int          COMMENT '自起始日期起的季度序号',
  d_year                   int          COMMENT '年份',
  d_dow                    int          COMMENT '星期几序号（0–6）',
  d_moy                    int          COMMENT '月份（1–12）',
  d_dom                    int          COMMENT '日（1–31）',
  d_qoy                    int          COMMENT '季度（1–4）',
  d_fy_year                int          COMMENT '财年',
  d_fy_quarter_seq         int          COMMENT '财年季度序号',
  d_fy_week_seq            int          COMMENT '财年周序号',
  d_day_name               string       COMMENT '星期名称',
  d_quarter_name           string       COMMENT '季度名称（如 2001Q1）',
  d_holiday                string       COMMENT '是否法定假日（Y/N）',
  d_weekend                string       COMMENT '是否周末（Y/N）',
  d_following_holiday      string       COMMENT '次日是否为假日（Y/N）',
  d_first_dom              int          COMMENT '当月第一天（月内序号）',
  d_last_dom               int          COMMENT '当月最后一天（月内序号）',
  d_same_day_ly            int          COMMENT '去年同一天的日期代理键',
  d_same_day_lq            int          COMMENT '上一季度同一天的日期代理键',
  d_current_day            string       COMMENT '是否当前日期（Y/N）',
  d_current_week           string       COMMENT '是否当前周（Y/N）',
  d_current_month          string       COMMENT '是否当前月（Y/N）',
  d_current_quarter        string       COMMENT '是否当前季度（Y/N）',
  d_current_year           string       COMMENT '是否当前年度（Y/N）'
)
COMMENT '日期维度表（覆盖 1900-01-02 至 2100-01-01，共 73049 天）'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/date_dim';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.warehouse (
  w_warehouse_sk           int          COMMENT '仓库代理键（主键）',
  w_warehouse_id           string       COMMENT '仓库业务标识（唯一）',
  w_warehouse_name         string       COMMENT '仓库名称',
  w_warehouse_sq_ft        int          COMMENT '仓库面积（平方英尺）',
  w_street_number          string       COMMENT '街道门牌号',
  w_street_name            string       COMMENT '街道名称',
  w_street_type            string       COMMENT '街道类型',
  w_suite_number           string       COMMENT '门牌/套间号',
  w_city                   string       COMMENT '城市',
  w_county                 string       COMMENT '县/区',
  w_state                  string       COMMENT '州/省',
  w_zip                    string       COMMENT '邮政编码',
  w_country                string       COMMENT '国家',
  w_gmt_offset             decimal(5,2) COMMENT '时区偏移（小时）'
)
COMMENT '仓库维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/warehouse';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.ship_mode (
  sm_ship_mode_sk          int          COMMENT '配送方式代理键（主键）',
  sm_ship_mode_id          string       COMMENT '配送方式业务标识（唯一）',
  sm_type                  string       COMMENT '配送类型（如 AIR/TRUCK/REG）',
  sm_code                  string       COMMENT '配送代码',
  sm_carrier               string       COMMENT '承运商',
  sm_contract              string       COMMENT '承运合同编号'
)
COMMENT '配送方式维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/ship_mode';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.time_dim (
  t_time_sk                int          COMMENT '时间代理键（主键，一天中的秒序号）',
  t_time_id                string       COMMENT '时间业务标识（唯一）',
  t_time                   int          COMMENT '一天中的秒数（0–86399）',
  t_hour                   int          COMMENT '小时（0–23）',
  t_minute                 int          COMMENT '分钟（0–59）',
  t_second                 int          COMMENT '秒（0–59）',
  t_am_pm                  string       COMMENT 'AM/PM 标识',
  t_shift                  string       COMMENT '班次（first/second/third）',
  t_sub_shift              string       COMMENT '子班次（morning/evening/night）',
  t_meal_time              string       COMMENT '用餐时段（breakfast/lunch/dinner/-）'
)
COMMENT '时间维度表（一天 86400 秒，秒级粒度）'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/time_dim';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.reason (
  r_reason_sk              int          COMMENT '退货原因代理键（主键）',
  r_reason_id              string       COMMENT '退货原因业务标识（唯一）',
  r_reason_desc            string       COMMENT '退货原因描述'
)
COMMENT '退货原因维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/reason';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.income_band (
  ib_income_band_sk        int          COMMENT '收入区间代理键（主键）',
  ib_lower_bound           int          COMMENT '收入区间下限（含）',
  ib_upper_bound           int          COMMENT '收入区间上限（含）'
)
COMMENT '收入区间维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/income_band';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.item (
  i_item_sk                int          COMMENT '商品代理键（主键）',
  i_item_id                string       COMMENT '商品业务标识（唯一）',
  i_rec_start_date         date         COMMENT '记录生效日期',
  i_rec_end_date           date         COMMENT '记录失效日期（为空表示当前有效）',
  i_item_desc              string       COMMENT '商品描述',
  i_current_price          decimal(7,2) COMMENT '当前售价',
  i_wholesale_cost         decimal(7,2) COMMENT '批发成本',
  i_brand_id               int          COMMENT '品牌编号',
  i_brand                  string       COMMENT '品牌名称',
  i_class_id               int          COMMENT '品类编号',
  i_class                  string       COMMENT '品类名称',
  i_category_id            int          COMMENT '类别编号',
  i_category               string       COMMENT '类别名称',
  i_manufact_id            int          COMMENT '制造商编号',
  i_manufact               string       COMMENT '制造商名称',
  i_size                   string       COMMENT '尺码',
  i_formulation            string       COMMENT '配方/成分',
  i_color                  string       COMMENT '颜色',
  i_units                  string       COMMENT '计量单位',
  i_container              string       COMMENT '包装类型',
  i_manager_id             int          COMMENT '商品经理编号',
  i_product_name           string       COMMENT '产品名称'
)
COMMENT '商品维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/item';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.store (
  s_store_sk               int          COMMENT '门店代理键（主键）',
  s_store_id               string       COMMENT '门店业务标识（唯一）',
  s_rec_start_date         date         COMMENT '记录生效日期',
  s_rec_end_date           date         COMMENT '记录失效日期',
  s_closed_date_sk         int          COMMENT '门店关闭日期代理键（date_dim）',
  s_store_name             string       COMMENT '门店名称',
  s_number_employees       int          COMMENT '员工人数',
  s_floor_space            int          COMMENT '营业面积（平方英尺）',
  s_hours                  string       COMMENT '营业时间',
  s_manager                string       COMMENT '门店经理',
  s_market_id              int          COMMENT '市场编号',
  s_geography_class        string       COMMENT '地理分级',
  s_market_desc            string       COMMENT '市场描述',
  s_market_manager         string       COMMENT '市场经理',
  s_division_id            int          COMMENT '事业部编号',
  s_division_name          string       COMMENT '事业部名称',
  s_company_id             int          COMMENT '公司编号',
  s_company_name           string       COMMENT '公司名称',
  s_street_number          string       COMMENT '街道门牌号',
  s_street_name            string       COMMENT '街道名称',
  s_street_type            string       COMMENT '街道类型',
  s_suite_number           string       COMMENT '门牌/套间号',
  s_city                   string       COMMENT '城市',
  s_county                 string       COMMENT '县/区',
  s_state                  string       COMMENT '州/省',
  s_zip                    string       COMMENT '邮政编码',
  s_country                string       COMMENT '国家',
  s_gmt_offset             decimal(5,2) COMMENT '时区偏移（小时）',
  s_tax_precentage         decimal(5,2) COMMENT '销售税率（原字段名拼写如此）'
)
COMMENT '门店维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/store';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.call_center (
  cc_call_center_sk        int          COMMENT '呼叫中心代理键（主键）',
  cc_call_center_id        string       COMMENT '呼叫中心业务标识（唯一）',
  cc_rec_start_date        date         COMMENT '记录生效日期',
  cc_rec_end_date          date         COMMENT '记录失效日期',
  cc_closed_date_sk        int          COMMENT '关闭日期代理键（date_dim）',
  cc_open_date_sk          int          COMMENT '开业日期代理键（date_dim）',
  cc_name                  string       COMMENT '呼叫中心名称',
  cc_class                 string       COMMENT '呼叫中心级别',
  cc_employees             int          COMMENT '员工人数',
  cc_sq_ft                 int          COMMENT '面积（平方英尺）',
  cc_hours                 string       COMMENT '服务时间',
  cc_manager               string       COMMENT '经理',
  cc_mkt_id                int          COMMENT '市场编号',
  cc_mkt_class             string       COMMENT '市场级别',
  cc_mkt_desc              string       COMMENT '市场描述',
  cc_market_manager        string       COMMENT '市场经理',
  cc_division              int          COMMENT '事业部编号',
  cc_division_name         string       COMMENT '事业部名称',
  cc_company               int          COMMENT '公司编号',
  cc_company_name          string       COMMENT '公司名称',
  cc_street_number         string       COMMENT '街道门牌号',
  cc_street_name           string       COMMENT '街道名称',
  cc_street_type           string       COMMENT '街道类型',
  cc_suite_number          string       COMMENT '门牌/套间号',
  cc_city                  string       COMMENT '城市',
  cc_county                string       COMMENT '县/区',
  cc_state                 string       COMMENT '州/省',
  cc_zip                   string       COMMENT '邮政编码',
  cc_country               string       COMMENT '国家',
  cc_gmt_offset            decimal(5,2) COMMENT '时区偏移（小时）',
  cc_tax_percentage        decimal(5,2) COMMENT '税率'
)
COMMENT '呼叫中心维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/call_center';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.customer (
  c_customer_sk            int          COMMENT '客户代理键（主键）',
  c_customer_id            string       COMMENT '客户业务标识（唯一）',
  c_current_cdemo_sk       int          COMMENT '当前人口统计代理键（customer_demographics）',
  c_current_hdemo_sk       int          COMMENT '当前家庭人口统计代理键（household_demographics）',
  c_current_addr_sk        int          COMMENT '当前地址代理键（customer_address）',
  c_first_shipto_date_sk   int          COMMENT '首次收货日期代理键（date_dim）',
  c_first_sales_date_sk    int          COMMENT '首次购买日期代理键（date_dim）',
  c_salutation             string       COMMENT '称谓',
  c_first_name             string       COMMENT '名',
  c_last_name              string       COMMENT '姓',
  c_preferred_cust_flag    string       COMMENT '是否优质客户（Y/N）',
  c_birth_day              int          COMMENT '出生日',
  c_birth_month            int          COMMENT '出生月',
  c_birth_year             int          COMMENT '出生年',
  c_birth_country          string       COMMENT '出生国家',
  c_login                  string       COMMENT '登录名',
  c_email_address          string       COMMENT '电子邮箱',
  c_last_review_date       string       COMMENT '上次审核日期（4.0.0 DDL 声明为 char(10)，dsdgen 实际输出 Julian 日整数）'
)
COMMENT '客户维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/customer';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.web_site (
  web_site_sk              int          COMMENT '网站代理键（主键）',
  web_site_id              string       COMMENT '网站业务标识（唯一）',
  web_rec_start_date       date         COMMENT '记录生效日期',
  web_rec_end_date         date         COMMENT '记录失效日期',
  web_name                 string       COMMENT '网站名称',
  web_open_date_sk         int          COMMENT '上线日期代理键（date_dim）',
  web_close_date_sk        int          COMMENT '关闭日期代理键（date_dim）',
  web_class                string       COMMENT '网站级别',
  web_manager              string       COMMENT '网站管理员',
  web_mkt_id               int          COMMENT '市场编号',
  web_mkt_class            string       COMMENT '市场级别',
  web_mkt_desc             string       COMMENT '市场描述',
  web_market_manager       string       COMMENT '市场经理',
  web_company_id           int          COMMENT '公司编号',
  web_company_name         string       COMMENT '公司名称',
  web_street_number        string       COMMENT '街道门牌号',
  web_street_name          string       COMMENT '街道名称',
  web_street_type          string       COMMENT '街道类型',
  web_suite_number         string       COMMENT '门牌/套间号',
  web_city                 string       COMMENT '城市',
  web_county               string       COMMENT '县/区',
  web_state                string       COMMENT '州/省',
  web_zip                  string       COMMENT '邮政编码',
  web_country              string       COMMENT '国家',
  web_gmt_offset           decimal(5,2) COMMENT '时区偏移（小时）',
  web_tax_percentage       decimal(5,2) COMMENT '税率'
)
COMMENT '网站维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/web_site';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.store_returns (
  sr_returned_date_sk      int          COMMENT '退货日期代理键（date_dim）',
  sr_return_time_sk        int          COMMENT '退货时间代理键（time_dim）',
  sr_item_sk               int          COMMENT '商品代理键（item）',
  sr_customer_sk           int          COMMENT '客户代理键（customer）',
  sr_cdemo_sk              int          COMMENT '客户人口统计代理键（customer_demographics）',
  sr_hdemo_sk              int          COMMENT '客户家庭人口统计代理键（household_demographics）',
  sr_addr_sk               int          COMMENT '客户地址代理键（customer_address）',
  sr_store_sk              int          COMMENT '门店代理键（store）',
  sr_reason_sk             int          COMMENT '退货原因代理键（reason）',
  sr_ticket_number         int          COMMENT '销售小票号（与商品联合唯一）',
  sr_return_quantity       int          COMMENT '退货数量',
  sr_return_amt            decimal(7,2) COMMENT '退货金额',
  sr_return_tax            decimal(7,2) COMMENT '退货税额',
  sr_return_amt_inc_tax    decimal(7,2) COMMENT '退货金额（含税）',
  sr_fee                   decimal(7,2) COMMENT '手续费',
  sr_return_ship_cost      decimal(7,2) COMMENT '退货运费',
  sr_refunded_cash         decimal(7,2) COMMENT '现金退款金额',
  sr_reversed_charge       decimal(7,2) COMMENT '冲正金额',
  sr_store_credit          decimal(7,2) COMMENT '门店购物信用额度退款',
  sr_net_loss              decimal(7,2) COMMENT '净损失'
)
COMMENT '门店渠道退货事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/store_returns';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.household_demographics (
  hd_demo_sk               int          COMMENT '家庭人口统计代理键（主键）',
  hd_income_band_sk        int          COMMENT '收入区间代理键（income_band）',
  hd_buy_potential         string       COMMENT '购买潜力区间（0-500/501-1000/1001-5000/5001-10000/10001+/Unknown）',
  hd_dep_count             int          COMMENT '家庭受抚养人数',
  hd_vehicle_count         int          COMMENT '家庭车辆数'
)
COMMENT '家庭人口统计维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/household_demographics';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.web_page (
  wp_web_page_sk           int          COMMENT '网页代理键（主键）',
  wp_web_page_id           string       COMMENT '网页业务标识（唯一）',
  wp_rec_start_date        date         COMMENT '记录生效日期',
  wp_rec_end_date          date         COMMENT '记录失效日期',
  wp_creation_date_sk      int          COMMENT '创建日期代理键（date_dim）',
  wp_access_date_sk        int          COMMENT '最近访问日期代理键（date_dim）',
  wp_autogen_flag          string       COMMENT '是否自动生成（Y/N）',
  wp_customer_sk           int          COMMENT '归属客户代理键（customer）',
  wp_url                   string       COMMENT '页面 URL',
  wp_type                  string       COMMENT '页面类型',
  wp_char_count            int          COMMENT '字符数',
  wp_link_count            int          COMMENT '链接数',
  wp_image_count           int          COMMENT '图片数',
  wp_max_ad_count          int          COMMENT '最大广告数'
)
COMMENT '网页维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/web_page';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.promotion (
  p_promo_sk               int          COMMENT '促销代理键（主键）',
  p_promo_id               string       COMMENT '促销业务标识（唯一）',
  p_start_date_sk          int          COMMENT '促销开始日期代理键（date_dim）',
  p_end_date_sk            int          COMMENT '促销结束日期代理键（date_dim）',
  p_item_sk                int          COMMENT '促销商品代理键（item）',
  p_cost                   decimal(15,2) COMMENT '促销成本',
  p_response_target        int          COMMENT '目标响应数',
  p_promo_name             string       COMMENT '促销名称',
  p_channel_dmail          string       COMMENT '是否直邮渠道（Y/N）',
  p_channel_email          string       COMMENT '是否邮件渠道（Y/N）',
  p_channel_catalog        string       COMMENT '是否目录渠道（Y/N）',
  p_channel_tv             string       COMMENT '是否电视渠道（Y/N）',
  p_channel_radio          string       COMMENT '是否广播渠道（Y/N）',
  p_channel_press          string       COMMENT '是否平面媒体渠道（Y/N）',
  p_channel_event          string       COMMENT '是否活动渠道（Y/N）',
  p_channel_demo           string       COMMENT '是否现场演示渠道（Y/N）',
  p_channel_details        string       COMMENT '渠道说明',
  p_purpose                string       COMMENT '促销目的',
  p_discount_active        string       COMMENT '是否启用折扣（Y/N）'
)
COMMENT '促销维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/promotion';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.catalog_page (
  cp_catalog_page_sk       int          COMMENT '目录页代理键（主键）',
  cp_catalog_page_id       string       COMMENT '目录页业务标识（唯一）',
  cp_start_date_sk         int          COMMENT '目录页生效日期代理键（date_dim）',
  cp_end_date_sk           int          COMMENT '目录页失效日期代理键（date_dim）',
  cp_department            string       COMMENT '部门',
  cp_catalog_number        int          COMMENT '目录编号',
  cp_catalog_page_number   int          COMMENT '目录内页码',
  cp_description           string       COMMENT '页面描述',
  cp_type                  string       COMMENT '页面类型'
)
COMMENT '目录页维度表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/catalog_page';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.inventory (
  inv_date_sk              int          COMMENT '库存日期代理键（date_dim，联合主键）',
  inv_item_sk              int          COMMENT '商品代理键（item，联合主键）',
  inv_warehouse_sk         int          COMMENT '仓库代理键（warehouse，联合主键）',
  inv_quantity_on_hand     int          COMMENT '在手库存数量'
)
COMMENT '库存快照事实表（日期×商品×仓库）'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/inventory';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.catalog_returns (
  cr_returned_date_sk      int          COMMENT '退货日期代理键（date_dim）',
  cr_returned_time_sk      int          COMMENT '退货时间代理键（time_dim）',
  cr_item_sk               int          COMMENT '商品代理键（item）',
  cr_refunded_customer_sk  int          COMMENT '被退款客户代理键（customer）',
  cr_refunded_cdemo_sk     int          COMMENT '被退款客户人口统计代理键（customer_demographics）',
  cr_refunded_hdemo_sk     int          COMMENT '被退款客户家庭人口统计代理键（household_demographics）',
  cr_refunded_addr_sk      int          COMMENT '被退款客户地址代理键（customer_address）',
  cr_returning_customer_sk int          COMMENT '退货客户代理键（customer）',
  cr_returning_cdemo_sk    int          COMMENT '退货客户人口统计代理键（customer_demographics）',
  cr_returning_hdemo_sk    int          COMMENT '退货客户家庭人口统计代理键（household_demographics）',
  cr_returning_addr_sk     int          COMMENT '退货客户地址代理键（customer_address）',
  cr_call_center_sk        int          COMMENT '呼叫中心代理键（call_center）',
  cr_catalog_page_sk       int          COMMENT '目录页代理键（catalog_page）',
  cr_ship_mode_sk          int          COMMENT '配送方式代理键（ship_mode）',
  cr_warehouse_sk          int          COMMENT '仓库代理键（warehouse）',
  cr_reason_sk             int          COMMENT '退货原因代理键（reason）',
  cr_order_number          int          COMMENT '目录订单号（与商品联合唯一）',
  cr_return_quantity       int          COMMENT '退货数量',
  cr_return_amount         decimal(7,2) COMMENT '退货金额',
  cr_return_tax            decimal(7,2) COMMENT '退货税额',
  cr_return_amt_inc_tax    decimal(7,2) COMMENT '退货金额（含税）',
  cr_fee                   decimal(7,2) COMMENT '手续费',
  cr_return_ship_cost      decimal(7,2) COMMENT '退货运费',
  cr_refunded_cash         decimal(7,2) COMMENT '现金退款金额',
  cr_reversed_charge       decimal(7,2) COMMENT '冲正金额',
  cr_store_credit          decimal(7,2) COMMENT '门店购物信用额度退款',
  cr_net_loss              decimal(7,2) COMMENT '净损失'
)
COMMENT '目录渠道退货事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/catalog_returns';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.web_returns (
  wr_returned_date_sk      int          COMMENT '退货日期代理键（date_dim）',
  wr_returned_time_sk      int          COMMENT '退货时间代理键（time_dim）',
  wr_item_sk               int          COMMENT '商品代理键（item）',
  wr_refunded_customer_sk  int          COMMENT '被退款客户代理键（customer）',
  wr_refunded_cdemo_sk     int          COMMENT '被退款客户人口统计代理键（customer_demographics）',
  wr_refunded_hdemo_sk     int          COMMENT '被退款客户家庭人口统计代理键（household_demographics）',
  wr_refunded_addr_sk      int          COMMENT '被退款客户地址代理键（customer_address）',
  wr_returning_customer_sk int          COMMENT '退货客户代理键（customer）',
  wr_returning_cdemo_sk    int          COMMENT '退货客户人口统计代理键（customer_demographics）',
  wr_returning_hdemo_sk    int          COMMENT '退货客户家庭人口统计代理键（household_demographics）',
  wr_returning_addr_sk     int          COMMENT '退货客户地址代理键（customer_address）',
  wr_web_page_sk           int          COMMENT '网页代理键（web_page）',
  wr_reason_sk             int          COMMENT '退货原因代理键（reason）',
  wr_order_number          int          COMMENT '网络订单号（与商品联合唯一）',
  wr_return_quantity       int          COMMENT '退货数量',
  wr_return_amt            decimal(7,2) COMMENT '退货金额',
  wr_return_tax            decimal(7,2) COMMENT '退货税额',
  wr_return_amt_inc_tax    decimal(7,2) COMMENT '退货金额（含税）',
  wr_fee                   decimal(7,2) COMMENT '手续费',
  wr_return_ship_cost      decimal(7,2) COMMENT '退货运费',
  wr_refunded_cash         decimal(7,2) COMMENT '现金退款金额',
  wr_reversed_charge       decimal(7,2) COMMENT '冲正金额',
  wr_account_credit        decimal(7,2) COMMENT '账户信用额度退款',
  wr_net_loss              decimal(7,2) COMMENT '净损失'
)
COMMENT '网络渠道退货事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/web_returns';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.web_sales (
  ws_sold_date_sk          int          COMMENT '销售日期代理键（date_dim）',
  ws_sold_time_sk          int          COMMENT '销售时间代理键（time_dim）',
  ws_ship_date_sk          int          COMMENT '发货日期代理键（date_dim）',
  ws_item_sk               int          COMMENT '商品代理键（item）',
  ws_bill_customer_sk      int          COMMENT '账单客户代理键（customer）',
  ws_bill_cdemo_sk         int          COMMENT '账单客户人口统计代理键（customer_demographics）',
  ws_bill_hdemo_sk         int          COMMENT '账单客户家庭人口统计代理键（household_demographics）',
  ws_bill_addr_sk          int          COMMENT '账单地址代理键（customer_address）',
  ws_ship_customer_sk      int          COMMENT '收货客户代理键（customer）',
  ws_ship_cdemo_sk         int          COMMENT '收货客户人口统计代理键（customer_demographics）',
  ws_ship_hdemo_sk         int          COMMENT '收货客户家庭人口统计代理键（household_demographics）',
  ws_ship_addr_sk          int          COMMENT '收货地址代理键（customer_address）',
  ws_web_page_sk           int          COMMENT '网页代理键（web_page）',
  ws_web_site_sk           int          COMMENT '网站代理键（web_site）',
  ws_ship_mode_sk          int          COMMENT '配送方式代理键（ship_mode）',
  ws_warehouse_sk          int          COMMENT '仓库代理键（warehouse）',
  ws_promo_sk              int          COMMENT '促销代理键（promotion）',
  ws_order_number          int          COMMENT '网络订单号（与商品联合唯一）',
  ws_quantity              int          COMMENT '购买数量',
  ws_wholesale_cost        decimal(7,2) COMMENT '批发成本',
  ws_list_price            decimal(7,2) COMMENT '标价',
  ws_sales_price           decimal(7,2) COMMENT '成交单价',
  ws_ext_discount_amt      decimal(7,2) COMMENT '折扣总额',
  ws_ext_sales_price       decimal(7,2) COMMENT '销售总额（成交单价×数量）',
  ws_ext_wholesale_cost    decimal(7,2) COMMENT '批发成本总额',
  ws_ext_list_price        decimal(7,2) COMMENT '标价总额',
  ws_ext_tax               decimal(7,2) COMMENT '税额',
  ws_coupon_amt            decimal(7,2) COMMENT '优惠券金额',
  ws_ext_ship_cost         decimal(7,2) COMMENT '运费总额',
  ws_net_paid              decimal(7,2) COMMENT '净支付金额',
  ws_net_paid_inc_tax      decimal(7,2) COMMENT '净支付金额（含税）',
  ws_net_paid_inc_ship     decimal(7,2) COMMENT '净支付金额（含运费）',
  ws_net_paid_inc_ship_tax decimal(7,2) COMMENT '净支付金额（含运费与税）',
  ws_net_profit            decimal(7,2) COMMENT '净利润'
)
COMMENT '网络渠道销售事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/web_sales';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.catalog_sales (
  cs_sold_date_sk          int          COMMENT '销售日期代理键（date_dim）',
  cs_sold_time_sk          int          COMMENT '销售时间代理键（time_dim）',
  cs_ship_date_sk          int          COMMENT '发货日期代理键（date_dim）',
  cs_bill_customer_sk      int          COMMENT '账单客户代理键（customer）',
  cs_bill_cdemo_sk         int          COMMENT '账单客户人口统计代理键（customer_demographics）',
  cs_bill_hdemo_sk         int          COMMENT '账单客户家庭人口统计代理键（household_demographics）',
  cs_bill_addr_sk          int          COMMENT '账单地址代理键（customer_address）',
  cs_ship_customer_sk      int          COMMENT '收货客户代理键（customer）',
  cs_ship_cdemo_sk         int          COMMENT '收货客户人口统计代理键（customer_demographics）',
  cs_ship_hdemo_sk         int          COMMENT '收货客户家庭人口统计代理键（household_demographics）',
  cs_ship_addr_sk          int          COMMENT '收货地址代理键（customer_address）',
  cs_call_center_sk        int          COMMENT '呼叫中心代理键（call_center）',
  cs_catalog_page_sk       int          COMMENT '目录页代理键（catalog_page）',
  cs_ship_mode_sk          int          COMMENT '配送方式代理键（ship_mode）',
  cs_warehouse_sk          int          COMMENT '仓库代理键（warehouse）',
  cs_item_sk               int          COMMENT '商品代理键（item）',
  cs_promo_sk              int          COMMENT '促销代理键（promotion）',
  cs_order_number          int          COMMENT '目录订单号（与商品联合唯一）',
  cs_quantity              int          COMMENT '购买数量',
  cs_wholesale_cost        decimal(7,2) COMMENT '批发成本',
  cs_list_price            decimal(7,2) COMMENT '标价',
  cs_sales_price           decimal(7,2) COMMENT '成交单价',
  cs_ext_discount_amt      decimal(7,2) COMMENT '折扣总额',
  cs_ext_sales_price       decimal(7,2) COMMENT '销售总额（成交单价×数量）',
  cs_ext_wholesale_cost    decimal(7,2) COMMENT '批发成本总额',
  cs_ext_list_price        decimal(7,2) COMMENT '标价总额',
  cs_ext_tax               decimal(7,2) COMMENT '税额',
  cs_coupon_amt            decimal(7,2) COMMENT '优惠券金额',
  cs_ext_ship_cost         decimal(7,2) COMMENT '运费总额',
  cs_net_paid              decimal(7,2) COMMENT '净支付金额',
  cs_net_paid_inc_tax      decimal(7,2) COMMENT '净支付金额（含税）',
  cs_net_paid_inc_ship     decimal(7,2) COMMENT '净支付金额（含运费）',
  cs_net_paid_inc_ship_tax decimal(7,2) COMMENT '净支付金额（含运费与税）',
  cs_net_profit            decimal(7,2) COMMENT '净利润'
)
COMMENT '目录渠道销售事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/catalog_sales';

CREATE EXTERNAL TABLE IF NOT EXISTS test_db.store_sales (
  ss_sold_date_sk          int          COMMENT '销售日期代理键（date_dim）',
  ss_sold_time_sk          int          COMMENT '销售时间代理键（time_dim）',
  ss_item_sk               int          COMMENT '商品代理键（item）',
  ss_customer_sk           int          COMMENT '客户代理键（customer）',
  ss_cdemo_sk              int          COMMENT '客户人口统计代理键（customer_demographics）',
  ss_hdemo_sk              int          COMMENT '客户家庭人口统计代理键（household_demographics）',
  ss_addr_sk               int          COMMENT '客户地址代理键（customer_address）',
  ss_store_sk              int          COMMENT '门店代理键（store）',
  ss_promo_sk              int          COMMENT '促销代理键（promotion）',
  ss_ticket_number         int          COMMENT '销售小票号（与商品联合唯一）',
  ss_quantity              int          COMMENT '购买数量',
  ss_wholesale_cost        decimal(7,2) COMMENT '批发成本',
  ss_list_price            decimal(7,2) COMMENT '标价',
  ss_sales_price           decimal(7,2) COMMENT '成交单价',
  ss_ext_discount_amt      decimal(7,2) COMMENT '折扣总额',
  ss_ext_sales_price       decimal(7,2) COMMENT '销售总额（成交单价×数量）',
  ss_ext_wholesale_cost    decimal(7,2) COMMENT '批发成本总额',
  ss_ext_list_price        decimal(7,2) COMMENT '标价总额',
  ss_ext_tax               decimal(7,2) COMMENT '税额',
  ss_coupon_amt            decimal(7,2) COMMENT '优惠券金额',
  ss_net_paid              decimal(7,2) COMMENT '净支付金额',
  ss_net_paid_inc_tax      decimal(7,2) COMMENT '净支付金额（含税）',
  ss_net_profit            decimal(7,2) COMMENT '净利润'
)
COMMENT '门店渠道销售事实表'
STORED AS PARQUET
LOCATION '/opt/dataforge/warehouse/tpcds/store_sales';

