package com.offerchaser.application.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleConfigService} 对新增 {@code blacklistCities}（不可接受城市）的校验/规范化/兼容测试。
 * 只调用 {@code validateAndCanonicalize}（不落库、不联网），用 null 依赖构造即可。
 */
class RuleConfigServiceBlacklistCitiesTest {

    private final RuleConfigService ruleConfigService = new RuleConfigService(null, null, null, null);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizeSortsAndDedupesBlacklistCities() {
        RulePayload payload = payloadWithBlacklistCities(new ArrayList<>(
                List.of(" 北京 ", "上海", "北京")));

        RulePayload canonical = ruleConfigService.validateAndCanonicalize(payload);

        assertEquals(List.of("上海", "北京"), canonical.blacklistCities(), "去空白、去重并排序");
    }

    @Test
    void nullBlacklistCitiesBecomesEmptyList() {
        RulePayload payload = payloadWithBlacklistCities(null);

        RulePayload canonical = ruleConfigService.validateAndCanonicalize(payload);

        assertEquals(List.of(), canonical.blacklistCities(), "空=不限");
        assertEquals(List.of("上海"), canonical.cities(), "旧可接受城市字段保持不变");
    }

    @Test
    void rejectsBlankOrOversizedBlacklistCitiesEntries() {
        List<String> blank = new ArrayList<>(List.of("北京", "  "));
        RuleValidationException blankError = assertThrows(RuleValidationException.class,
                () -> ruleConfigService.validateAndCanonicalize(payloadWithBlacklistCities(blank)));
        assertTrue(blankError.getMessage().contains("blacklistCities"));

        List<String> tooLong = new ArrayList<>(List.of("京".repeat(101)));
        RuleValidationException longError = assertThrows(RuleValidationException.class,
                () -> ruleConfigService.validateAndCanonicalize(payloadWithBlacklistCities(tooLong)));
        assertTrue(longError.getMessage().contains("blacklistCities"));
    }

    @Test
    void rejectsTooManyBlacklistCities() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            many.add("城市" + i);
        }
        RuleValidationException error = assertThrows(RuleValidationException.class,
                () -> ruleConfigService.validateAndCanonicalize(payloadWithBlacklistCities(many)));
        assertTrue(error.getMessage().contains("blacklistCities"));
    }

    @Test
    void legacyJsonWithoutBlacklistCitiesDeserializesAsNullAndStillValidates() throws Exception {
        String legacyJson = """
                {"highestDegree":"硕士","salaryStrategy":"INTERSECT","expectedSalaryMin":30,
                 "expectedSalaryMax":0,"jobDirections":["产品"],"cities":["上海"],
                 "workTypes":[],"blacklistMatchMode":"CONTAINS","blacklistJob":["外包"],
                 "blacklistCompany":[],"blacklistRecruiter":[],"whitelist":[],
                 "filterDeadHr":true,"deadStatus":[]}
                """;

        RulePayload legacy = objectMapper.readValue(legacyJson, RulePayload.class);
        assertNull(legacy.blacklistCities(), "旧 JSON 缺少字段时应为 null");

        RulePayload canonical = ruleConfigService.validateAndCanonicalize(legacy);
        assertEquals(List.of(), canonical.blacklistCities());
        assertEquals(List.of("上海"), canonical.cities());
        assertEquals(List.of("产品"), canonical.jobDirections());
    }

    /** 构造一个最小合法载荷，只更换 blacklistCities。 */
    private static RulePayload payloadWithBlacklistCities(List<String> blacklistCities) {
        return new RulePayload(
                "本科",
                "INTERSECT",
                20,
                0,
                List.of(),
                List.of("上海"),
                blacklistCities,
                List.of(),
                "CONTAINS",
                List.of("外包"),
                List.of(),
                List.of(),
                List.of(),
                true,
                List.of());
    }
}
