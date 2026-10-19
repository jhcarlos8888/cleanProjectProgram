package com.cleanprojectprogram.analysis;

import java.nio.file.Path;
import java.util.List;

public record AnalysisOutcome(AnalysisReport report, List<ProposedEdit> proposedEdits) {
    public AnalysisOutcome {
        proposedEdits = proposedEdits == null ? List.of() : List.copyOf(proposedEdits);
    }

    public record ProposedEdit(
            String findingId,
            String kind,
            Path file,
            int startOffset,
            int endOffset,
            String symbol
    ) {
    }
}
