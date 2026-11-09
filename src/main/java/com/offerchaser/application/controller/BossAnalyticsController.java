package com.offerchaser.application.controller;

import com.offerchaser.application.service.BossService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BOSS 旧版投递看板接口（{@code /api/boss}，只读兼容入口）。
 * <p>
 * 现役统计看板走 {@code /api/boss/metrics/*}；这里保留三条老路由给旧页面/脚本：
 * {@code /stats} 聚合统计、{@code /list} 分页列表、{@code /reload} 刷新 boss_data。
 * 三个端点都只读，不参与投递主流程。
 */
@RestController
@RequestMapping("/api/boss")
public class BossAnalyticsController {

    private static final String DEFAULT_PAGE_TEXT = "1";
    private static final String DEFAULT_PAGE_SIZE_TEXT = "20";
    private static final String STATUS_SEPARATOR = ",";

    private final BossService bossService;

    // 只读看板：不持有运行时状态，BossService 由 Spring 构造器注入
    public BossAnalyticsController(BossService bossService) {
        this.bossService = bossService;
    }
    // 构造函数只做依赖注入，没有额外初始化

    /**
     * 投递分析统计与图表，筛选维度与列表接口一致。
     */
    @GetMapping(path = "/stats")
    public BossService.StatsResponse getStats(
            @RequestParam(name = "statuses", required = false) String statuses,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "experience", required = false) String experience,
            @RequestParam(name = "degree", required = false) String degree,
            @RequestParam(name = "minK", required = false) Double minK,
            @RequestParam(name = "maxK", required = false) Double maxK,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "filterHeadhunter", required = false) Boolean filterHeadhunter) {
        JobFilter filter = JobFilter.from(statuses, location, experience, degree, minK, maxK, keyword, filterHeadhunter);
        return bossService.getBossStats(filter.statusList(), filter.location(), filter.experience(),
                filter.degree(), filter.minK(), filter.maxK(), filter.keyword(), filter.onlyHeadhunter());
    }

    /**
     * 岗位列表：筛选 + 分页。
     */
    @GetMapping(path = "/list")
    public BossService.PagedResult list(
            @RequestParam(name = "statuses", required = false) String statuses,
            @RequestParam(name = "location", required = false) String location,
            @RequestParam(name = "experience", required = false) String experience,
            @RequestParam(name = "degree", required = false) String degree,
            @RequestParam(name = "minK", required = false) Double minK,
            @RequestParam(name = "maxK", required = false) Double maxK,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "filterHeadhunter", required = false) Boolean filterHeadhunter,
            @RequestParam(name = "page", required = false, defaultValue = DEFAULT_PAGE_TEXT) Integer page,
            @RequestParam(name = "size", required = false, defaultValue = DEFAULT_PAGE_SIZE_TEXT) Integer size) {
        JobFilter filter = JobFilter.from(statuses, location, experience, degree, minK, maxK, keyword, filterHeadhunter);
        return bossService.listBossJobs(filter.statusList(), filter.location(), filter.experience(),
                filter.degree(), filter.minK(), filter.maxK(), filter.keyword(),
                page, size, filter.onlyHeadhunter());
    }

    /**
     * 刷新 boss_data 并返回统计结果（保留的兼容入口）。
     */
    @GetMapping(path = "/reload")
    public Map<String, Object> reload() {
        return bossService.reloadBossData();
    }

    /**
     * stats / list 共用的筛选条件解析结果。
     */
    private record JobFilter(
            List<String> statusList,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            boolean onlyHeadhunter) {

        static JobFilter from(String statuses, String location, String experience, String degree,
                              Double minK, Double maxK, String keyword, Boolean filterHeadhunter) {
            List<String> parsedStatuses = splitStatuses(statuses);
            boolean headhunterOnly = Boolean.TRUE.equals(filterHeadhunter);
            return new JobFilter(parsedStatuses, location, experience, degree,
                    minK, maxK, keyword, headhunterOnly);
        }

        /** 状态多选：空/空白视为不过滤；支持半角逗号分隔。 */
        private static List<String> splitStatuses(String statuses) {
            if (statuses == null || statuses.trim().isEmpty()) {
                return null;
            }
            String[] parts = statuses.split(STATUS_SEPARATOR);
            return Arrays.stream(parts)
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .collect(Collectors.toList());
        }
    }
}
