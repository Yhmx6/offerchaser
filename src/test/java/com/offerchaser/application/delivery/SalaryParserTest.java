package com.offerchaser.application.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SalaryParser 单元测试：覆盖文档要求的全部样例、边界与非法格式。
 */
class SalaryParserTest {

    private static final double DELTA = 1e-6;

    // ==================== 通过样例 ====================

    @Test
    void parsesIntegerKRange() {
        SalaryInfo info = SalaryParser.parse("20-40K");

        assertTrue(info.parsable());
        assertFalse(info.negotiable());
        assertEquals(20.0, info.minK(), DELTA);
        assertEquals(40.0, info.maxK(), DELTA);
        assertEquals(12, info.months());
        assertEquals(SalaryParser.PERIOD_MONTH, info.period());
        assertEquals("20-40K", info.normalized());
        assertNull(info.errorMessage());
    }

    @Test
    void parsesDecimalSingleValueAndDoesNotBecome125K() {
        // 旧实现去掉非数字字符会把 12.5K 解析成 125K，这里必须保持 12.5
        SalaryInfo info = SalaryParser.parse("12.5K");

        assertTrue(info.parsable());
        assertEquals(12.5, info.minK(), DELTA);
        assertEquals(12.5, info.maxK(), DELTA);
        assertNotEquals(125.0, info.minK());
        assertEquals("12.5K", info.normalized());
    }

    @Test
    void parsesDecimalRangeWithMonths() {
        SalaryInfo info = SalaryParser.parse("12.5-18.5K·14薪");

        assertTrue(info.parsable());
        assertEquals(12.5, info.minK(), DELTA);
        assertEquals(18.5, info.maxK(), DELTA);
        assertEquals(14, info.months());
        assertEquals(SalaryParser.PERIOD_MONTH, info.period());
        assertEquals("12.5-18.5K·14薪", info.normalized());
    }

    @Test
    void parsesWanAsMonthlySalary() {
        SalaryInfo info = SalaryParser.parse("1.5-2.5万");

        assertTrue(info.parsable());
        assertEquals(15.0, info.minK(), DELTA);
        assertEquals(25.0, info.maxK(), DELTA);
        assertEquals(SalaryParser.PERIOD_MONTH, info.period());
        assertEquals("15-25K", info.normalized());
    }

    @Test
    void parsesAnnualWanByDividingTwelveMonths() {
        SalaryInfo info = SalaryParser.parse("20-40万/年");

        assertTrue(info.parsable());
        // 20 万/年 = 200000 / 12 / 1000 ≈ 16.6667K；40 万/年 ≈ 33.3333K
        assertEquals(16.6667, info.minK(), 0.001);
        assertEquals(33.3333, info.maxK(), 0.001);
        assertEquals(SalaryParser.PERIOD_YEAR, info.period());
        assertEquals("20-40万/年", info.normalized());
    }

    @Test
    void parsesDailySalaryByWorkDays() {
        SalaryInfo info = SalaryParser.parse("300-500元/天");

        assertTrue(info.parsable());
        // 300 × 21.75 ÷ 1000 = 6.525K；500 × 21.75 ÷ 1000 = 10.875K
        assertEquals(6.525, info.minK(), DELTA);
        assertEquals(10.875, info.maxK(), DELTA);
        assertEquals(SalaryParser.PERIOD_DAY, info.period());
        assertEquals("300-500元/天", info.normalized());
    }

    @Test
    void parsesNegotiable() {
        SalaryInfo info = SalaryParser.parse("面议");

        assertTrue(info.parsable());
        assertTrue(info.negotiable());
        assertNull(info.minK());
        assertNull(info.maxK());
        assertNull(info.months());
        assertEquals(SalaryParser.PERIOD_NEGOTIABLE, info.period());
        assertEquals("面议", info.normalized());
        assertNull(info.errorMessage());
    }

    @Test
    void parsesNegotiableInsideLongerText() {
        SalaryInfo info = SalaryParser.parse("薪资面议");

        assertTrue(info.parsable());
        assertTrue(info.negotiable());
        assertEquals(SalaryParser.PERIOD_NEGOTIABLE, info.period());
    }

    @Test
    void parsesOpenEndedUpperBound() {
        SalaryInfo info = SalaryParser.parse("20K以上");

        assertTrue(info.parsable());
        assertEquals(20.0, info.minK(), DELTA);
        assertNull(info.maxK(), "「以上」表示上限未知，maxK 应为 null");
        assertEquals("20K以上", info.normalized());
    }

    @Test
    void parsesOpenEndedWithQiSuffix() {
        SalaryInfo info = SalaryParser.parse("20K起");

        assertTrue(info.parsable());
        assertEquals(20.0, info.minK(), DELTA);
        assertNull(info.maxK());
        assertEquals("20K起", info.normalized());
    }

    // ==================== 全角与空白 ====================

    @Test
    void parsesFullWidthCharacters() {
        SalaryInfo kRange = SalaryParser.parse("２０－４０Ｋ");
        assertEquals(20.0, kRange.minK(), DELTA);
        assertEquals(40.0, kRange.maxK(), DELTA);
        assertEquals("20-40K", kRange.normalized());

        SalaryInfo decimalK = SalaryParser.parse("１２．５Ｋ");
        assertEquals(12.5, decimalK.minK(), DELTA);
        assertEquals(12.5, decimalK.maxK(), DELTA);
        assertEquals("12.5K", decimalK.normalized());

        SalaryInfo wan = SalaryParser.parse("１．５－２．５万");
        assertEquals(15.0, wan.minK(), DELTA);
        assertEquals(25.0, wan.maxK(), DELTA);

        SalaryInfo day = SalaryParser.parse("３００－５００元／天");
        assertEquals(6.525, day.minK(), DELTA);
        assertEquals(10.875, day.maxK(), DELTA);

        SalaryInfo above = SalaryParser.parse("２０Ｋ以上");
        assertEquals(20.0, above.minK(), DELTA);
        assertNull(above.maxK());
    }

    @Test
    void ignoresWhitespaceInSalaryText() {
        SalaryInfo info = SalaryParser.parse(" 20 - 40 K ");

        assertTrue(info.parsable());
        assertEquals(20.0, info.minK(), DELTA);
        assertEquals(40.0, info.maxK(), DELTA);
        assertEquals("20-40K", info.normalized());
    }

    // ==================== 边界写法 ====================

    @Test
    void parsesUnitOnBothSides() {
        SalaryInfo info = SalaryParser.parse("12.5K-18.5K·14薪");

        assertTrue(info.parsable());
        assertEquals(12.5, info.minK(), DELTA);
        assertEquals(18.5, info.maxK(), DELTA);
        assertEquals(14, info.months());
    }

    @Test
    void keepsExplicitMonthsInNormalizedText() {
        SalaryInfo thirteen = SalaryParser.parse("20-40K·13薪");
        assertEquals(13, thirteen.months());
        assertEquals("20-40K·13薪", thirteen.normalized());

        SalaryInfo twelve = SalaryParser.parse("20-40K");
        assertEquals(12, twelve.months(), "未写薪数时按 12 薪展示");
    }

    @Test
    void parsesYuanPerMonth() {
        SalaryInfo info = SalaryParser.parse("20000-40000元/月");

        assertTrue(info.parsable());
        assertEquals(20.0, info.minK(), DELTA);
        assertEquals(40.0, info.maxK(), DELTA);
        assertEquals("20-40K", info.normalized());
    }

    @Test
    void parsesLowerCaseK() {
        SalaryInfo info = SalaryParser.parse("20-40k");

        assertTrue(info.parsable());
        assertEquals(20.0, info.minK(), DELTA);
        assertEquals(40.0, info.maxK(), DELTA);
    }

    @Test
    void parsesWanWithMonthsSuffix() {
        SalaryInfo info = SalaryParser.parse("1.5-2.5万·14薪");

        assertTrue(info.parsable());
        assertEquals(15.0, info.minK(), DELTA);
        assertEquals(25.0, info.maxK(), DELTA);
        assertEquals(14, info.months());
        assertEquals("15-25K·14薪", info.normalized());
    }

    // ==================== 未知/非法格式 ====================

    @Test
    void nullOrBlankTextIsUnparsable() {
        SalaryInfo nullInfo = SalaryParser.parse(null);
        assertFalse(nullInfo.parsable());
        assertNull(nullInfo.minK());
        assertNull(nullInfo.normalized());
        assertEquals(SalaryParser.PERIOD_UNKNOWN, nullInfo.period());
        assertEquals("薪资文本为空", nullInfo.errorMessage());

        SalaryInfo blankInfo = SalaryParser.parse("   ");
        assertFalse(blankInfo.parsable());
        assertEquals("薪资文本为空", blankInfo.errorMessage());
    }

    @Test
    void unknownFormatsAreUnparsable() {
        assertFalse(SalaryParser.parse("15-25").parsable(), "缺少单位");
        assertFalse(SalaryParser.parse("薪资保密").parsable());
        assertFalse(SalaryParser.parse("abc").parsable());
        assertFalse(SalaryParser.parse("10-20元/小时").parsable(), "不支持的计薪周期");
        assertFalse(SalaryParser.parse("14薪").parsable(), "只有薪数没有区间");
    }

    @Test
    void unparsableResultHasErrorMessage() {
        SalaryInfo info = SalaryParser.parse("薪资保密");

        assertFalse(info.parsable());
        assertFalse(info.negotiable());
        assertNull(info.minK());
        assertNull(info.maxK());
        assertNull(info.months());
        assertEquals(SalaryParser.PERIOD_UNKNOWN, info.period());
        assertNull(info.normalized());
        assertTrue(info.errorMessage() != null && !info.errorMessage().isBlank());
    }
}
