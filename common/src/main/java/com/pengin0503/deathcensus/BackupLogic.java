package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BackupLogic {
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private BackupLogic() {
    }

    public static List<String> toDelete(List<String> namesOldestFirst, int keep) {
        if (namesOldestFirst == null || namesOldestFirst.isEmpty()) {
            return List.of();
        }
        int effectiveKeep = Math.max(1, keep);
        int excess = namesOldestFirst.size() - effectiveKeep;
        if (excess <= 0) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(namesOldestFirst.subList(0, excess)));
    }

    public static String backupFileName(long epochMillis, ZoneId zone) {
        return "deathcensus-backup-" + timestamp(epochMillis, zone) + ".json";
    }

    /**
     * Name for a freshly requested safety backup (review M4). The second-level
     * timestamp alone collides when a backup is followed by a delete/reset/
     * restore within the same second; adding the millisecond and a random
     * suffix means a new request can never resolve to an existing file and be
     * reported as success while returning a stale backup.
     */
    public static String uniqueBackupFileName(long epochMillis, ZoneId zone) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "deathcensus-backup-" + timestamp(epochMillis, zone)
                + String.format(Locale.ROOT, "-%03d-%s", Math.floorMod(epochMillis, 1000L), random)
                + ".json";
    }

    private static String timestamp(long epochMillis, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return BACKUP_TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(effectiveZone));
    }
}
