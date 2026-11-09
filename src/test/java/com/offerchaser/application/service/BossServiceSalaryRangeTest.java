package com.offerchaser.application.service;

import com.offerchaser.application.entity.BossJobDataEntity;
import com.offerchaser.application.mapper.BossJobDataMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0 薪资区间重构测试：旧解析语义（K/薪数/面议/宽松兜底）保持不变，
 * 新 {@link BossService.SalaryRange} 用区间交集判定，列表过滤从“中位数落区间”改为 INTERSECT。
 */
class BossServiceSalaryRangeTest {

    // ==================== 解析：旧语义保持 ====================

    @Test
    void parseSalaryKeepsLegacyFormats() {
        BossService.SalaryInfo range = BossService.parseSalary("20-40K");
        assertNotNull(range);
        assertEquals(20, range.minK);
        assertEquals(40, range.maxK);
        assertEquals(12, range.months);
        assertEquals(30.0, range.medianK);
        assertEquals(360_000L, range.annualTotal);

        BossService.SalaryInfo withMonths = BossService.parseSalary("35-65K·16薪");
        assertNotNull(withMonths);
        assertEquals(35, withMonths.minK);
        assertEquals(65, withMonths.maxK);
        assertEquals(16, withMonths.months);
        assertEquals(50.0, withMonths.medianK);
        assertEquals(800_000L, withMonths.annualTotal);

        BossService.SalaryInfo single = BossService.parseSalary("30K·15薪");
        assertNotNull(single);
        assertEquals(30, single.minK);
        assertEquals(30, single.maxK);
        assertEquals(15, single.months);
        assertEquals(450_000L, single.annualTotal);
    }

    @Test
    void parseSalaryKeepsNegotiableAndFallbackSemantics() {
        assertNull(BossService.parseSalary(null));
        assertNull(BossService.parseSalary("   "));
        assertNull(BossService.parseSalary("面议"));
        assertNull(BossService.parseSalary("20-40"));   // 无 K 单位 => 不可解析
        // 旧宽松清洗：去掉非数字/K 字符后仍尝试匹配（12.5K -> 125K 是旧实现既有怪癖，这里锁定兜底路径）
        BossService.SalaryInfo cleaned = BossService.parseSalary("12.5K");
        assertNotNull(cleaned);
        assertEquals(125, cleaned.minK);
        assertEquals(125, cleaned.maxK);
    }

    // ==================== 区间判定：交集 ====================

    @Test
    void salaryRangeIntersectsSupportsOverlapAndSingleSidedBounds() {
        BossService.SalaryRange expected = BossService.SalaryRange.monthlyFilter(20.0, 40.0);
        assertTrue(expected.intersects(BossService.SalaryRange.monthlyFilter(30.0, 50.0)));
        assertTrue(expected.intersects(BossService.SalaryRange.monthlyFilter(40.0, 45.0)), "边界相接算交集");
        assertFalse(expected.intersects(BossService.SalaryRange.monthlyFilter(50.0, 60.0)));
        assertFalse(expected.intersects(BossService.SalaryRange.monthlyFilter(1.0, 19.9)));

        // 只有下限的期望：岗位上限低于期望下限才排除
        BossService.SalaryRange minOnly = BossService.SalaryRange.monthlyFilter(20.0, null);
        assertTrue(minOnly.intersects(BossService.SalaryRange.monthlyFilter(10.0, 25.0)));
        assertFalse(minOnly.intersects(BossService.SalaryRange.monthlyFilter(5.0, 18.0)));

        // 只有上限的期望：岗位下限高于期望上限才排除
        BossService.SalaryRange maxOnly = BossService.SalaryRange.monthlyFilter(null, 40.0);
        assertTrue(maxOnly.intersects(BossService.SalaryRange.monthlyFilter(35.0, 50.0)));
        assertFalse(maxOnly.intersects(BossService.SalaryRange.monthlyFilter(50.0, 60.0)));

        // 期望完全不限 => 视为没有过滤
        BossService.SalaryRange unbounded = BossService.SalaryRange.monthlyFilter(null, null);
        assertTrue(unbounded.isUnbounded());
        assertTrue(unbounded.intersects(BossService.SalaryRange.monthlyFilter(100.0, 200.0)));
    }

    // ==================== 列表过滤：INTERSECT ====================

    @Test
    void listBossJobsFiltersByRangeIntersection() {
        BossJobDataMapper mapper = mock(BossJobDataMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                job("10-25K"), job("30-50K"), job("50-60K"), job("面议"), job(null)));
        BossService service = new BossService(null, null, null, null, mapper, null, null);

        // 期望 20-40K：10-25K 与 30-50K 有交集；50-60K / 面议 / null 无交集
        BossService.PagedResult filtered = service.listBossJobs(
                List.of(), null, null, null, 20.0, 40.0, null, 1, 10, false);
        assertEquals(2, filtered.total);
        assertEquals(List.of("10-25K", "30-50K"),
                filtered.items.stream().map(BossJobDataEntity::getSalary).toList());

        // 单边下限 30K：10-25K 上限不足；30-50K / 50-60K 通过
        BossService.PagedResult minOnly = service.listBossJobs(
                List.of(), null, null, null, 30.0, null, null, 1, 10, false);
        assertEquals(2, minOnly.total);
        assertEquals(List.of("30-50K", "50-60K"),
                minOnly.items.stream().map(BossJobDataEntity::getSalary).toList());

        // 不传薪资条件：全部放行（含面议/null）
        BossService.PagedResult all = service.listBossJobs(
                List.of(), null, null, null, null, null, null, 1, 10, false);
        assertEquals(5, all.total);
    }

    private BossJobDataEntity job(String salary) {
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setSalary(salary);
        return entity;
    }
}
