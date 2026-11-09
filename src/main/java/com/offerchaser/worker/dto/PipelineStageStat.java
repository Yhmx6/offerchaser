package com.offerchaser.worker.dto;

import java.util.Map;

/**
 * 单个阶段的汇总统计。
 *
 * @param stage    阶段代码
 * @param label    阶段中文名
 * @param entered  进入该阶段的岗位数（同一岗位同一阶段只计一次）
 * @param outcomes 该阶段各结果的次数（PASS/REJECT/SKIP/FAIL/UNKNOWN/CONFIRM/DELIVERED...；RUNNING 不计）
 */
public record PipelineStageStat(String stage, String label, int entered, Map<String, Integer> outcomes) {
}
