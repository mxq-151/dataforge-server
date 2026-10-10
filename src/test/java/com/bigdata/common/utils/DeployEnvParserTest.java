package com.bigdata.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署环境解析 —— 库名 / 工作流名后缀 → 环境码，以及「按环境过滤」的判定口径。
 *
 * <p>这层判定同时服务资产目录（库名后缀）与调度指标（工作流名后缀），
 * 一旦口径漂移，「生产看板里混进测试表」这类错误会同时出现在多个页面上，
 * 因此把边界逐个钉死。</p>
 */
class DeployEnvParserTest {

    @Nested
    @DisplayName("parseEnv：后缀解析")
    class ParseEnv {

        @Test
        @DisplayName("识别 _prod / _gray / _test，大小写不敏感并容忍首尾空白")
        void recognizesKnownSuffixes() {
            assertEquals("prod", DeployEnvParser.parseEnv("dwd_trade_prod"));
            assertEquals("gray", DeployEnvParser.parseEnv("dwd_trade_gray"));
            assertEquals("test", DeployEnvParser.parseEnv("dwd_trade_test"));
            assertEquals("prod", DeployEnvParser.parseEnv("  DWD_TRADE_PROD  "));
        }

        @Test
        @DisplayName("没有下划线 / 下划线结尾 / 非三档后缀 → null（不猜环境）")
        void returnsNullWhenUnrecognized() {
            assertNull(DeployEnvParser.parseEnv("dw"));
            assertNull(DeployEnvParser.parseEnv("dwd_trade"));
            assertNull(DeployEnvParser.parseEnv("dwd_trade_"));
            assertNull(DeployEnvParser.parseEnv("dwd_trade_staging"));
            assertNull(DeployEnvParser.parseEnv("olist_code_ods_test2"));
        }

        @Test
        @DisplayName("null / 空白 → null")
        void returnsNullForBlank() {
            assertNull(DeployEnvParser.parseEnv(null));
            assertNull(DeployEnvParser.parseEnv(""));
            assertNull(DeployEnvParser.parseEnv("   "));
        }

        @Test
        @DisplayName("只认最后一段：中间出现 test 不算测试环境")
        void onlyLooksAtLastSegment() {
            assertNull(DeployEnvParser.parseEnv("test_dwd_trade"));
            assertEquals("prod", DeployEnvParser.parseEnv("test_dwd_trade_prod"));
        }
    }

    @Nested
    @DisplayName("matches：过滤口径")
    class Matches {

        @Test
        @DisplayName("env 为空 / null → 不过滤，一律命中")
        void blankEnvDisablesFilter() {
            assertTrue(DeployEnvParser.matches("dwd_trade_prod", null));
            assertTrue(DeployEnvParser.matches("dwd_trade_prod", ""));
            assertTrue(DeployEnvParser.matches("dwd_trade_prod", "   "));
            assertTrue(DeployEnvParser.matches("dw", null));
        }

        @Test
        @DisplayName("显式指定环境 → 只有同环境命中，大小写不敏感")
        void filtersByEnv() {
            assertTrue(DeployEnvParser.matches("dwd_trade_prod", "prod"));
            assertTrue(DeployEnvParser.matches("dwd_trade_prod", "PROD"));
            assertFalse(DeployEnvParser.matches("dwd_trade_prod", "test"));
            assertFalse(DeployEnvParser.matches("dwd_trade_gray", "prod"));
        }

        @Test
        @DisplayName("解析不出环境的名称在显式过滤时被排除（不猜成任何环境）")
        void unknownNameExcludedWhenFiltering() {
            assertFalse(DeployEnvParser.matches("dw", "prod"));
            assertFalse(DeployEnvParser.matches("dw", "test"));
            assertFalse(DeployEnvParser.matches(null, "prod"));
        }

        @Test
        @DisplayName("工作流名与库名同一套口径（提交调度把环境拼在名称末尾）")
        void appliesToWorkflowNames() {
            assertTrue(DeployEnvParser.matches("olist_code_ods_prod", "prod"));
            assertFalse(DeployEnvParser.matches("olist_code_ods_prod", "gray"));
        }
    }

    @Nested
    @DisplayName("normalize：入参归一化")
    class Normalize {

        @Test
        @DisplayName("合法值转小写去掉空白")
        void keepsLegalValues() {
            assertEquals("prod", DeployEnvParser.normalize(" PROD "));
            assertEquals("gray", DeployEnvParser.normalize("Gray"));
            assertEquals("test", DeployEnvParser.normalize("test"));
        }

        @Test
        @DisplayName("空值与脏值一律视为不过滤，避免脏参数把结果集清空")
        void dirtyValuesBecomeNull() {
            assertNull(DeployEnvParser.normalize(null));
            assertNull(DeployEnvParser.normalize(""));
            assertNull(DeployEnvParser.normalize("  "));
            assertNull(DeployEnvParser.normalize("unknown"));
            assertNull(DeployEnvParser.normalize("production"));
        }
    }
}
