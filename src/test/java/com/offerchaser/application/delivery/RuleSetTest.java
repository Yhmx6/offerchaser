package com.offerchaser.application.delivery;

import com.offerchaser.worker.boss.BossConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 B B2 规则模型测试：RuleSet/BlacklistPolicy 的不可变拷贝、默认值、defaultFrom 映射。
 */
class RuleSetTest {

    @Test
    void normalizesNullComponentsToSafeDefaults() {
        RuleSet rules = new RuleSet(0, null, null, null, null, null,
                null, null, null, null, null, null);

        assertEquals(0, rules.configVersion());
        assertNull(rules.sourceHash());
        assertEquals("本科", rules.highestDegree());
        assertEquals(SalaryStrategy.INTERSECT, rules.salaryStrategy());
        assertNull(rules.expectedSalaryMin());
        assertNull(rules.expectedSalaryMax());
        assertEquals(List.of(), rules.jobDirections());
        assertEquals(List.of(), rules.cities());
        assertEquals(List.of(), rules.blacklistCities(), "旧构造器兼容：不可接受城市为空");
        assertEquals(List.of(), rules.workTypes());
        assertEquals(BlacklistPolicy.empty(), rules.blacklist());
        assertNull(rules.filterDeadHr(), "filterDeadHr 为 null 表示不启用 HR 活跃过滤");
        assertEquals(List.of(), rules.deadStatus());
    }

    @Test
    void copiesListsAndDropsBlankItems() {
        List<String> directions = new ArrayList<>(List.of(" Java ", "大数据", " "));
        List<String> deadStatus = new ArrayList<>(List.of(" 今日活跃 ", "本月活跃"));
        RuleSet rules = new RuleSet(3, "hash", " 硕士 ", SalaryStrategy.FLOOR, 20, 40,
                directions, List.of(" 上海 "), List.of("全职"),
                BlacklistPolicy.empty(), true, deadStatus);

        assertEquals("硕士", rules.highestDegree());
        assertEquals(List.of("Java", "大数据"), rules.jobDirections());
        assertEquals(List.of("上海"), rules.cities());
        assertEquals(List.of("全职"), rules.workTypes());
        assertEquals(List.of("今日活跃", "本月活跃"), rules.deadStatus());
        assertEquals(Integer.valueOf(20), rules.expectedSalaryMin());
        assertEquals(Integer.valueOf(40), rules.expectedSalaryMax());
        assertEquals(Boolean.TRUE, rules.filterDeadHr());

        // 不可变拷贝：改原列表不影响规则，规则列表本身不可修改
        directions.add("测试");
        assertEquals(List.of("Java", "大数据"), rules.jobDirections());
        assertThrows(UnsupportedOperationException.class, () -> rules.jobDirections().add("x"));
    }

    @Test
    void copiesBlacklistCitiesAndDropsBlankItems() {
        List<String> blacklistCities = new ArrayList<>(List.of(" 北京 ", "", "上海"));
        RuleSet rules = new RuleSet(0, null, null, null, null, null,
                List.of(), List.of(), blacklistCities, List.of(),
                null, null, null);

        assertEquals(List.of("北京", "上海"), rules.blacklistCities());
        blacklistCities.add("广州");
        assertEquals(List.of("北京", "上海"), rules.blacklistCities(), "必须做不可变拷贝");
        assertThrows(UnsupportedOperationException.class, () -> rules.blacklistCities().add("x"));
    }

    @Test
    void defaultFromReadsBossConfigAndBlacklists() {
        BossConfig config = new BossConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(20, 40)));
        config.setFilterDeadHR(true);
        config.setDeadStatus(new ArrayList<>(List.of("本月活跃", "今日活跃")));

        RuleSet rules = RuleSet.defaultFrom(config,
                Set.of("B公司", "A公司"), Set.of("外包HR"), Set.of("保险"), SalaryStrategy.FLOOR);

        assertEquals(0, rules.configVersion(), "默认规则未持久化，版本为 0");
        assertNull(rules.sourceHash());
        assertEquals("本科", rules.highestDegree());
        assertEquals(SalaryStrategy.FLOOR, rules.salaryStrategy());
        assertEquals(Integer.valueOf(20), rules.expectedSalaryMin());
        assertEquals(Integer.valueOf(40), rules.expectedSalaryMax());
        assertEquals(List.of(), rules.jobDirections());
        assertEquals(List.of(), rules.cities());
        assertEquals(List.of(), rules.blacklistCities(), "默认规则不得包含不可接受城市");
        assertEquals(List.of(), rules.workTypes());
        assertEquals(BlacklistMatchMode.CONTAINS, rules.blacklist().matchMode());
        assertEquals(List.of("保险"), rules.blacklist().job());
        assertEquals(List.of("A公司", "B公司"), rules.blacklist().company(), "集合转列表需稳定排序");
        assertEquals(List.of("外包HR"), rules.blacklist().recruiter());
        assertEquals(List.of(), rules.blacklist().whitelist());
        assertEquals(Boolean.TRUE, rules.filterDeadHr());
        assertEquals(List.of("本月活跃", "今日活跃"), rules.deadStatus());
    }

    @Test
    void defaultFromToleratesNullConfigAndNullSets() {
        RuleSet rules = RuleSet.defaultFrom(null, null, null, null, null);

        assertEquals(0, rules.configVersion());
        assertEquals("本科", rules.highestDegree());
        assertEquals(SalaryStrategy.INTERSECT, rules.salaryStrategy());
        assertNull(rules.expectedSalaryMin());
        assertNull(rules.expectedSalaryMax());
        assertNull(rules.filterDeadHr());
        assertEquals(List.of(), rules.deadStatus());
        assertEquals(BlacklistPolicy.empty(), rules.blacklist());
    }

    @Test
    void defaultFromKeepsZeroBoundsForEngineToTreatAsUnset() {
        BossConfig config = new BossConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(0, 0)));

        RuleSet rules = RuleSet.defaultFrom(config, Set.of(), Set.of(), Set.of(), SalaryStrategy.INTERSECT);

        assertEquals(Integer.valueOf(0), rules.expectedSalaryMin());
        assertEquals(Integer.valueOf(0), rules.expectedSalaryMax());
        assertTrue(rules.blacklist().job().isEmpty());
    }

    @Test
    void deprecatedConstructorUsesDefaultRules() {
        BossConfig config = new BossConfig();
        config.setExpectedSalary(new ArrayList<>(List.of(30, 50)));
        JobRuleEngine engine = new JobRuleEngine(config, Set.of("黑公司"), Set.of(), Set.of("外包"), SalaryStrategy.INTERSECT);

        RuleSet rules = engine.getRules();
        assertEquals(0, rules.configVersion());
        assertEquals("本科", rules.highestDegree());
        assertEquals(List.of("外包"), rules.blacklist().job());
        assertEquals(List.of("黑公司"), rules.blacklist().company());
        assertEquals(Integer.valueOf(30), rules.expectedSalaryMin());
        assertEquals(Integer.valueOf(50), rules.expectedSalaryMax());
    }

    @Test
    void blacklistPolicyCopiesListsAndNormalizesMode() {
        List<String> job = new ArrayList<>(List.of(" 外包 ", ""));
        BlacklistPolicy policy = new BlacklistPolicy(null, job, null, null, null);

        assertEquals(BlacklistMatchMode.CONTAINS, policy.matchMode());
        assertEquals(List.of("外包"), policy.job());
        assertEquals(List.of(), policy.company());
        assertEquals(List.of(), policy.recruiter());
        assertEquals(List.of(), policy.whitelist());

        job.add("新增");
        assertEquals(List.of("外包"), policy.job());
        assertThrows(UnsupportedOperationException.class, () -> policy.job().add("x"));
        assertEquals(BlacklistPolicy.empty(),
                new BlacklistPolicy(null, null, null, null, null), "全空构造应等于 empty()");
        assertSame(BlacklistMatchMode.CONTAINS, BlacklistPolicy.empty().matchMode());
    }
}
