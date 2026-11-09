package com.offerchaser.application.persistence;

/**
 * 规则配置版本的只读视图（对应《阶段B-B2-实施契约》第 3 节的顶层 record 形状）。
 * <p>
 * 实际实现是 {@link RuleConfigRepository.RuleConfigVersion} record（{@code RuleConfigRepository}
 * 的嵌套类型，供 {@code RuleConfigService} 直接引用）；这里声明为接口，使
 * {@code RuleConfigRepository.RuleConfigVersion} 的返回值既可以赋给嵌套类型，也可以按契约
 * 使用顶层类型 {@code RuleConfigVersion} 接收/遍历，两种写法都能编译。
 */
public interface RuleConfigVersion {

    long id();

    int version();

    String rulesJson();

    String sourceHash();

    String note();

    /** 生成该版本时 live 配置（BossConfig+黑名单）的哈希；旧版本行为 null。 */
    String liveHash();

    String createdAt();
}
