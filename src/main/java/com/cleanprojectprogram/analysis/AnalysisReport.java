package com.cleanprojectprogram.analysis;

import java.time.Instant;
import java.util.List;

public record AnalysisReport(
        String projectPath,
        Instant analyzedAt,
        int javaFilesFound,
        int javaFilesParsed,
        int generatedFilesSkipped,
        int parseErrors,
        int findingsCount,
        int autoRemovableCount,
        int reviewRequiredCount,
        List<Finding> findings,
        List<String> warnings
) {
    public AnalysisReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
