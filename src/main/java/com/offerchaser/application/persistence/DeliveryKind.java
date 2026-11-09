package com.offerchaser.application.persistence;

/**
 * 投递类型：首次沟通或跟进。
 * <p>
 * 两类投递使用独立的每日预算，互不占用。
 */
public enum DeliveryKind {
    /** 首次与招聘者打招呼 */
    FIRST_CONTACT,
    /** 对已确认首次沟通的岗位做跟进 */
    FOLLOWUP
}
