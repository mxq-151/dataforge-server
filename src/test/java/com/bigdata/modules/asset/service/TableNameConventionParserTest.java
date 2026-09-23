package com.bigdata.modules.asset.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表名命名规范解析器测试。
 *
 * <p>解析器是资产目录「规范视角」的唯一分类来源：分层与业务域不落库、不人工维护，
 * 全靠这个纯函数实时算出来。因此它的边界行为会直接决定目录树长什么样 ——
 * 尤其是「解析失败但仍能归到某个分层」这种情况，必须与
 * {@link AssetHealthService.Issue#NAMING_UNCONFORMED} 的判定保持一致。</p>
 */
@DisplayName("表名命名规范解析 —— {分层}_{业务域}_{实体描述}_{刷新周期}")
class TableNameConventionParserTest {

    @Nested
    @DisplayName("合规表名")
    class Conformant {

        @Test
        @DisplayName("四要素齐备时全部解析出来")
        void parsesAllFourSegments() {
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("dwd_order_detail_d");
            assertTrue(parsed.parsed());
            assertEquals("dwd", parsed.layerCode());
            assertEquals("order", parsed.domainCode());
            assertEquals("detail", parsed.entityDesc());
            assertEquals("d", parsed.refreshCycle());
        }

        @Test
        @DisplayName("实体描述含多段时按原样还原")
        void keepsMultiSegmentEntityDesc() {
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("dws_user_order_summary_1d_w");
            assertTrue(parsed.parsed());
            assertEquals("dws", parsed.layerCode());
            assertEquals("user", parsed.domainCode());
            assertEquals("order_summary_1d", parsed.entityDesc());
            assertEquals("w", parsed.refreshCycle());
        }

        @Test
        @DisplayName("大写表名按小写解析（物理名大小写不影响分类）")
        void normalizesCase() {
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("DWD_ORDER_DETAIL_D");
            assertTrue(parsed.parsed());
            assertEquals("dwd", parsed.layerCode());
            assertEquals("order", parsed.domainCode());
        }

        @Test
        @DisplayName("7 种刷新周期后缀都能识别")
        void recognizesAllRefreshCycles() {
            for (String cycle : new String[]{"d", "h", "w", "m", "y", "r", "f"}) {
                TableNameConventionParser.Parsed parsed =
                        TableNameConventionParser.parse("ods_shop_item_" + cycle);
                assertTrue(parsed.parsed(), "刷新周期 " + cycle + " 未被识别");
                assertEquals(cycle, parsed.refreshCycle());
            }
        }
    }

    @Nested
    @DisplayName("不合规表名")
    class NonConformant {

        @Test
        @DisplayName("未知分层：整体判不合规，但业务域/实体描述仍按分段抽出来")
        void unknownLayerStillExtractsDomain() {
            // 解析器是「独立抽段 + 整体合规标记」两个动作：
            // 首段不是已知分层 → layerCode 为 null、parsed 为 false；
            // 但第二段仍是合法业务域编码，照样抽出来 —— 目录树把它放进
            // 「未分类 / order」下，好过整张表只剩一个表名可看。
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("stg_order_detail_d");
            assertFalse(parsed.parsed());
            assertNull(parsed.layerCode());
            assertEquals("order", parsed.domainCode());
            assertEquals("detail", parsed.entityDesc());
            assertEquals("d", parsed.refreshCycle());
        }

        @Test
        @DisplayName("缺刷新周期后缀：不合规，但分层仍保留（目录树要能放进正确分层）")
        void missingRefreshCycleKeepsLayer() {
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("dwd_order_detail");
            assertFalse(parsed.parsed());
            assertEquals("dwd", parsed.layerCode());
            assertEquals("order", parsed.domainCode());
            assertNull(parsed.refreshCycle());
        }

        @Test
        @DisplayName("只有「分层_一段」时无法区分业务域与实体描述，判不合规")
        void twoSegmentsIsAmbiguous() {
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("dwd_orders");
            assertFalse(parsed.parsed());
            assertEquals("dwd", parsed.layerCode());
            assertNull(parsed.domainCode());
            assertEquals("orders", parsed.entityDesc());
        }

        @Test
        @DisplayName("业务域段含下划线拆出的多段名：仍可解析（首段作业务域）")
        void multiSegmentNameStillParsesFirstAsDomain() {
            // user_order_detail_d → 业务域 user、实体 order_detail
            TableNameConventionParser.Parsed parsed =
                    TableNameConventionParser.parse("dwd_user_order_detail_d");
            assertTrue(parsed.parsed());
            assertEquals("user", parsed.domainCode());
            assertEquals("order_detail", parsed.entityDesc());
        }

        @Test
        @DisplayName("空表名与 null 不抛异常，返回不合规结果")
        void blankInputIsSafe() {
            assertFalse(TableNameConventionParser.parse(null).parsed());
            assertFalse(TableNameConventionParser.parse("   ").parsed());
            assertFalse(TableNameConventionParser.parse("").parsed());
        }

        @Test
        @DisplayName("自定义分层集合生效：平台可增补分层而无需改代码")
        void honorsCustomLayerSet() {
            TableNameConventionParser.Parsed withDefault =
                    TableNameConventionParser.parse("app_sales_report_d");
            assertFalse(withDefault.parsed());

            TableNameConventionParser.Parsed withCustom =
                    TableNameConventionParser.parse("app_sales_report_d", Set.of("app"));
            assertTrue(withCustom.parsed());
            assertEquals("app", withCustom.layerCode());
            assertEquals("sales", withCustom.domainCode());
        }
    }

    @Nested
    @DisplayName("显示名与占位分层")
    class Labels {

        @Test
        @DisplayName("分层编码映射为中文名，未登记编码原样返回")
        void layerLabels() {
            assertEquals("贴源层", TableNameConventionParser.layerLabel("ods"));
            assertEquals("明细层", TableNameConventionParser.layerLabel("DWD"));
            assertEquals("未分类", TableNameConventionParser.layerLabel(
                    TableNameConventionParser.UNCLASSIFIED_LAYER));
            assertEquals("custom", TableNameConventionParser.layerLabel("custom"));
            assertEquals("", TableNameConventionParser.layerLabel(null));
        }

        @Test
        @DisplayName("占位分层不会被误判为真实分层")
        void unclassifiedLayerIsNotARealLayer() {
            assertFalse(TableNameConventionParser.DEFAULT_LAYERS.contains(
                    TableNameConventionParser.UNCLASSIFIED_LAYER));
            assertFalse(TableNameConventionParser.parse(
                    TableNameConventionParser.UNCLASSIFIED_LAYER).parsed());
        }

        @Test
        @DisplayName("刷新周期编码映射为中文名")
        void refreshCycleLabels() {
            assertEquals("日", TableNameConventionParser.refreshCycleLabel("d"));
            assertEquals("实时", TableNameConventionParser.refreshCycleLabel("r"));
            assertEquals("", TableNameConventionParser.refreshCycleLabel(null));
        }
    }
}
