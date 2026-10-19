package com.cleanprojectprogram.analysis;

import java.util.List;

public record CleanResult(
        String backupDirectory,
        int filesBackedUp,
        int filesDeleted,
        int declarationsRemoved,
        List<String> appliedChanges,
        List<String> skippedChanges,
        String safetyMessage
) {
    public CleanResult {
        appliedChanges = appliedChanges == null ? List.of() : List.copyOf(appliedChanges);
        skippedChanges = skippedChanges == null ? List.of() : List.copyOf(skippedChanges);
    }
}
