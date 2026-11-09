package com.offerchaser.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offerchaser.application.entity.CookieEntity;
import com.offerchaser.application.mapper.CookieMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Cookie 登录态持久化服务：按平台保存最新一份，表与唯一索引在启动时自动补齐。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CookieService {

    /**
     * cookie 表 DDL（IF NOT EXISTS，旧库补建；列名/类型/默认值必须与历史库保持一致）。
     */
    private static final String COOKIE_TABLE_DDL = "CREATE TABLE IF NOT EXISTS cookie ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "platform VARCHAR(50) NOT NULL, "
            + "cookie_value TEXT NOT NULL, "
            + "remark TEXT, "
            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)";

    /** 平台唯一索引：同一平台只保留一条逻辑记录 */
    private static final String COOKIE_PLATFORM_INDEX_DDL =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_cookie_platform ON cookie(platform)";

    private final CookieMapper cookieMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时确保 cookie 表与平台唯一索引存在。
     * <p>
     * 历史版本曾把 Cookie 改为 cookies/&lt;platform&gt;.json 文件存储并执行过 DROP TABLE cookie，
     * 那批用户回退到数据库存储后需要自动补表；否则所有 Cookie 读写都会报 no such table，
     * 表现为登录态完全无法保存。
     * </p>
     */
    @PostConstruct
    public void ensureCookieTable() {
        String tableDdl = COOKIE_TABLE_DDL;
        String platformIndexDdl = COOKIE_PLATFORM_INDEX_DDL;
        try {
            jdbcTemplate.execute(tableDdl);
            jdbcTemplate.execute(platformIndexDdl);
        } catch (Exception e) {
            log.error("创建 cookie 表失败，Cookie 将无法保存，原因={}", e.getMessage());
        }
    }

    /**
     * 读取指定平台最新的一条 Cookie 记录。
     *
     * @param platform 平台标识（当前只启用 boss）
     * @return Cookie 实体；平台没有记录时返回 null
     */
    public CookieEntity getCookieByPlatform(String platform) {
        return cookieMapper.selectOne(Wrappers.<CookieEntity>lambdaQuery()
                .eq(CookieEntity::getPlatform, platform)
                .orderByDesc(CookieEntity::getUpdatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 保存指定平台的 Cookie：已有记录则覆盖字段并刷新 updated_at，否则新增一条。
     *
     * @param platform    平台标识
     * @param cookieValue Cookie 值（JSON 文本）
     * @param remark      备注
     * @return 写入是否成功
     */
    public boolean saveOrUpdateCookie(String platform, String cookieValue, String remark) {
        CookieEntity existing = getCookieByPlatform(platform);
        if (existing == null) {
            return insertCookie(platform, cookieValue, remark);
        }
        existing.setCookieValue(cookieValue);
        existing.setRemark(remark);
        existing.setUpdatedAt(LocalDateTime.now());
        return cookieMapper.updateById(existing) > 0;
    }

    /**
     * 新增一条 Cookie 记录（created_at 与 updated_at 使用同一时刻）。
     */
    private boolean insertCookie(String platform, String cookieValue, String remark) {
        LocalDateTime now = LocalDateTime.now();
        CookieEntity created = new CookieEntity();
        created.setPlatform(platform);
        created.setCookieValue(cookieValue);
        created.setRemark(remark);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        return cookieMapper.insert(created) > 0;
    }

    /**
     * 清空指定平台所有 Cookie 记录的值。
     * <p>历史数据里同一平台可能存在重复行，这里按平台批量清空而不是只改一条。</p>
     *
     * @param platform 平台标识
     * @param remark   备注（写入最近一次操作原因）
     * @return 是否至少更新了一行
     */
    public boolean clearCookieByPlatform(String platform, String remark) {
        return cookieMapper.update(null, Wrappers.<CookieEntity>lambdaUpdate()
                .eq(CookieEntity::getPlatform, platform)
                .set(CookieEntity::getCookieValue, "")
                .set(CookieEntity::getRemark, remark)
                .set(CookieEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    /**
     * 删除指定平台的全部 Cookie 记录。
     *
     * @param platform 平台标识
     * @return 是否至少删除一行
     */
    public boolean deleteCookie(String platform) {
        return cookieMapper.delete(Wrappers.<CookieEntity>lambdaQuery()
                .eq(CookieEntity::getPlatform, platform)) > 0;
    }

    /**
     * 读取所有平台的 Cookie 记录。
     *
     * @return Cookie 记录列表（无记录时为空列表）
     */
    public List<CookieEntity> getAllCookies() {
        return cookieMapper.selectList(null);
    }
}
