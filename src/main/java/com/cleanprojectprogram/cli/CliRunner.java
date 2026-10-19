package com.cleanprojectprogram.cli;

import com.cleanprojectprogram.analysis.AnalysisOutcome;
import com.cleanprojectprogram.analysis.AnalysisReport;
import com.cleanprojectprogram.analysis.CleanResult;
import com.cleanprojectprogram.service.ProjectAnalyzer;
import com.cleanprojectprogram.service.ProjectCleaner;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CliRunner implements ApplicationRunner {
    private final Environment environment;
    private final ProjectAnalyzer analyzer;
    private final ProjectCleaner cleaner;
    private final JsonMapper objectMapper;

    public CliRunner(Environment environment, ProjectAnalyzer analyzer,
                     ProjectCleaner cleaner, JsonMapper objectMapper) {
        this.environment = environment;
        this.analyzer = analyzer;
        this.cleaner = cleaner;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String projectPath = environment.getProperty("project-path");
        if (projectPath == null || projectPath.isBlank()) {
            return; // No CLI target: serve the local HTTP API instead.
        }

        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        AnalysisOutcome outcome = analyzer.analyze(root);
        printSummary(outcome.report());

        if (Boolean.parseBoolean(environment.getProperty("apply", "false"))) {
            boolean allowDirty = Boolean.parseBoolean(environment.getProperty("allow-dirty", "false"));
            CleanResult cleanResult = cleaner.apply(root, outcome, allowDirty);
            System.out.println("\nResultado de limpieza:");
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cleanResult));
            outcome = analyzer.analyze(root);
            System.out.println("\nReanálisis posterior a la limpieza:");
            printSummary(outcome.report());
        }

        String reportFile = environment.getProperty("report-file", "cleanProjectProgram-report.json");
        Path reportPath = Path.of(reportFile).toAbsolutePath().normalize();
        if (!reportPath.startsWith(root)) {
            Files.createDirectories(reportPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), outcome.report());
            System.out.println("\nInforme JSON: " + reportPath);
        } else {
            System.out.println("\nNo se escribió el informe dentro del proyecto analizado para no afectar sus evidencias de uso. "
                    + "Indica --report-file=/ruta/externa/informe.json.");
        }
    }

    private void printSummary(AnalysisReport report) throws IOException {
        System.out.println("\n====================================================");
        System.out.println(" cleanProjectProgram - resultado del análisis");
        System.out.println("====================================================");
        System.out.println("Proyecto: " + report.projectPath());
        System.out.println("Archivos Java encontrados: " + report.javaFilesFound());
        System.out.println("Archivos Java parseados: " + report.javaFilesParsed());
        System.out.println("Fuentes generadas omitidas: " + report.generatedFilesSkipped());
        System.out.println("Errores de análisis: " + report.parseErrors());
        System.out.println("Hallazgos totales: " + report.findingsCount());
        System.out.println("Candidatos de bajo riesgo: " + report.autoRemovableCount());
        System.out.println("Requieren revisión: " + report.reviewRequiredCount());
        System.out.println("Advertencias:");
        if (report.warnings().isEmpty()) {
            System.out.println("  Ninguna");
        } else {
            report.warnings().forEach(warning -> System.out.println("  - " + warning));
        }
        System.out.println("====================================================\n");
    }
}
