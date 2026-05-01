package com.ai.rag.service;

import com.ai.rag.metrics.RagMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * UPDATED — FIX #5 (Partition name injection risk).
 *
 * PROBLEM: archivePartitionConcurrently() used the partitionName string directly
 * in SQL via string concatenation:
 *   jdbc.execute("ALTER TABLE query_history DETACH PARTITION " + partitionName + " CONCURRENTLY");
 *   jdbc.execute("ALTER TABLE " + partitionName + " RENAME TO " + archiveName);
 *
 * If partitionName came from a DB query result that was somehow tampered with
 * (e.g. SQL injection in the pg_inherits query result, or a malicious table name),
 * this would execute arbitrary DDL. PostgreSQL doesn't allow parameterized DDL
 * statements (ALTER TABLE doesn't support PreparedStatement placeholders),
 * so we must validate the name strictly before use.
 *
 * FIX: Validate partitionName against a strict regex BEFORE using it in any SQL.
 * Only names matching "query_history_YYYY_MM" (exactly) are accepted.
 * Any other value throws IllegalArgumentException and the operation is skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartitionMaintenanceService {

    private final JdbcTemplate jdbc;
    private final RagMetrics   ragMetrics;

    private static final int MONTHS_AHEAD   = 3;
    private static final int MONTHS_TO_KEEP = 12;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy_MM");

    /**
     * FIX #5: Strict allowlist pattern for partition names.
     * Only "query_history_" followed by exactly 4 digits, underscore, 2 digits.
     * Nothing else passes — no SQL keywords, no semicolons, no injection vectors.
     */
    private static final Pattern PARTITION_NAME_PATTERN =
            Pattern.compile("^query_history_\\d{4}_\\d{2}$");

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void ensureFuturePartitions() {
        YearMonth now = YearMonth.now();
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            createPartitionIfAbsent(now.plusMonths(i));
        }
        ragMetrics.recordPartitionMaintenanceSuccess();
    }

    @Scheduled(cron = "0 0 2 1 * *", zone = "UTC")
    public void archiveOldPartitions() {
        YearMonth cutoff = YearMonth.now().minusMonths(MONTHS_TO_KEEP);
        String cutoffName = "query_history_" + cutoff.format(FMT);

        try {
            jdbc.query("""
                    SELECT child.relname AS partition_name
                    FROM pg_inherits
                    JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                    JOIN pg_class child  ON pg_inherits.inhrelid  = child.oid
                    WHERE parent.relname = 'query_history'
                      AND child.relname < ?
                    """,
                    rs -> {
                        String partitionName = rs.getString("partition_name");
                        try {
                            archivePartitionConcurrently(partitionName);
                        } catch (IllegalArgumentException e) {
                            log.error("[PartitionMaintenance] Skipping invalid name '{}': {}",
                                    partitionName, e.getMessage());
                        }
                    },
                    cutoffName);
        } catch (Exception e) {
            log.error("[PartitionMaintenance] Failed to find old partitions: {}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void createPartitionIfAbsent(YearMonth month) {
        String name  = "query_history_" + month.format(FMT);
        String start = month.atDay(1).toString();
        String end   = month.plusMonths(1).atDay(1).toString();

        try {
            // createPartitionIfAbsent names are generated internally — validate anyway
            validatePartitionName(name);
            jdbc.execute(String.format(
                    "CREATE TABLE IF NOT EXISTS %s " +
                    "PARTITION OF query_history " +
                    "FOR VALUES FROM ('%s') TO ('%s')",
                    name, start, end));
            log.debug("[PartitionMaintenance] Ensured partition: {}", name);
        } catch (Exception e) {
            log.error("[PartitionMaintenance] Failed to create partition {}: {}", name, e.getMessage());
        }
    }

    /**
     * FIX #5: Validates partition name before using it in SQL DDL.
     * DETACH PARTITION and ALTER TABLE do not support parameterized identifiers in JDBC,
     * so we must sanitize manually before string concatenation.
     *
     * @throws IllegalArgumentException if the name doesn't match the strict pattern
     */
    private void archivePartitionConcurrently(String partitionName) {
        // FIX #5: Reject anything that doesn't exactly match "query_history_YYYY_MM"
        validatePartitionName(partitionName);

        String archiveName = partitionName.replace("query_history_", "query_history_archive_");
        // Validate the derived archive name too (same pattern, different prefix)
        if (!archiveName.matches("^query_history_archive_\\d{4}_\\d{2}$")) {
            throw new IllegalArgumentException("Derived archive name is invalid: " + archiveName);
        }

        try {
            jdbc.execute("ALTER TABLE query_history DETACH PARTITION " + partitionName + " CONCURRENTLY");
            log.info("[PartitionMaintenance] Detached partition: {}", partitionName);

            jdbc.execute("ALTER TABLE " + partitionName + " RENAME TO " + archiveName);
            log.info("[PartitionMaintenance] Archived as: {}", archiveName);

        } catch (Exception e) {
            log.error("[PartitionMaintenance] Failed to archive {}: {}", partitionName, e.getMessage(), e);
        }
    }

    private void validatePartitionName(String name) {
        if (name == null || !PARTITION_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid partition name '" + name + "'. " +
                    "Expected format: query_history_YYYY_MM (e.g. query_history_2025_04)");
        }
    }
}
