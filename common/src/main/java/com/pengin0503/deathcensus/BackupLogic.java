package com.pengin0503.deathcensus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return "deathcensus-backup-"
                + BACKUP_TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(effectiveZone))
                + ".json";
    }
}
