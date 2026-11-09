package com.offerchaser.application.persistence;

import java.util.List;

/**
 * 通用分页结果（B1 管理台列表接口的数据外壳）。
 * <p>
 * Jackson 序列化后即 {@code {"items":[...],"page":1,"size":20,"total":123}}。
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {
}
