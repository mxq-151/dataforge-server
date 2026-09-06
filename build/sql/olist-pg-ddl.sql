-- ============================================================================
-- Brazilian E-Commerce Public Dataset by Olist  建表脚本 (PostgreSQL)
-- 数据来源: https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce
-- 说明:
--   1. 所有 ID / 文本字段使用 TEXT 类型, 以兼容原始 CSV 中的字符串标识符
--   2. 金额字段使用 NUMERIC(10,2) 保留两位小数, 与原始数据价格精确匹配
--   3. 时间戳字段使用 TIMESTAMP
--   4. 执行方式: psql -U <user> -d <db> -f olist-pg-ddl.sql
-- ============================================================================

-- 先删除已存在的表 (按依赖倒序, 便于重复执行)
DROP TABLE IF EXISTS product_category_name_translation;
DROP TABLE IF EXISTS olist_geolocation_dataset;
DROP TABLE IF EXISTS olist_order_reviews_dataset;
DROP TABLE IF EXISTS olist_order_payments_dataset;
DROP TABLE IF EXISTS olist_order_items_dataset;
DROP TABLE IF EXISTS olist_orders_dataset;
DROP TABLE IF EXISTS olist_products_dataset;
DROP TABLE IF EXISTS olist_sellers_dataset;
DROP TABLE IF EXISTS olist_customers_dataset;

-- ============================================================================
-- 1. olist_customers_dataset 客户信息表
--    客户主数据, 一个真实客户策略(customer_unique_id)可对应多个订单客户ID
-- ============================================================================
CREATE TABLE olist_customers_dataset (
    customer_id           TEXT      NOT NULL,   -- 客户ID (订单系统的客户主键, 全局唯一)
    customer_unique_id    TEXT      NOT NULL,   -- 客户唯一标识 (同一真实客户重复下单时其值相同)
    customer_zip_code_prefix INTEGER NOT NULL,  -- 客户地址邮编前5位
    customer_city         TEXT,                 -- 客户所在城市
    customer_state        TEXT                  -- 客户所在州 (巴西两字母州码)
);

COMMENT ON TABLE  olist_customers_dataset IS '客户主数据表：记录巴西电商平台每个下单客户的基本信息(客户ID、唯一标识、地址邮编/城市/州)';
COMMENT ON COLUMN olist_customers_dataset.customer_id            IS '客户ID：订单系统内部生成的唯一客户主键';
COMMENT ON COLUMN olist_customers_dataset.customer_unique_id     IS '客户唯一标识：关联到真实顾客，同一顾客多次下单时该字段值一致但customer_id不同';
COMMENT ON COLUMN olist_customers_dataset.customer_zip_code_prefix IS '客户地址邮编前5位数字，用于关联地理位置表';
COMMENT ON COLUMN olist_customers_dataset.customer_city          IS '客户所在城市名称';
COMMENT ON COLUMN olist_customers_dataset.customer_state         IS '客户所在州代码(巴西两字母缩写，如SP、MG)';

-- ============================================================================
-- 2. olist_geolocation_dataset 地理位置表
--    邮编范围对应的经纬度坐标(客户与卖家地址可共用)
-- ============================================================================
CREATE TABLE olist_geolocation_dataset (
    geolocation_zip_code_prefix INTEGER NOT NULL,  -- 邮编前5位
    geolocation_lat       DOUBLE PRECISION,       -- 纬度 (南纬为负值)
    geolocation_lng       DOUBLE PRECISION,       -- 经度 (西经为负值)
    geolocation_city      TEXT,                   -- 城市
    geolocation_state     TEXT                    -- 州代码
);

COMMENT ON TABLE  olist_geolocation_dataset IS '地理位置表：按邮编前5位映射的巴西城市经纬度坐标，用于地理分析与可视化';
COMMENT ON COLUMN olist_geolocation_dataset.geolocation_zip_code_prefix IS '邮编前5位：地理位置聚合键';
COMMENT ON COLUMN olist_geolocation_dataset.geolocation_lat        IS '纬度坐标(十进制度数，南半球为负值)';
COMMENT ON COLUMN olist_geolocation_dataset.geolocation_lng        IS '经度坐标(十进制度数，西半球为负值)';
COMMENT ON COLUMN olist_geolocation_dataset.geolocation_city       IS '坐标对应城市名称';
COMMENT ON COLUMN olist_geolocation_dataset.geolocation_state      IS '坐标对应州代码';

-- ============================================================================
-- 3. olist_order_items_dataset 订单商品明细表
--    每个订单行包含具体商品、卖家、单价与运费
-- ============================================================================
CREATE TABLE olist_order_items_dataset (
    order_id              TEXT      NOT NULL,   -- 订单ID (关联订单表)
    order_item_id         INTEGER   NOT NULL,   -- 订单内商品行序号 (同一订单内从1递增)
    product_id            TEXT      NOT NULL,   -- 商品ID (关联商品表)
    seller_id             TEXT      NOT NULL,   -- 卖家ID (关联卖家表)
    shipping_limit_date   TIMESTAMP,            -- 卖家承诺的发货截止时间
    price                 NUMERIC(10,2),        -- 商品单价 (巴西雷亚尔)
    freight_value         NUMERIC(10,2)         -- 该订单行商品的运费
);

COMMENT ON TABLE  olist_order_items_dataset IS '订单商品明细表：存储每个订单中每件商品的购买信息(单价、运费、发货时限)';
COMMENT ON COLUMN olist_order_items_dataset.order_id             IS '订单ID：关联olist_orders_dataset.order_id';
COMMENT ON COLUMN olist_order_items_dataset.order_item_id        IS '订单内商品行序号：同一订单从1开始递增的索引';
COMMENT ON COLUMN olist_order_items_dataset.product_id           IS '商品ID：关联olist_products_dataset.product_id';
COMMENT ON COLUMN olist_order_items_dataset.seller_id            IS '卖家ID：关联olist_sellers_dataset.seller_id';
COMMENT ON COLUMN olist_order_items_dataset.shipping_limit_date  IS '发货截止时间：卖家最晚发货时间，与D+2发货政策有关';
COMMENT ON COLUMN olist_order_items_dataset.price                IS '商品单价(单位:巴西雷亚尔BRL)';
COMMENT ON COLUMN olist_order_items_dataset.freight_value        IS '该行商品的运费(单位:巴西雷亚尔BRL)';

-- ============================================================================
-- 4. olist_order_payments_dataset 订单支付表
--    一个订单可能有多次支付(不同方式/分期)，按payment_sequential区分
-- ============================================================================
CREATE TABLE olist_order_payments_dataset (
    order_id              TEXT      NOT NULL,   -- 订单ID
    payment_sequential    INTEGER,              -- 同一订单内支付顺序号 (1,2,...)
    payment_type          TEXT,                 -- 支付方式 (credit_card/voucher/boleto/debit_card/not_defined)
    payment_installments  INTEGER,              -- 分期付款的期数
    payment_value         NUMERIC(10,2)         -- 该次支付的金额
);

COMMENT ON TABLE  olist_order_payments_dataset IS '订单支付表：一个订单可存在多笔支付记录，按支付顺序与方式记录付款明细';
COMMENT ON COLUMN olist_order_payments_dataset.order_id             IS '订单ID：关联olist_orders_dataset.order_id';
COMMENT ON COLUMN olist_order_payments_dataset.payment_sequential   IS '支付顺序号：同一订单内第几次支付(从1开始)';
COMMENT ON COLUMN olist_order_payments_dataset.payment_type         IS '支付方式：credit_card信用卡、voucher优惠券、boleto银行票据、debit_card借记卡、not_defined未定义';
COMMENT ON COLUMN olist_order_payments_dataset.payment_installments IS '分期付款期数：信用卡分期或分期支付的总期数';
COMMENT ON COLUMN olist_order_payments_dataset.payment_value        IS '该笔支付金额(单位:巴西雷亚尔BRL)';

-- ============================================================================
-- 5. olist_order_reviews_dataset 订单评价表
--    订单收货后买家对商品/服务的评价
-- ============================================================================
CREATE TABLE olist_order_reviews_dataset (
    review_id             TEXT      NOT NULL,   -- 评价ID (全局唯一)
    order_id              TEXT      NOT NULL,   -- 被评价的订单ID
    review_score          INTEGER,              -- 评价分数 (1~5分)
    review_comment_title  TEXT,                 -- 评价标题
    review_comment_message TEXT,                -- 评价正文内容
    review_creation_date  TIMESTAMP,            -- 评价创建时间
    review_answer_timestamp TIMESTAMP           -- 评价发出时间 (买家回复/最后编辑时间)
);

COMMENT ON TABLE  olist_order_reviews_dataset IS '订单评价表：买家在下单后对该订单商品及服务提交的评分与评论';
COMMENT ON COLUMN olist_order_reviews_dataset.review_id             IS '评价ID：评价记录唯一主键';
COMMENT ON COLUMN olist_order_reviews_dataset.order_id              IS '被评价的订单ID：关联olist_orders_dataset.order_id';
COMMENT ON COLUMN olist_order_reviews_dataset.review_score          IS '评价分数：1-5分，5表示非常满意，1表示非常不满意';
COMMENT ON COLUMN olist_order_reviews_dataset.review_comment_title  IS '评价标题：买家填写的评论标题(可能为空)';
COMMENT ON COLUMN olist_order_reviews_dataset.review_comment_message IS '评价正文：买家填写的详细评论内容(可能为空)';
COMMENT ON COLUMN olist_order_reviews_dataset.review_creation_date  IS '评价提交时间：表示评价创建的时刻';
COMMENT ON COLUMN olist_order_reviews_dataset.review_answer_timestamp IS '评价发出时间：买家最终提交/回复评价的时间(与创建时间可能不同)';

-- ============================================================================
-- 6. olist_orders_dataset 订单主表
--    订单主流程与状态流转的核心表
-- ============================================================================
CREATE TABLE olist_orders_dataset (
    order_id                      TEXT      NOT NULL,  -- 订单ID (全局唯一主键)
    customer_id                   TEXT      NOT NULL,  -- 客户ID (关联客户表)
    order_status                  TEXT,                -- 订单状态 (delivered/shipped/canceled/invoiced/processing等)
    order_purchase_timestamp      TIMESTAMP,           -- 客户下单时间
    order_approved_at             TIMESTAMP,           -- 支付确认/审核通过时间
    order_delivered_carrier_date  TIMESTAMP,           -- 商品配送至承运商的时间
    order_delivered_customer_date TIMESTAMP,           -- 商品送达客户的时间
    order_estimated_delivery_date TIMESTAMP            -- 预计送达时间 (卖家在下单时承诺)
);

COMMENT ON TABLE  olist_orders_dataset IS '订单主表：记录订单完整生命周期(下单、审核、发货、送达)及订单状态';
COMMENT ON COLUMN olist_orders_dataset.order_id                      IS '订单ID：全局唯一订单主键';
COMMENT ON COLUMN olist_orders_dataset.customer_id                   IS '客户ID：关联olist_customers_dataset.customer_id';
COMMENT ON COLUMN olist_orders_dataset.order_status                  IS '订单状态枚举：delivered已送达、shipped已发货、canceled已取消、invoiced已开票、processing处理中等';
COMMENT ON COLUMN olist_orders_dataset.order_purchase_timestamp      IS '下单时间：客户提交订单的时刻';
COMMENT ON COLUMN olist_orders_dataset.order_approved_at             IS '支付确认时间：订单付款被批准/审核通过的时刻';
COMMENT ON COLUMN olist_orders_dataset.order_delivered_carrier_date  IS '到承运商时间：商品交付给物流承运商的时刻';
COMMENT ON COLUMN olist_orders_dataset.order_delivered_customer_date IS '送达客户时间：商品最终送达客户手上的时刻';
COMMENT ON COLUMN olist_orders_dataset.order_estimated_delivery_date IS '预计送达时间：卖承担诺给客户的估计送达日期';

-- ============================================================================
-- 7. olist_products_dataset 商品主数据表
--    商品属性(类别、名称长度、物理尺寸与重量)
-- ============================================================================
CREATE TABLE olist_products_dataset (
    product_id                 TEXT      NOT NULL,  -- 商品ID (全局唯一主键)
    product_category_name      TEXT,                -- 商品类别名称 (对应类别翻译表)
    product_name_length        INTEGER,             -- 商品名称字符长度
    product_description_length INTEGER,             -- 商品描述字符长度
    product_photos_qty         INTEGER,             -- 商品展示图片数量
    product_weight_g           INTEGER,             -- 商品重量(克)
    product_length_cm          INTEGER,             -- 商品长度(厘米)
    product_height_cm          INTEGER,             -- 商品高度(厘米)
    product_width_cm           INTEGER              -- 商品宽度(厘米)
);

COMMENT ON TABLE  olist_products_dataset IS '商品主数据表：存储商品类别、名称/描述长度及物理尺寸重量等属性';
COMMENT ON COLUMN olist_products_dataset.product_id                 IS '商品ID：商品唯一主键';
COMMENT ON COLUMN olist_products_dataset.product_category_name      IS '商品类别名称(葡萄牙语)：关联product_category_name_translation表做英文映射';
COMMENT ON COLUMN olist_products_dataset.product_name_length        IS '商品名称字符数：名称长度为-N表示缺失';
COMMENT ON COLUMN olist_products_dataset.product_description_length IS '商品描述字符数：描述长度为-N表示缺失';
COMMENT ON COLUMN olist_products_dataset.product_photos_qty         IS '商品展示图片数量：为-N表示缺失';
COMMENT ON COLUMN olist_products_dataset.product_weight_g           IS '商品重量(单位:克)，用于计算运费';
COMMENT ON COLUMN olist_products_dataset.product_length_cm          IS '商品长度(单位:厘米)';
COMMENT ON COLUMN olist_products_dataset.product_height_cm          IS '商品高度(单位:厘米)';
COMMENT ON COLUMN olist_products_dataset.product_width_cm           IS '商品宽度(单位:厘米)';

-- ============================================================================
-- 8. olist_sellers_dataset 卖家表
--    平台入驻卖家(含部分有意愿/已注册但未上架商品的新卖家)
-- ============================================================================
CREATE TABLE olist_sellers_dataset (
    seller_id               TEXT      NOT NULL,  -- 卖家ID (全局唯一主键)
    seller_zip_code_prefix  INTEGER,             -- 卖家地址邮编前5位
    seller_city             TEXT,                -- 卖家所在城市
    seller_state            TEXT                 -- 卖家所在州代码
);

COMMENT ON TABLE  olist_sellers_dataset IS '卖家表：记录在Olist平台注册的卖家基本资料与地址';
COMMENT ON COLUMN olist_sellers_dataset.seller_id              IS '卖家ID：卖家唯一标识';
COMMENT ON COLUMN olist_sellers_dataset.seller_zip_code_prefix IS '卖家地址邮编前5位，用于关联地理位置表';
COMMENT ON COLUMN olist_sellers_dataset.seller_city            IS '卖家所在城市名称';
COMMENT ON COLUMN olist_sellers_dataset.seller_state           IS '卖家所在州代码(两字母缩写)';

-- ============================================================================
-- 9. product_category_name_translation 商品类别翻译表
--    将葡萄牙语类别名映射为英文
-- ============================================================================
CREATE TABLE product_category_name_translation (
    product_category_name         TEXT NOT NULL,  -- 葡萄牙语类别名称 (源名)
    product_category_name_english TEXT             -- 英文类别名称 (翻译)
);

COMMENT ON TABLE  product_category_name_translation IS '商品类别翻译表：提供葡萄牙语类别名与英文类别名的映射';
COMMENT ON COLUMN product_category_name_translation.product_category_name         IS '葡萄牙语商品类别名称(源)';
COMMENT ON COLUMN product_category_name_translation.product_category_name_english IS '英文商品类别名称(翻译后)';

-- ============================================================================
-- 建议索引：加快主键与常用关联查询
-- ============================================================================
CREATE INDEX idx_orders_customer_id       ON olist_orders_dataset(customer_id);
CREATE INDEX idx_order_items_order_id     ON olist_order_items_dataset(order_id);
CREATE INDEX idx_order_items_product_id   ON olist_order_items_dataset(product_id);
CREATE INDEX idx_order_items_seller_id    ON olist_order_items_dataset(seller_id);
CREATE INDEX idx_order_payments_order_id  ON olist_order_payments_dataset(order_id);
CREATE INDEX idx_order_reviews_order_id   ON olist_order_reviews_dataset(order_id);