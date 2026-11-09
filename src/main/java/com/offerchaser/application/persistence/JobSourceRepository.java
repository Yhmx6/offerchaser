package com.offerchaser.application.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B4 采集来源仓库：记录「账号 + 平台 + 岗位 + 关键词」的来源关联。
 * <p>
 * 唯一键 (account, platform, encrypt_id, keyword) 由数据库保证：重复采集同岗位同关键词只保留一条，
 * 同岗位多关键词则多行来源（不重复岗位）。旧 {@code boss_data} 数据没有来源记录，不在此表。
 */
@Service
@Slf4j
public class JobSourceRepository {

    private final DataSource dataSource;

    public JobSourceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 幂等记录来源关联（INSERT OR IGNORE）。
     *
     * @return true=本次新插入；false=已存在（重复采集）或写库失败
     */
    public boolean recordIfAbsent(String account, String platform, String encryptId,
                                  String keyword, String cityCode, String discoveredAt,
                                  String snapshotHash) {
        if (PersistenceSupport.trimToNull(account) == null
                || PersistenceSupport.trimToNull(platform) == null
                || PersistenceSupport.trimToNull(encryptId) == null) {
            log.warn("job_source 记录跳过：account/platform/encryptId 不能为空");
            return false;
        }
        String now = PersistenceSupport.now();
        String effectiveDiscoveredAt = PersistenceSupport.defaultIfBlank(discoveredAt, now);
        String sql = "INSERT OR IGNORE INTO job_source" +
                "(account, platform, encrypt_id, keyword, city_code, discovered_at, snapshot_hash, created_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.trim());
            statement.setString(2, platform.trim());
            statement.setString(3, encryptId.trim());
            statement.setString(4, PersistenceSupport.trimToNull(keyword));
            statement.setString(5, PersistenceSupport.trimToNull(cityCode));
            statement.setString(6, effectiveDiscoveredAt);
            statement.setString(7, PersistenceSupport.trimToNull(snapshotHash));
            statement.setString(8, now);
            boolean inserted = statement.executeUpdate() == 1;
            if (inserted) {
                log.debug("已记录采集来源 | account={} | platform={} | encryptId={} | keyword={}",
                        account, platform, encryptId, keyword);
            }
            return inserted;
        } catch (SQLException e) {
            log.error("记录采集来源失败 | encryptId={}：{}", encryptId, e.getMessage(), e);
            return false;
        }
    }

    /** 来源记录数（按 discovered_at 范围）。 */
    public int countRecords(String account, String from, String to) {
        String sql = "SELECT COUNT(*) FROM job_source WHERE account = ?" +
                rangeClause("discovered_at", from, to);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(account);
            addRangeParameters(parameters, from, to);
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("统计来源记录数失败 | account={}：{}", account, e.getMessage(), e);
            throw new IllegalStateException("统计来源记录数失败：" + e.getMessage(), e);
        }
    }

    /** 去重岗位数（按 discovered_at 范围）。 */
    public int countUniqueJobs(String account, String from, String to) {
        String sql = "SELECT COUNT(DISTINCT encrypt_id) FROM job_source WHERE account = ?" +
                rangeClause("discovered_at", from, to);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(account);
            addRangeParameters(parameters, from, to);
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("统计来源去重岗位数失败 | account={}：{}", account, e.getMessage(), e);
            throw new IllegalStateException("统计来源去重岗位数失败：" + e.getMessage(), e);
        }
    }

    /** 按关键词汇总：keyword, sourceRecords, uniqueJobs, firstDiscoveredAt, lastDiscoveredAt。 */
    public List<Map<String, Object>> summaryByKeyword(String account, String from, String to) {
        String sql = "SELECT COALESCE(keyword, '') AS keyword, COUNT(*) AS source_records, " +
                "COUNT(DISTINCT encrypt_id) AS unique_jobs, MIN(discovered_at) AS first_at, " +
                "MAX(discovered_at) AS last_at FROM job_source WHERE account = ?" +
                rangeClause("discovered_at", from, to) +
                " GROUP BY keyword ORDER BY source_records DESC, keyword ASC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(account);
            addRangeParameters(parameters, from, to);
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("keyword", resultSet.getString("keyword"));
                    row.put("sourceRecords", resultSet.getLong("source_records"));
                    row.put("uniqueJobs", resultSet.getLong("unique_jobs"));
                    row.put("firstDiscoveredAt", resultSet.getString("first_at"));
                    row.put("lastDiscoveredAt", resultSet.getString("last_at"));
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.error("按关键词汇总来源失败 | account={}：{}", account, e.getMessage(), e);
            throw new IllegalStateException("按关键词汇总来源失败：" + e.getMessage(), e);
        }
    }

    /** 按日来源序列：date, newJobs（当日去重岗位）, sourceRecords（当日来源记录）。 */
    public List<Map<String, Object>> dailySeries(String account, String from, String to) {
        String sql = "SELECT substr(discovered_at, 1, 10) AS date, " +
                "COUNT(DISTINCT encrypt_id) AS new_jobs, COUNT(*) AS source_records " +
                "FROM job_source WHERE account = ?" + rangeClause("discovered_at", from, to) +
                " GROUP BY date ORDER BY date ASC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> parameters = new ArrayList<>();
            parameters.add(account);
            addRangeParameters(parameters, from, to);
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", resultSet.getString("date"));
                    row.put("newJobs", resultSet.getLong("new_jobs"));
                    row.put("sourceRecords", resultSet.getLong("source_records"));
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            log.error("查询来源按日序列失败 | account={}：{}", account, e.getMessage(), e);
            throw new IllegalStateException("查询来源按日序列失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private String rangeClause(String column, String from, String to) {
        String start = normalizeStart(from);
        String end = normalizeEnd(to);
        StringBuilder clause = new StringBuilder();
        if (start != null) {
            clause.append(" AND ").append(column).append(" >= ?");
        }
        if (end != null) {
            clause.append(" AND ").append(column).append(" <= ?");
        }
        return clause.toString();
    }

    private void addRangeParameters(List<Object> parameters, String from, String to) {
        String start = normalizeStart(from);
        String end = normalizeEnd(to);
        if (start != null) {
            parameters.add(start);
        }
        if (end != null) {
            parameters.add(end);
        }
    }

    private String normalizeStart(String value) {
        String trimmed = PersistenceSupport.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() == 10 ? trimmed + " 00:00:00" : trimmed;
    }

    private String normalizeEnd(String value) {
        String trimmed = PersistenceSupport.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() == 10 ? trimmed + " 23:59:59" : trimmed;
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
    }
}
