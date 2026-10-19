package com.cleanprojectprogram.service;

import com.cleanprojectprogram.analysis.AnalysisOutcome;
import com.cleanprojectprogram.analysis.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAnalyzerTest {
    @TempDir
    Path tempDir;

    private final ProjectAnalyzer analyzer = new ProjectAnalyzer();

    @Test
    void detectsUnusedPrivateMethodFieldAndLocalButPreservesUsedMethodAndConstructor() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                public class Example {
                    private int unusedField;
                    private int assignedOnly = calculate();

                    public Example() { }

                    private void unusedMethod() { }
                    private void usedMethod() { }
                    private int calculate() { return 4; }

                    public void run() {
                        usedMethod();
                        int neverRead = 10;
                        assignedOnly = 8;
                    }
                }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        List<Finding> findings = outcome.report().findings();

        assertTrue(findings.stream().anyMatch(f -> f.kind().equals("UNUSED_PRIVATE_METHOD")
                && f.symbol().contains("unusedMethod")));
        assertFalse(findings.stream().anyMatch(f -> f.kind().equals("UNUSED_PRIVATE_METHOD")
                && f.symbol().equals("Example#usedMethod()")));
        assertTrue(findings.stream().anyMatch(f -> f.kind().equals("UNUSED_PRIVATE_FIELD")
                && f.symbol().contains("unusedField") && f.autoRemovable()));
        assertTrue(findings.stream().anyMatch(f -> f.kind().equals("UNUSED_LOCAL_VARIABLE")
                && f.symbol().equals("neverRead")));
        assertFalse(findings.stream().anyMatch(f -> f.kind().toLowerCase().contains("constructor")
                && f.symbol().contains("Example")));
    }

    @Test
    void reportsUnreferencedMapperButNeverMarksItForAutomaticDeletion() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/OrphanMapper.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                @Mapper
                public interface OrphanMapper { String map(String value); }
                """);
        Path generated = tempDir.resolve("build/generated/sources/annotationProcessor/java/main/com/example/OrphanMapperImpl.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "package com.example; class OrphanMapperImpl implements OrphanMapper { public String map(String v){return v;} }");

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        Finding mapper = outcome.report().findings().stream()
                .filter(f -> f.kind().equals("UNREFERENCED_MAPPER_INTERFACE"))
                .findFirst().orElseThrow();

        assertFalse(mapper.autoRemovable());
        assertEquals(1, outcome.report().generatedFilesSkipped());
    }

    @Test
    void frameworkManagedClassesAreNotReportedAsDeadTypes() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/ManagedService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                @Service
                public class ManagedService { }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertFalse(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNREFERENCED_TYPE") && f.symbol().contains("ManagedService")));
    }

    @Test
    void parseErrorsDisableAllAutomaticEdits() throws Exception {
        Path good = tempDir.resolve("src/main/java/com/example/Unused.java");
        Files.createDirectories(good.getParent());
        Files.writeString(good, "package com.example; class Unused { }");
        Path bad = tempDir.resolve("src/main/java/com/example/Broken.java");
        Files.writeString(bad, "package com.example; public class Broken { this is not java }");

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertTrue(outcome.report().parseErrors() > 0);
        assertEquals(0, outcome.report().autoRemovableCount());
        assertTrue(outcome.proposedEdits().isEmpty());
    }

    @Test
    void detectsUnusedImports() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/ImportTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                import java.util.List;
                import java.util.Map;
                public class ImportTest {
                    private List<String> items;
                }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertTrue(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNUSED_IMPORT") && f.symbol().equals("java.util.Map")));
        assertFalse(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNUSED_IMPORT") && f.symbol().equals("java.util.List")));
    }

    @Test
    void detectsUnusedPublicConstant() throws Exception {
        Path source = tempDir.resolve("src/main/java/com/example/ConstantTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;
                public class ConstantTest {
                    public static final String USED = "used";
                    public static final String UNUSED = "unused";
                    public String getUsed() { return USED; }
                }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertTrue(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNUSED_CONSTANT") && f.symbol().equals("ConstantTest#UNUSED")));
        assertFalse(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNUSED_CONSTANT") && f.symbol().equals("ConstantTest#USED")));
    }

    @Test
    void detectsUnreachableTypes() throws Exception {
        Path entry = tempDir.resolve("src/main/java/com/example/EntryService.java");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class EntryService {
                    private ReachableService reachable = new ReachableService();
                    public void run() { reachable.doWork(); }
                }
                """);
        Path reachable = tempDir.resolve("src/main/java/com/example/ReachableService.java");
        Files.writeString(reachable, """
                package com.example;
                public class ReachableService {
                    public void doWork() {}
                }
                """);
        Path unreachable = tempDir.resolve("src/main/java/com/example/UnreachableService.java");
        Files.writeString(unreachable, """
                package com.example;
                public class UnreachableService {
                    public void orphan() {}
                }
                """);

        AnalysisOutcome outcome = analyzer.analyze(tempDir);
        assertTrue(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNREACHABLE_TYPE") && f.symbol().contains("UnreachableService")));
        assertFalse(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNREACHABLE_TYPE") && f.symbol().contains("ReachableService")));
        assertFalse(outcome.report().findings().stream()
                .anyMatch(f -> f.kind().equals("UNREACHABLE_TYPE") && f.symbol().contains("EntryService")));
    }
}
