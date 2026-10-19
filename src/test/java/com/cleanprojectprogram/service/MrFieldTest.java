package com.cleanprojectprogram.service;

import com.cleanprojectprogram.analysis.AnalysisOutcome;
import com.cleanprojectprogram.analysis.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MrFieldTest {
    @TempDir
    Path tempDir;

    private final ProjectAnalyzer analyzer = new ProjectAnalyzer();

    @Test
    void fieldUsedOnlyViaMethodReferenceIsNotAutoRemoved() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/Mr.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                public class Mr {
                    private java.util.function.Supplier<String> task;

                    public java.util.function.Supplier<String> supply() {
                        return task::get;
                    }
                }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertFalse(outcome.report().findings().stream().anyMatch(f -> f.kind().equals("UNUSED_PRIVATE_FIELD")
                && f.symbol().contains("task") && f.autoRemovable()));
    }
}
