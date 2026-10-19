package com.cleanprojectprogram.service;

import com.cleanprojectprogram.analysis.AnalysisException;
import com.cleanprojectprogram.analysis.AnalysisOutcome;
import com.cleanprojectprogram.analysis.AnalysisReport;
import com.cleanprojectprogram.analysis.CleanResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies only edits produced by the analyzer's strict low-risk rules. */
@Service
public class ProjectCleaner {
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public CleanResult apply(Path projectRoot, AnalysisOutcome outcome, boolean allowDirtyGitWorktree) {
        Path root = projectRoot.toAbsolutePath().normalize();
        AnalysisReport report = outcome.report();
        if (report.parseErrors() > 0) {
            throw new AnalysisException("No se permite --apply: el análisis contiene errores de lectura o sintaxis.");
        }
        if (outcome.proposedEdits().isEmpty()) {
            return new CleanResult("", 0, 0, 0, List.of(), List.of(),
                    "No hay cambios que cumplan las reglas conservadoras de eliminación automática.");
        }
        for (AnalysisOutcome.ProposedEdit edit : outcome.proposedEdits()) {
            Path normalized = edit.file().toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) {
                throw new AnalysisException("Se rechazó un cambio fuera del proyecto: " + normalized);
            }
        }
        requireCleanGitWorktreeIfAvailable(root, allowDirtyGitWorktree);

        Path backup = createBackupDirectory(root);
        Map<Path, String> originals = new HashMap<>();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Path> filesToDelete = outcome.proposedEdits().stream()
                .filter(edit -> edit.kind().equals("DELETE_FILE"))
                .map(edit -> edit.file().toAbsolutePath().normalize())
                .collect(Collectors.toSet());
        Map<Path, List<AnalysisOutcome.ProposedEdit>> rangeEdits = outcome.proposedEdits().stream()
                .filter(edit -> !edit.kind().equals("DELETE_FILE"))
                .filter(edit -> !filesToDelete.contains(edit.file().toAbsolutePath().normalize()))
                .collect(Collectors.groupingBy(edit -> edit.file().toAbsolutePath().normalize()));

        try {
            // Back up every original before the first mutation.
            for (Path file : filesToDelete) {
                originals.put(file, Files.readString(file, StandardCharsets.UTF_8));
                copyToBackup(root, backup, file);
            }
            for (Path file : rangeEdits.keySet()) {
                originals.put(file, Files.readString(file, StandardCharsets.UTF_8));
                copyToBackup(root, backup, file);
            }

            for (Path file : filesToDelete) {
                Files.delete(file);
                applied.add("Archivo eliminado: " + root.relativize(file).toString().replace('\\', '/')
                        + " (tipo package-private sin referencias detectables)");
            }

            for (Map.Entry<Path, List<AnalysisOutcome.ProposedEdit>> entry : rangeEdits.entrySet()) {
                Path file = entry.getKey();
                StringBuilder content = new StringBuilder(originals.get(file));
                List<AnalysisOutcome.ProposedEdit> edits = entry.getValue().stream()
                        .sorted(Comparator.comparingInt(AnalysisOutcome.ProposedEdit::startOffset).reversed())
                        .toList();
                int previousStart = Integer.MAX_VALUE;
                for (AnalysisOutcome.ProposedEdit edit : edits) {
                    int start = edit.startOffset();
                    int end = edit.endOffset();
                    if (start < 0 || end > content.length() || start >= end || end > previousStart) {
                        throw new AnalysisException("Los rangos de edición se solapan o son inválidos en " + file);
                    }
                    content.delete(start, end);
                    previousStart = start;
                    applied.add("Declaración retirada: " + edit.symbol() + " en "
                            + root.relativize(file).toString().replace('\\', '/'));
                }
                Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            // Best-effort rollback: restore every backed-up file if any write/delete fails.
            for (Map.Entry<Path, String> original : originals.entrySet()) {
                try {
                    Files.createDirectories(original.getKey().getParent());
                    Files.writeString(original.getKey(), original.getValue(), StandardCharsets.UTF_8);
                } catch (IOException rollbackException) {
                    skipped.add("Falló la restauración de " + original.getKey() + ": " + rollbackException.getMessage());
                }
            }
            throw new AnalysisException("No se pudo completar la limpieza; se intentó restaurar los archivos originales. Respaldo: "
                    + backup + ". Causa: " + ex.getMessage(), ex);
        }

        int fileDeletes = filesToDelete.size();
        int declarationsRemoved = applied.size() - fileDeletes;
        return new CleanResult(backup.toString(), originals.size(), fileDeletes, declarationsRemoved,
                applied, skipped,
                "La limpieza fue solicitada explícitamente. Revisa el diff y ejecuta las pruebas del proyecto antes de aceptar los cambios.");
    }

    private Path createBackupDirectory(Path root) {
        Path parent = root.getParent();
        if (parent == null) {
            parent = root;
        }
        String rootName = root.getFileName() == null ? "project" : root.getFileName().toString();
        String name = rootName + ".cleanProjectProgram-backup-" + LocalDateTime.now().format(BACKUP_FORMAT);
        Path backup = parent.resolve(name);
        int sequence = 1;
        while (Files.exists(backup)) {
            backup = parent.resolve(name + "-" + sequence++);
        }
        try {
            Files.createDirectories(backup);
            return backup;
        } catch (IOException ex) {
            throw new AnalysisException("No se pudo crear el directorio de respaldo " + backup + ": " + ex.getMessage(), ex);
        }
    }

    private void copyToBackup(Path root, Path backup, Path source) throws IOException {
        Path target = backup.resolve(root.relativize(source));
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }

    private void requireCleanGitWorktreeIfAvailable(Path root, boolean allowDirty) {
        Process process;
        try {
            process = new ProcessBuilder("git", "-C", root.toString(), "status", "--porcelain", "--untracked-files=normal")
                    .redirectErrorStream(true).start();
        } catch (IOException ex) {
            // Git is not installed: backups still protect changed files.
            return;
        }
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                // Not a Git repository or git status unavailable.
                return;
            }
            if (!output.isBlank() && !allowDirty) {
                throw new AnalysisException("El repositorio Git tiene cambios sin confirmar. Haz commit/stash antes de --apply, "
                        + "o usa --allow-dirty=true si aceptas el riesgo.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("Se interrumpió la verificación del estado Git.", ex);
        } catch (IOException ex) {
            throw new AnalysisException("No se pudo consultar el estado Git: " + ex.getMessage(), ex);
        }
    }
}
