package com.cleanprojectprogram.analysis;

import jakarta.validation.constraints.NotBlank;

public record AnalysisRequest(@NotBlank String projectPath) {
}
