package com.cleanprojectprogram.analysis;

import java.util.List;

/** A possible dead-code finding. autoRemovable only means it passed conservative local rules. */
public record Finding(
        String id,
        String kind,
        String severity,
        String symbol,
        String file,
        int line,
        boolean autoRemovable,
        String explanation,
        List<String> evidence
) {
    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
