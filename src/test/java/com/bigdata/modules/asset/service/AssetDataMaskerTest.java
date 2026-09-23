package com.bigdata.modules.asset.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据脱敏取值器测试。
 *
 * <p>这一层补的是平台此前的断点：脱敏类型此前只被标注在<b>元数据</b>上，
 * 从没有对返回的<b>数据值</b>做过替换。资产预览是第一个会把真实数据行
 * 送到浏览器的入口，因此这里的每个分支都值得钉死 ——
 * 尤其是「未知类型」这条：配置写错时宁可过度脱敏，也不能漏。</p>
 */
@DisplayName("数据脱敏取值")
class AssetDataMaskerTest {

    @Test
    @DisplayName("full：无论原值多长都整体遮蔽")
    void fullMask() {
        assertEquals("***", AssetDataMasker.mask("13800138000", "full"));
        assertEquals("***", AssetDataMasker.mask("a", "full"));
    }

    @Test
    @DisplayName("idcard：保留前 6 后 4，中间等长遮蔽")
    void idCardMask() {
        String masked = AssetDataMasker.mask("11010119900307531X", "idcard");
        assertTrue(masked.startsWith("110101"), "应保留前 6 位地区码：" + masked);
        assertTrue(masked.endsWith("531X"), "应保留后 4 位：" + masked);
        assertEquals(18, masked.length(), "掩码后长度不变，避免泄露长度线索以外的信息");
        assertFalse(masked.contains("19900307"), "出生日期必须被遮蔽：" + masked);
    }

    @Test
    @DisplayName("phone：保留前 3 后 4")
    void phoneMask() {
        String masked = AssetDataMasker.mask("13812345678", "phone");
        assertEquals("138****5678", masked);
    }

    @Test
    @DisplayName("email：保留首字符与域名，用户名被遮蔽")
    void emailMask() {
        assertEquals("z***@example.com", AssetDataMasker.mask("zhangsan@example.com", "email"));
    }

    @Test
    @DisplayName("partial：保留首尾字符，中间定长星号")
    void partialMask() {
        assertEquals("张***丰", AssetDataMasker.mask("张三丰", "partial"));
        assertEquals("a*", AssetDataMasker.mask("ab", "partial"));
        assertEquals("***", AssetDataMasker.mask("a", "partial"));
    }

    @Test
    @DisplayName("未知类型按 full 兜底（配置写错时宁可过度脱敏）")
    void unknownTypeFailsClosed() {
        assertEquals("***", AssetDataMasker.mask("13800138000", "phone_number"));
        assertEquals("***", AssetDataMasker.mask("13800138000", "  FULL_PHONE  "));
    }

    @Test
    @DisplayName("大小写与首尾空白不影响识别")
    void normalizesInput() {
        assertEquals("138****5678", AssetDataMasker.mask("13812345678", " PHONE "));
        assertEquals("***", AssetDataMasker.mask("x", " Full "));
    }

    @Test
    @DisplayName("空值不处理：脱敏空值没有意义，且会把 null 变成字符串")
    void emptyValuesPassThrough() {
        assertNull(AssetDataMasker.mask(null, "full"));
        assertEquals("", AssetDataMasker.mask("", "full"));
    }

    @Test
    @DisplayName("未配置脱敏类型（null / 空白）时原样返回")
    void noRuleMeansNoMasking() {
        assertEquals("13800138000", AssetDataMasker.mask("13800138000", null));
        assertEquals("13800138000", AssetDataMasker.mask("13800138000", "   "));
    }

    @Test
    @DisplayName("畸形值退化到 partial 而不是抛异常（预览不能因一个脏值整页失败）")
    void malformedValuesDegradeGracefully() {
        // idcard：长度不足 10 位时无法按身份证规则切分，退化为 partial
        assertEquals("1***2", AssetDataMasker.mask("1232", "idcard"));
        // email：没有 @ 时退化为 partial
        assertEquals("n***l", AssetDataMasker.mask("notanemail", "email"));
        // phone：长度不足 7 位时退化为 partial
        assertEquals("1***5", AssetDataMasker.mask("12345", "phone"));
    }
}
