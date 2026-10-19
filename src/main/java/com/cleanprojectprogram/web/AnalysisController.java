package com.cleanprojectprogram.web;

import com.cleanprojectprogram.analysis.AnalysisRequest;
import com.cleanprojectprogram.analysis.AnalysisReport;
import com.cleanprojectprogram.service.ProjectAnalyzer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
    private final ProjectAnalyzer analyzer;

    public AnalysisController(ProjectAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "application", "cleanProjectProgram");
    }

    @PostMapping("/analysis")
    public AnalysisReport analyze(@Valid @RequestBody AnalysisRequest request) {
        return analyzer.analyze(Path.of(request.projectPath())).report();
    }
}
