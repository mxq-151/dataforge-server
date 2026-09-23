package com.bigdata.modules.quality.enums;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量规则类型「三方同源」契约测试。
 *
 * <p>背景：7 类规则类型是全链路统一术语，同时出现在三个载体上 ——
 * <ol>
 *   <li>服务端枚举 {@link QualityRuleType}（落库与校验分支的依据）；</li>
 *   <li>前端 {@code dataforge/src/types/quality.ts} 的 {@code QUALITY_RULE_TYPES}
 *       （UI 展示与提示词生成的唯一事实源）；</li>
 *   <li>建库脚本 {@code sql/init/dataforge-*.sql} 里 {@code dq_rule_template} 的内置模板种子
 *       （决定 template_code 与 rule_type 是否同名）。</li>
 * </ol>
 * </p>
 *
 * <p>这三处一旦有一处单独改动，就会出现「AI 按 A 写规则、平台按 B 存规则、界面按 C 显示」
 * 的静默断裂 —— 两边都不报错，只体现为覆盖率统计口径对不上。因此这里把三处**逐字**对齐，
 * 任何一处改文案或改编码都会让本测试失败。</p>
 *
 * <p>前端文件通过相对路径定位，找不到时跳过（例如只 clone 了后端仓库的 CI）；
 * 可用环境变量 {@code DF_FRONTEND_DIR} 显式指定前端仓库根目录。</p>
 */
@DisplayName("质量规则类型 —— 服务端 / 前端 / 建库脚本三方同源")
class QualityRuleTypeContractTest {

    /** 7 类规则类型的规范化顺序（即提示词编号顺序，不得调整） */
    private static final List<QualityRuleType> ORDERED = List.of(
            QualityRuleType.PRIMARY_KEY_UNIQUE,
            QualityRuleType.NOT_NULL,
            QualityRuleType.VALUE_RANGE,
            QualityRuleType.ROW_COUNT_FLUCTUATION,
            QualityRuleType.ROW_COUNT_MOM,
            QualityRuleType.UPSTREAM_DOWNSTREAM_RECON,
            QualityRuleType.METRIC_CONSISTENCY);

    // ==================================================================
    // 一、枚举自身
    // ==================================================================

    @Nested
    @DisplayName("枚举自洽")
    class EnumSelfConsistency {

        @Test
        @DisplayName("固定 7 类，且声明顺序与提示词编号一致")
        void fixedSevenInOrder() {
            assertEquals(7, QualityRuleType.values().length, "规则类型必须固定为 7 类");
            assertEquals(ORDERED, Arrays.asList(QualityRuleType.values()),
                    "枚举声明顺序即提示词里的 1..7 编号，调整顺序等于改提示词契约");
        }

        @Test
        @DisplayName("code 为大写下划线形式且两两不同")
        void codesAreDistinctUpperSnake() {
            List<String> codes = ORDERED.stream().map(QualityRuleType::getCode).collect(Collectors.toList());
            assertEquals(7, Set.copyOf(codes).size(), "规则类型编码出现重复: " + codes);
            for (String code : codes) {
                assertTrue(code.matches("^[A-Z][A-Z0-9_]*$"), "编码不是大写下划线形式: " + code);
            }
        }

        @Test
        @DisplayName("label 与 desc 非空且 label 两两不同（自造同义名会让覆盖率统计串味）")
        void labelsAndDescsDistinct() {
            List<String> labels = ORDERED.stream().map(QualityRuleType::getLabel).collect(Collectors.toList());
            assertEquals(7, Set.copyOf(labels).size(), "规则类型名称出现重复: " + labels);
            for (QualityRuleType type : ORDERED) {
                assertTrue(type.getLabel() != null && !type.getLabel().isBlank(), type + " 缺少规则类型名");
                assertTrue(type.getDesc() != null && !type.getDesc().isBlank(), type + " 缺少规则类型说明");
            }
        }

        @Test
        @DisplayName("校验对象范围固定为 COLUMN / TABLE / PAIR")
        void scopes() {
            assertTrue(QualityRuleType.PRIMARY_KEY_UNIQUE.isColumnScope());
            assertTrue(QualityRuleType.NOT_NULL.isColumnScope());
            assertTrue(QualityRuleType.VALUE_RANGE.isColumnScope());
            assertTrue(QualityRuleType.PRIMARY_KEY_UNIQUE.getScope() == QualityRuleType.RuleScope.COLUMN);
            assertTrue(QualityRuleType.ROW_COUNT_FLUCTUATION.getScope() == QualityRuleType.RuleScope.TABLE);
            assertTrue(QualityRuleType.ROW_COUNT_MOM.getScope() == QualityRuleType.RuleScope.TABLE);
            assertTrue(QualityRuleType.UPSTREAM_DOWNSTREAM_RECON.isPairScope());
            assertTrue(QualityRuleType.METRIC_CONSISTENCY.isPairScope());
        }

        @Test
        @DisplayName("按编码与按名称都能反查（S2 稽核规则清单落库的解析依据）")
        void lookups() {
            for (QualityRuleType type : ORDERED) {
                assertEquals(type, QualityRuleType.fromCode(type.getCode()));
                // 名称可能带前后空白，反查须先 trim
                assertEquals(type, QualityRuleType.fromLabel("  " + type.getLabel() + " "));
            }
            assertNull(QualityRuleType.fromCode("NOT_A_REAL_TYPE"));
            assertNull(QualityRuleType.fromLabel("主键唯一性校验"));
            assertNull(QualityRuleType.fromCode(null));
            assertNull(QualityRuleType.fromLabel(null));
        }
    }

    // ==================================================================
    // 二、前端 types/quality.ts
    // ==================================================================

    @Nested
    @DisplayName("前端 types/quality.ts 同源")
    class FrontendSameSource {

        @Test
        @DisplayName("前端 QUALITY_RULE_TYPES 的 code / label / desc / scope 与枚举逐字一致")
        void ruleTypesMatchFrontend() throws IOException {
            String ts = readFrontendQualityTypes();

            // 形如：{ code: 'NOT_NULL', label: '非空', desc: '关键字段空值与空串检测', scope: 'COLUMN' },
            Pattern entry = Pattern.compile(
                    "\\{\\s*code:\\s*'([A-Z_]+)'\\s*,\\s*label:\\s*'([^']*)'\\s*,\\s*desc:\\s*'([^']*)'\\s*,\\s*scope:\\s*'([A-Z]+)'\\s*\\}");
            Matcher matcher = entry.matcher(ts);
            List<String> frontendCodes = new ArrayList<>();
            while (matcher.find()) {
                String code = matcher.group(1);
                String label = matcher.group(2);
                String desc = matcher.group(3);
                String scope = matcher.group(4);
                frontendCodes.add(code);

                QualityRuleType type = QualityRuleType.fromCode(code);
                assertNotNull(type, "前端存在服务端未实现的规则类型: " + code);
                assertEquals(type.getLabel(), label,
                        "规则类型 " + code + " 的名称与前端不一致（改文案等于改提示词契约）");
                assertEquals(type.getDesc(), desc,
                        "规则类型 " + code + " 的说明与前端不一致");
                assertEquals(type.getScope().name(), scope,
                        "规则类型 " + code + " 的校验对象范围与前端不一致");
            }

            assertEquals(ORDERED.stream().map(QualityRuleType::getCode).collect(Collectors.toList()),
                    frontendCodes,
                    "前端规则类型的编码集合或顺序与服务端不一致");
        }

        @Test
        @DisplayName("前端 QUALITY_DISPOSE_MODES 与处置方式枚举同源")
        void disposeModesMatchFrontend() throws IOException {
            String ts = readFrontendQualityTypes();
            for (QualityDisposeMode mode : QualityDisposeMode.values()) {
                String expected = "{ code: '" + mode.getCode() + "', label: '" + mode.getLabel() + "' }";
                assertTrue(ts.contains(expected), "前端缺少处置方式声明: " + expected);
            }
        }

        @Test
        @DisplayName("前端把 7 类规则类型声明为只读常量（防止界面侧私自扩展类型）")
        void frontendDeclaresReadonly() throws IOException {
            String ts = readFrontendQualityTypes();
            assertTrue(ts.contains("readonly QualityRuleTypeMeta[]"),
                    "前端 QUALITY_RULE_TYPES 应为 readonly，否则界面侧可绕过契约新增类型");
        }
    }

    // ==================================================================
    // 三、建库脚本种子
    // ==================================================================

    @Nested
    @DisplayName("建库脚本内置模板种子同源")
    class InitScriptSeed {

        @Test
        @DisplayName("dq_rule_template 种子的 template_code == rule_type，且 7 类齐备、文案与枚举一致")
        void builtinTemplateSeedMatchesEnum() throws IOException {
            String sql = readInitScript();

            // 形如：('NOT_NULL', '非空', 'NOT_NULL', 'builtin', '关键字段空值与空串检测', 1),
            Pattern seed = Pattern.compile(
                    "\\('([A-Z_]+)',\\s*'([^']*)',\\s*'([A-Z_]+)',\\s*'builtin'");
            Matcher matcher = seed.matcher(sql);
            List<String> seededCodes = new ArrayList<>();
            while (matcher.find()) {
                String templateCode = matcher.group(1);
                String templateName = matcher.group(2);
                String ruleType = matcher.group(3);
                seededCodes.add(templateCode);

                assertEquals(templateCode, ruleType,
                        "内置模板的 template_code 必须与 rule_type 同名，否则按模板建规则时找不到归属类型");
                QualityRuleType type = QualityRuleType.fromCode(ruleType);
                assertNotNull(type, "建库脚本种子里出现未知规则类型: " + ruleType);
                assertEquals(type.getLabel(), templateName,
                        "内置模板 " + templateCode + " 的名称与枚举不一致");
            }

            assertEquals(ORDERED.stream().map(QualityRuleType::getCode).collect(Collectors.toList()),
                    seededCodes,
                    "建库脚本里的内置模板编码集合或顺序与枚举不一致");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 读取前端 types/quality.ts；文件不存在时跳过本组断言（仅后端仓库的 CI 场景）。 */
    private String readFrontendQualityTypes() throws IOException {
        Path file = resolveFrontendDir().resolve("src/types/quality.ts");
        Assumptions.assumeTrue(Files.exists(file),
                "未找到前端契约文件 " + file + "，跳过三方同源断言（可设 DF_FRONTEND_DIR 指定前端根目录）");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 读取 PostgreSQL 建库脚本（四方言里种子内容一致，取一份即可）。 */
    private String readInitScript() throws IOException {
        Path file = Paths.get("src/main/resources/sql/init/dataforge-postgresql.sql").toAbsolutePath();
        Assumptions.assumeTrue(Files.exists(file), "未找到建库脚本 " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 定位前端仓库根目录。
     * <p>优先取环境变量 {@code DF_FRONTEND_DIR}；否则按本机目录布局推断，
     * 常见两种：与本仓库同级，或位于上一级（dataforge-docker 与 dataforge 并列）。</p>
     */
    private Path resolveFrontendDir() {
        String override = System.getenv("DF_FRONTEND_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim()).toAbsolutePath();
        }
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolveSibling("dataforge"),
                cwd.getParent() != null ? cwd.getParent().resolveSibling("dataforge") : cwd.resolve("dataforge"),
                cwd.resolve("dataforge"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("src/types/quality.ts"))) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    @Test
    @DisplayName("枚举不得出现历史同义词（禁止自造同义词约束的服务端侧落地）")
    void noSynonymTypes() {
        List<String> labels = ORDERED.stream().map(QualityRuleType::getLabel).collect(Collectors.toList());
        assertFalse(labels.contains("主键唯一性校验"), "出现了自造同义名「主键唯一性校验」");
        assertFalse(labels.contains("非空校验"), "出现了自造同义名「非空校验」");
        assertTrue(labels.contains("非空"), "「非空」是既定术语，不得改名");
        assertTrue(labels.contains("主键唯一性"), "「主键唯一性」是既定术语，不得改名");
    }
}
