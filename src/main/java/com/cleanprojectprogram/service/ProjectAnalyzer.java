package com.cleanprojectprogram.service;

import com.cleanprojectprogram.analysis.AnalysisException;
import com.cleanprojectprogram.analysis.AnalysisOutcome;
import com.cleanprojectprogram.analysis.AnalysisReport;
import com.cleanprojectprogram.analysis.Finding;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative source analyzer. It uses JavaParser's AST rather than regular expressions
 * to parse Java. Uncertain usages intentionally suppress findings rather than risk deletion.
 */
@SuppressWarnings("unchecked")
@Service
public class ProjectAnalyzer {
    private static final Set<String> EXCLUDED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "target", "out",
            "node_modules", ".cleanprojectprogram-backup"
    );
    private static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
            ".xml", ".yml", ".yaml", ".properties", ".json", ".json5", ".conf",
            ".cfg", ".txt", ".md", ".gradle", ".kts", ".sql", ".html", ".htm",
            ".jsp", ".jspx", ".ftl", ".vm", ".mustache", ".graphql", ".proto",
            ".toml", ".ini", ".csv", ".sh", ".bat", ".ps1"
    );
    private static final long MAX_TEXT_RESOURCE_BYTES = 2 * 1024 * 1024;

    private static final Set<String> FRAMEWORK_MANAGED_ANNOTATIONS = Set.of(
            "SpringBootApplication", "RestController", "Controller", "Service", "Repository",
            "Component", "Configuration", "ControllerAdvice", "RestControllerAdvice",
            "ConfigurationProperties", "FeignClient", "Aspect", "Entity", "Embeddable",
            "MappedSuperclass", "Document", "JsonComponent", "WebMvcTest", "SpringBootTest",
            "EnableScheduling", "EnableConfigurationProperties", "EntityListeners"
    );
    private static final Set<String> FRAMEWORK_FIELD_ANNOTATIONS = Set.of(
            "Data", "Getter", "Setter", "Value", "Builder", "SuperBuilder", "Jacksonized",
            "Entity", "Embeddable", "MappedSuperclass", "ConfigurationProperties", "JsonAutoDetect"
    );
    private static final Set<String> COMMON_LIFECYCLE_METHOD_NAMES = Set.of(
            "main", "finalize", "readObject", "writeObject", "readResolve", "writeReplace",
            "readExternal", "writeExternal", "clone", "close", "destroy", "afterPropertiesSet",
            "setUp", "tearDown", "beforeEach", "afterEach", "beforeAll", "afterAll"
    );

    public AnalysisOutcome analyze(Path requestedRoot) {
        Path root = validateRoot(requestedRoot);
        List<String> warnings = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<AnalysisOutcome.ProposedEdit> edits = new ArrayList<>();
        List<Path> allFiles = listFiles(root, warnings);

        List<Path> javaPaths = new ArrayList<>();
        int generatedSkipped = countGeneratedJavaFiles(root, warnings);
        for (Path file : allFiles) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            if (isGeneratedPath(root.relativize(file))) {
                generatedSkipped++;
                continue;
            }
            javaPaths.add(file);
        }

        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setAttributeComments(true);
        JavaParser parser = new JavaParser(configuration);
        List<SourceUnit> units = new ArrayList<>();
        List<TypeSymbol> types = new ArrayList<>();
        int parseErrors = 0;

        for (Path javaFile : javaPaths) {
            String source;
            try {
                source = Files.readString(javaFile, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                parseErrors++;
                warnings.add("No se pudo leer " + relative(root, javaFile) + ": " + ex.getMessage());
                continue;
            }

            ParseResult<CompilationUnit> parseResult;
            try {
                parseResult = parser.parse(javaFile);
            } catch (Exception ex) {
                parseErrors++;
                findings.add(parseErrorFinding(root, javaFile, ex.getMessage()));
                units.add(new SourceUnit(javaFile, source, null, isTestPath(root.relativize(javaFile)), false));
                continue;
            }

            CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
            SourceUnit unit = new SourceUnit(javaFile, source, compilationUnit, isTestPath(root.relativize(javaFile)), parseResult.isSuccessful());
            units.add(unit);
            if (compilationUnit == null || !parseResult.isSuccessful()) {
                parseErrors++;
                String problem = parseResult.getProblems().isEmpty()
                        ? "No se pudo construir el árbol sintáctico"
                        : parseResult.getProblems().get(0).getMessage();
                findings.add(parseErrorFinding(root, javaFile, problem));
                continue;
            }
            collectTypes(unit, root, types);
        }

        List<ResourceText> resourceTexts = loadTextResources(root, allFiles, warnings);
        boolean incomplete = parseErrors > 0;
        if (incomplete) {
            warnings.add("El análisis está incompleto. Por seguridad no se autoriza ninguna eliminación automática mientras haya archivos Java que no se pudieron analizar.");
        }

        analyzeTypes(root, units, types, resourceTexts, incomplete, findings, edits);
        analyzeMethods(root, units, resourceTexts, incomplete, findings, edits);
        analyzeFields(root, units, resourceTexts, incomplete, findings, edits);
        analyzeLocalsAndParameters(root, units, findings);
        analyzeImports(root, units, findings);
        analyzeReachability(root, units, types, resourceTexts, incomplete, findings);

        findings.sort(Comparator.comparing(Finding::file)
                .thenComparingInt(Finding::line)
                .thenComparing(Finding::kind)
                .thenComparing(Finding::symbol));


        // Do not allow two edits to overlap; deleting a file subsumes member findings and edits within that file.
        Set<Path> deletedFiles = new HashSet<>();
        for (AnalysisOutcome.ProposedEdit edit : edits) {
            if (edit.kind().equals("DELETE_FILE")) {
                deletedFiles.add(edit.file());
            }
        }
        Set<String> deletedRelativeFiles = deletedFiles.stream().map(path -> relative(root, path)).collect(java.util.stream.Collectors.toSet());
        findings.removeIf(finding -> deletedRelativeFiles.contains(finding.file())
                && !finding.kind().equals("UNREFERENCED_TYPE"));
        edits.removeIf(edit -> !edit.kind().equals("DELETE_FILE") && deletedFiles.contains(edit.file()));
        edits.sort(Comparator.comparing((AnalysisOutcome.ProposedEdit edit) -> edit.file().toString())
                .thenComparing(AnalysisOutcome.ProposedEdit::startOffset).reversed());

        int autoCount = (int) findings.stream().filter(Finding::autoRemovable).count();
        int reviewCount = findings.size() - autoCount;
        AnalysisReport report = new AnalysisReport(
                root.toString(), Instant.now(), javaPaths.size(),
                (int) units.stream().filter(unit -> unit.parseSuccessful).count(),
                generatedSkipped, parseErrors, findings.size(), autoCount, reviewCount,
                findings, warnings
        );
        return new AnalysisOutcome(report, edits);
    }

    private Path validateRoot(Path requestedRoot) {
        if (requestedRoot == null) {
            throw new AnalysisException("Debes indicar la ruta del proyecto a analizar.");
        }
        Path root = requestedRoot.toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new AnalysisException("La ruta no existe o no es un directorio: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new AnalysisException("No hay permisos de lectura en: " + root);
        }
        return root;
    }

    private List<Path> listFiles(Path root, List<String> warnings) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !hasExcludedSegment(root.relativize(path)))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new AnalysisException("No se pudo recorrer el proyecto: " + ex.getMessage(), ex);
        }
    }

    private int countGeneratedJavaFiles(Path root, List<String> warnings) {
        int count = 0;
        for (Path generatedRoot : List.of(
                root.resolve("build/generated"),
                root.resolve("build/generated-sources"),
                root.resolve("target/generated-sources"),
                root.resolve("target/generated"))) {
            if (!Files.isDirectory(generatedRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(generatedRoot)) {
                count += (int) stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java")).count();
            } catch (IOException ex) {
                warnings.add("No se pudieron contar las fuentes generadas en " + generatedRoot + ": " + ex.getMessage());
            }
        }
        return count;
    }

    private boolean hasExcludedSegment(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if (EXCLUDED_DIRECTORY_NAMES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGeneratedPath(Path relative) {
        String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/generated/")
                || normalized.contains("/generated-sources/")
                || normalized.startsWith("generated/")
                || normalized.startsWith("generated-sources/");
    }

    private boolean isTestPath(Path relative) {
        String normalized = "/" + relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT) + "/";
        return normalized.contains("/src/test/")
                || normalized.contains("/src/integrationtest/")
                || normalized.contains("/src/testfixtures/")
                || normalized.contains("/tests/");
    }

    private void collectTypes(SourceUnit unit, Path root, List<TypeSymbol> target) {
        CompilationUnit cu = unit.compilationUnit;
        if (cu == null) {
            return;
        }
        String packageName = cu.getPackageDeclaration().map(decl -> decl.getNameAsString()).orElse("");
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String simpleName = type.getNameAsString();
            List<String> typeNames = new ArrayList<>();
            TypeDeclaration<?> current = type;
            while (current != null) {
                typeNames.add(0, current.getNameAsString());
                current = current.findAncestor(TypeDeclaration.class).orElse(null);
            }
            String typePath = String.join(".", typeNames);
            String fqcn = packageName.isBlank() ? typePath : packageName + "." + typePath;
            boolean mapper = hasAnnotation(type, "Mapper")
                    && type instanceof ClassOrInterfaceDeclaration classOrInterface
                    && classOrInterface.isInterface();
            target.add(new TypeSymbol(unit, type, simpleName, fqcn, packageName,
                    hasModifier(type, Modifier.Keyword.PUBLIC), unit.testSource,
                    mapper, annotationNames(type)));
        }
    }

    private void analyzeTypes(Path root, List<SourceUnit> units, List<TypeSymbol> types,
                              List<ResourceText> resources, boolean incomplete,
                              List<Finding> findings, List<AnalysisOutcome.ProposedEdit> edits) {
        for (TypeSymbol symbol : types) {
            if (!symbol.topLevel()) {
                continue;
            }
            if (symbol.testSource || symbol.unit.compilationUnit == null) {
                continue;
            }
            boolean mapper = symbol.mapper;
            if (isFrameworkManaged(symbol.annotations) && !mapper) {
                continue;
            }
            if (isReferencedType(symbol, units, resources)) {
                continue;
            }

            String relPath = relative(root, symbol.unit.path);
            int line = beginLine(symbol.declaration).orElse(1);
            boolean onlyTopLevelType = symbol.unit.compilationUnit.getTypes().size() == 1;
            boolean packagePrivate = !symbol.isPublic && !hasModifier(symbol.declaration, Modifier.Keyword.PROTECTED)
                    && !hasModifier(symbol.declaration, Modifier.Keyword.PRIVATE);
            boolean hasAnnotations = !symbol.declaration.getAnnotations().isEmpty();
            boolean hasMain = symbol.declaration.findAll(MethodDeclaration.class).stream()
                    .anyMatch(method -> method.getNameAsString().equals("main") && method.isStatic());
            boolean frameworkLikeAnnotation = hasAnnotations && !mapper;
            boolean auto = !incomplete && !mapper && packagePrivate && onlyTopLevelType
                    && !frameworkLikeAnnotation && !hasMain
                    && hasSafeLineRange(symbol.declaration, symbol.unit.source);

            String kind = mapper ? "UNREFERENCED_MAPPER_INTERFACE" : "UNREFERENCED_TYPE";
            String severity = auto ? "LOW_RISK_CANDIDATE" : "REVIEW_REQUIRED";
            List<String> evidence = new ArrayList<>();
            evidence.add("No se encontraron referencias externas reconocibles en los archivos Java analizados.");
            evidence.add("Se revisaron recursos de configuración/texto compatibles con la búsqueda de nombres.");
            if (mapper) {
                evidence.add("Las interfaces @Mapper pueden activarse o localizarse por configuración; no se eliminan automáticamente.");
            }
            if (symbol.isPublic) {
                evidence.add("La declaración es pública; podría formar parte de una API usada fuera del repositorio.");
            }
            if (hasAnnotations && !mapper) {
                evidence.add("La clase tiene anotaciones; se evita asumir que su uso depende de llamadas Java directas.");
            }
            if (!onlyTopLevelType) {
                evidence.add("El archivo contiene varias declaraciones top-level; no se elimina el archivo completo.");
            }
            if (incomplete) {
                evidence.add("Hay errores de análisis en el proyecto; la ausencia de referencias no es concluyente.");
            }
            String explanation = mapper
                    ? "La interfaz mapper no muestra referencias externas detectables. Revisar inyección, configuración MapStruct y generación de código antes de retirarla."
                    : auto
                    ? "Tipo top-level sin referencias detectables, package-private, sin anotaciones y único en el archivo. Se puede retirar únicamente con --apply y copia de seguridad."
                    : "Tipo sin referencias detectables, pero no cumple todas las condiciones conservadoras para eliminarlo automáticamente.";
            String id = findingId(kind, relPath, symbol.fqcn, line);
            findings.add(new Finding(id, kind, severity, symbol.fqcn, relPath, line, auto, explanation, evidence));
            if (auto) {
                edits.add(new AnalysisOutcome.ProposedEdit(id, "DELETE_FILE", symbol.unit.path, 0,
                        symbol.unit.source.length(), symbol.fqcn));
            }
        }
    }

    private boolean isReferencedType(TypeSymbol target, List<SourceUnit> units, List<ResourceText> resources) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                if (containsWord(unit.source, target.simpleName) || containsWord(unit.source, target.fqcn)) {
                    // A parse error makes this a conservative reference, even though parsing already suppresses deletion.
                    return true;
                }
                continue;
            }
            for (var simpleName : unit.compilationUnit.findAll(com.github.javaparser.ast.expr.SimpleName.class)) {
                if (!simpleName.asString().equals(target.simpleName)) {
                    continue;
                }
                if (isDescendantOf(simpleName, target.declaration)) {
                    continue;
                }
                // Duplicate simple names are intentionally treated as a possible reference to each candidate.
                return true;
            }
            for (StringLiteralExpr literal : unit.compilationUnit.findAll(StringLiteralExpr.class)) {
                if (isDescendantOf(literal, target.declaration)) {
                    continue;
                }
                String value = literal.asString();
                if (containsWord(value, target.simpleName) || (!target.fqcn.isBlank() && value.contains(target.fqcn))) {
                    return true;
                }
            }
        }
        for (ResourceText resource : resources) {
            if (containsWord(resource.content, target.simpleName) || resource.content.contains(target.fqcn)) {
                return true;
            }
        }
        return false;
    }

    private void analyzeMethods(Path root, List<SourceUnit> units, List<ResourceText> resources,
                                boolean incomplete, List<Finding> findings,
                                List<AnalysisOutcome.ProposedEdit> edits) {
        List<MethodDeclaration> allMethods = units.stream()
                .filter(unit -> unit.compilationUnit != null)
                .flatMap(unit -> unit.compilationUnit.findAll(MethodDeclaration.class).stream())
                .toList();

        for (MethodDeclaration method : allMethods) {
            SourceUnit unit = findUnit(units, method).orElse(null);
            if (unit == null || unit.testSource || method.getBody().isEmpty()) {
                continue;
            }
            String name = method.getNameAsString();
            TypeDeclaration<?> owner = method.findAncestor(TypeDeclaration.class).orElse(null);
            if (owner == null || hasAnyAnnotations(method)) {
                continue;
            }
            List<MethodDeclaration> sameName = owner.getMembers().stream()
                    .filter(member -> member instanceof MethodDeclaration)
                    .map(member -> (MethodDeclaration) member)
                    .filter(candidate -> candidate.getNameAsString().equals(name))
                    .toList();
            if (sameName.size() > 1) {
                continue; // Overloads are deliberately not classified automatically.
            }
            if (COMMON_LIFECYCLE_METHOD_NAMES.contains(name)) {
                continue;
            }
            boolean referenced = isMethodReferenced(method, name, units, resources);
            if (referenced) {
                continue;
            }
            String relPath = relative(root, unit.path);
            int line = beginLine(method).orElse(1);
            boolean privateMethod = hasModifier(method, Modifier.Keyword.PRIVATE);
            boolean auto = privateMethod && !incomplete && hasSafeLineRange(method, unit.source);
            String kind = privateMethod ? "UNUSED_PRIVATE_METHOD" : "UNREFERENCED_METHOD_REVIEW";
            List<String> evidence = new ArrayList<>();
            evidence.add("No se encontraron llamadas ni referencias ::method reconocibles fuera de la declaración.");
            if (privateMethod) {
                evidence.add("Solo se consideran métodos privados sin anotaciones; los constructores nunca se proponen para eliminación.");
            } else {
                evidence.add("Un método no privado puede ser consumido por otro módulo o cliente externo; no se elimina automáticamente.");
            }
            if (incomplete) {
                evidence.add("El proyecto tiene errores de análisis, por lo que el resultado no puede considerarse completo.");
            }
            String id = findingId(kind, relPath, owner.getNameAsString() + "#" + name, line);
            findings.add(new Finding(id, kind, auto ? "LOW_RISK_CANDIDATE" : "REVIEW_REQUIRED",
                    owner.getNameAsString() + "#" + name + "()", relPath, line, auto,
                    auto
                            ? "Método privado, sin anotaciones, no sobrecargado y sin referencias externas detectables."
                            : "No se detectaron referencias directas; requiere revisión manual antes de retirarlo.",
                    evidence));
            if (auto) {
                safeLineEdit(unit, method).ifPresent(range -> edits.add(new AnalysisOutcome.ProposedEdit(
                        id, "DELETE_RANGE", unit.path, range[0], range[1], owner.getNameAsString() + "#" + name)));
            }
        }
    }

    private boolean isMethodReferenced(MethodDeclaration target, String name, List<SourceUnit> units,
                                      List<ResourceText> resources) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                if (containsWord(unit.source, name)) {
                    return true;
                }
                continue;
            }
            for (MethodCallExpr call : unit.compilationUnit.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals(name) && !isDescendantOf(call, target)) {
                    return true;
                }
            }
            for (MethodReferenceExpr ref : unit.compilationUnit.findAll(MethodReferenceExpr.class)) {
                if (ref.getIdentifier().equals(name) && !isDescendantOf(ref, target)) {
                    return true;
                }
            }
            for (StringLiteralExpr literal : unit.compilationUnit.findAll(StringLiteralExpr.class)) {
                if (!isDescendantOf(literal, target) && containsWord(literal.asString(), name)) {
                    return true;
                }
            }
        }
        return resources.stream().anyMatch(resource -> containsWord(resource.content, name));
    }

    private void analyzeFields(Path root, List<SourceUnit> units, List<ResourceText> resources,
                               boolean incomplete, List<Finding> findings,
                               List<AnalysisOutcome.ProposedEdit> edits) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null || unit.testSource) {
                continue;
            }
            for (FieldDeclaration field : unit.compilationUnit.findAll(FieldDeclaration.class)) {
                if (hasAnyAnnotations(field)) {
                    continue;
                }
                boolean isPrivate = hasModifier(field, Modifier.Keyword.PRIVATE);
                boolean isStaticFinal = hasModifier(field, Modifier.Keyword.STATIC)
                        && hasModifier(field, Modifier.Keyword.FINAL);
                if (!isPrivate && !isStaticFinal) {
                    continue;
                }
                TypeDeclaration<?> owner = field.findAncestor(TypeDeclaration.class).orElse(null);
                if (owner == null || hasAnyAnnotation(owner, FRAMEWORK_FIELD_ANNOTATIONS)) {
                    continue;
                }
                // Multiple declarators make precise removal risky.
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    FieldUse fieldUse = findFieldUses(name, field, units);
                    boolean textual = hasExternalStringOrResourceMention(name, field, units, resources);
                    if (textual) {
                        continue;
                    }
                    if (fieldUse.reads > 0) {
                        continue;
                    }
                    String kind;
                    String explanation;
                    boolean auto = false;
                    if (isStaticFinal) {
                        if (fieldUse.writes > 0 || "serialVersionUID".equals(name)) {
                            continue;
                        }
                        kind = "UNUSED_CONSTANT";
                        explanation = "Constante static final sin referencias reconocibles en el proyecto.";
                        auto = !incomplete && field.getVariables().size() == 1
                                && hasSafeLineRange(field, unit.source);
                    } else if (fieldUse.writes > 0) {
                        kind = "WRITE_ONLY_PRIVATE_FIELD";
                        explanation = "El atributo privado solo aparece como destino de escritura; revisar asignaciones y posible uso por reflexión antes de retirarlo.";
                    } else {
                        kind = "UNUSED_PRIVATE_FIELD";
                        explanation = "No se detectaron lecturas ni escrituras reconocibles del atributo privado.";
                        auto = !incomplete && field.getVariables().size() == 1
                                && variable.getInitializer().isEmpty()
                                && hasSafeLineRange(field, unit.source);
                    }
                    String relPath = relative(root, unit.path);
                    int line = beginLine(variable).orElse(beginLine(field).orElse(1));
                    String id = findingId(kind, relPath, owner.getNameAsString() + "#" + name, line);
                    List<String> evidence = new ArrayList<>();
                    evidence.add(fieldUse.reads + " lectura(s) y " + fieldUse.writes + " escritura(s) explícita(s) reconocibles.");
                    if (field.getVariables().size() > 1) {
                        evidence.add("La declaración comparte línea/declaración con otros atributos; no se retira automáticamente.");
                    }
                    if (variable.getInitializer().isPresent()) {
                        evidence.add("Tiene inicializador; no se elimina automáticamente para preservar posibles efectos secundarios.");
                    }
                    if (incomplete) {
                        evidence.add("El análisis global está incompleto por errores de sintaxis/lectura.");
                    }
                    findings.add(new Finding(id, kind, auto ? "LOW_RISK_CANDIDATE" : "REVIEW_REQUIRED",
                            owner.getNameAsString() + "#" + name, relPath, line, auto, explanation, evidence));
                    if (auto) {
                        safeLineEdit(unit, field).ifPresent(range -> edits.add(new AnalysisOutcome.ProposedEdit(
                                id, "DELETE_RANGE", unit.path, range[0], range[1], owner.getNameAsString() + "#" + name)));
                    }
                }
            }
        }
    }

    private FieldUse findFieldUses(String name, FieldDeclaration declaration, List<SourceUnit> units) {
        int reads = 0;
        int writes = 0;
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                continue;
            }
            for (NameExpr expr : unit.compilationUnit.findAll(NameExpr.class)) {
                if (!expr.getNameAsString().equals(name) || isDescendantOf(expr, declaration)) {
                    continue;
                }
                if (isWriteTarget(expr)) {
                    writes++;
                } else {
                    reads++;
                }
            }
            for (FieldAccessExpr expr : unit.compilationUnit.findAll(FieldAccessExpr.class)) {
                if (!expr.getNameAsString().equals(name) || isDescendantOf(expr, declaration)) {
                    continue;
                }
                if (isWriteTarget(expr)) {
                    writes++;
                } else {
                    reads++;
                }
            }
            // Method references such as field::method use the field as their scope. JavaParser
            // may represent that scope as a TypeExpr rather than a NameExpr, so it would not be
            // counted by the loops above. Treat any method reference whose scope text matches
            // the field name as a read to avoid deleting fields that are only used this way.
            for (MethodReferenceExpr expr : unit.compilationUnit.findAll(MethodReferenceExpr.class)) {
                if (isDescendantOf(expr, declaration)) {
                    continue;
                }
                String scopeText = expr.getScope().toString();
                if (scopeText.equals(name)) {
                    reads++;
                }
            }
        }
        return new FieldUse(reads, writes);
    }

    private boolean isWriteTarget(Node expression) {
        Optional<AssignExpr> assign = expression.getParentNode().filter(AssignExpr.class::isInstance).map(AssignExpr.class::cast);
        return assign.map(value -> value.getTarget() == expression).orElse(false);
    }

    private boolean hasExternalStringOrResourceMention(String name, FieldDeclaration field,
                                                        List<SourceUnit> units, List<ResourceText> resources) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                if (containsWord(unit.source, name)) {
                    return true;
                }
                continue;
            }
            for (StringLiteralExpr literal : unit.compilationUnit.findAll(StringLiteralExpr.class)) {
                if (!isDescendantOf(literal, field) && containsWord(literal.asString(), name)) {
                    return true;
                }
            }
        }
        return resources.stream().anyMatch(resource -> containsWord(resource.content, name));
    }

    private void analyzeLocalsAndParameters(Path root, List<SourceUnit> units, List<Finding> findings) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null || unit.testSource) {
                continue;
            }
            for (VariableDeclarator variable : unit.compilationUnit.findAll(VariableDeclarator.class)) {
                if (variable.findAncestor(FieldDeclaration.class).isPresent()) {
                    continue;
                }
                Node scope = variable.findAncestor(CallableDeclaration.class)
                        .map(Node.class::cast)
                        .orElseGet(() -> variable.findAncestor(LambdaExpr.class).map(Node.class::cast).orElse(unit.compilationUnit));
                String variableName = variable.getNameAsString();
                boolean used = scope.findAll(NameExpr.class).stream()
                        .anyMatch(expr -> expr.getNameAsString().equals(variableName)
                                && !isDescendantOf(expr, variable))
                        || scope.findAll(MethodReferenceExpr.class).stream()
                        .anyMatch(expr -> expr.getScope().toString().equals(variableName)
                                && !isDescendantOf(expr, variable));
                if (used) {
                    continue;
                }
                String relPath = relative(root, unit.path);
                int line = beginLine(variable).orElse(1);
                String kind = "UNUSED_LOCAL_VARIABLE";
                String id = findingId(kind, relPath, variable.getNameAsString(), line);
                List<String> evidence = new ArrayList<>();
                evidence.add("No se encontró otra expresión NameExpr con el mismo nombre dentro del método/constructor/lambda contenedor.");
                if (variable.getInitializer().isPresent()) {
                    evidence.add("La variable tiene inicializador; eliminarlo podría cambiar efectos secundarios, por eso este hallazgo nunca se aplica automáticamente.");
                }
                findings.add(new Finding(id, kind, "REVIEW_REQUIRED", variable.getNameAsString(), relPath, line, false,
                        "Variable local declarada sin referencias reconocibles. Verificar inicializador y ámbito antes de editar.", evidence));
            }

            for (MethodDeclaration method : unit.compilationUnit.findAll(MethodDeclaration.class)) {
                if (!method.isPrivate() || hasAnyAnnotations(method) || method.getBody().isEmpty()) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    String name = parameter.getNameAsString();
                    boolean used = method.getBody().get().findAll(NameExpr.class).stream()
                            .anyMatch(expr -> expr.getNameAsString().equals(name))
                            || method.getBody().get().findAll(MethodReferenceExpr.class).stream()
                            .anyMatch(expr -> expr.getScope().toString().equals(name));
                    if (used) {
                        continue;
                    }
                    String relPath = relative(root, unit.path);
                    int line = beginLine(parameter).orElse(beginLine(method).orElse(1));
                    String kind = "UNUSED_PRIVATE_METHOD_PARAMETER";
                    findings.add(new Finding(findingId(kind, relPath, method.getNameAsString() + ":" + name, line),
                            kind, "REVIEW_REQUIRED", method.getNameAsString() + "(" + name + ")", relPath, line, false,
                            "Parámetro sin referencias reconocibles en el cuerpo de un método privado. No se modifica la firma automáticamente.",
                            List.of("El parámetro podría formar parte de una intención explícita o interactuar con herramientas externas.")));
                }
            }
        }
    }

    private boolean hasExternalStringOrResourceMention(String name, Node excludedNode,
                                                        List<SourceUnit> units, List<ResourceText> resources) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                if (containsWord(unit.source, name)) {
                    return true;
                }
                continue;
            }
            for (StringLiteralExpr literal : unit.compilationUnit.findAll(StringLiteralExpr.class)) {
                if (!isDescendantOf(literal, excludedNode) && containsWord(literal.asString(), name)) {
                    return true;
                }
            }
        }
        return resources.stream().anyMatch(resource -> containsWord(resource.content, name));
    }

    private void analyzeImports(Path root, List<SourceUnit> units, List<Finding> findings) {
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null || unit.testSource) {
                continue;
            }
            Set<String> usedNames = new HashSet<>();
            for (SimpleName simpleName : unit.compilationUnit.findAll(SimpleName.class)) {
                usedNames.add(simpleName.asString());
            }
            for (AnnotationExpr annotation : unit.compilationUnit.findAll(AnnotationExpr.class)) {
                usedNames.add(annotation.getNameAsString());
            }
            for (StringLiteralExpr literal : unit.compilationUnit.findAll(StringLiteralExpr.class)) {
                String value = literal.asString();
                for (String word : value.split("[^A-Za-z0-9_$]+")) {
                    if (!word.isBlank()) {
                        usedNames.add(word);
                    }
                }
            }
            for (com.github.javaparser.ast.ImportDeclaration importDecl : unit.compilationUnit.getImports()) {
                if (importDecl.isAsterisk() || importDecl.isStatic()) {
                    continue;
                }
                String importedName = importDecl.getNameAsString();
                String simpleName = importedName.substring(importedName.lastIndexOf('.') + 1);
                if (usedNames.contains(simpleName)) {
                    continue;
                }
                String relPath = relative(root, unit.path);
                int line = importDecl.getRange().map(r -> r.begin.line).orElse(1);
                String id = findingId("UNUSED_IMPORT", relPath, importedName, line);
                findings.add(new Finding(id, "UNUSED_IMPORT", "REVIEW_REQUIRED", importedName,
                        relPath, line, false,
                        "El import no tiene referencias reconocibles en el archivo. Puede ser código muerto o usarse por reflexión.",
                        List.of("No se encontró ningún SimpleName ni literal que coincida con el nombre importado.")));
            }
        }
    }

    private void analyzeReachability(Path root, List<SourceUnit> units, List<TypeSymbol> types,
                                     List<ResourceText> resources, boolean incomplete,
                                     List<Finding> findings) {
        // Build reference graph: type FQCN -> set of referenced type FQCNs
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, TypeSymbol> fqcnToType = new HashMap<>();
        for (TypeSymbol type : types) {
            fqcnToType.put(type.fqcn, type);
            graph.putIfAbsent(type.fqcn, new HashSet<>());
        }
        for (SourceUnit unit : units) {
            if (unit.compilationUnit == null) {
                continue;
            }
            for (TypeSymbol type : types) {
                if (type.unit != unit) {
                    continue;
                }
                for (SimpleName simpleName : unit.compilationUnit.findAll(SimpleName.class)) {
                    String name = simpleName.asString();
                    for (TypeSymbol candidate : types) {
                        if (candidate != type && candidate.simpleName.equals(name)) {
                            graph.get(type.fqcn).add(candidate.fqcn);
                        }
                    }
                }
            }
        }
        // Entry points: framework-managed types, types with main(), mappers
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (TypeSymbol type : types) {
            if (type.testSource) {
                continue;
            }
            boolean isEntryPoint = type.mapper
                    || isFrameworkManaged(type.annotations)
                    || type.declaration.findAll(MethodDeclaration.class).stream()
                            .anyMatch(m -> m.getNameAsString().equals("main") && m.isStatic());
            if (isEntryPoint) {
                reachable.add(type.fqcn);
                queue.add(type.fqcn);
            }
        }
        // BFS
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String neighbor : graph.getOrDefault(current, Set.of())) {
                if (reachable.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        // Report unreachable types
        for (TypeSymbol type : types) {
            if (type.testSource || !type.topLevel()) {
                continue;
            }
            if (reachable.contains(type.fqcn)) {
                continue;
            }
            if (isFrameworkManaged(type.annotations) || type.mapper) {
                continue;
            }
            String relPath = relative(root, type.unit.path);
            int line = beginLine(type.declaration).orElse(1);
            String kind = "UNREACHABLE_TYPE";
            String id = findingId(kind, relPath, type.fqcn, line);
            List<String> evidence = new ArrayList<>();
            evidence.add("No es alcanzable desde endpoints REST, métodos main() ni otros tipos alcanzables.");
            evidence.add("Puede ser usado por reflexión, configuración externa o frameworks; revisar antes de eliminar.");
            if (incomplete) {
                evidence.add("El análisis global está incompleto por errores de sintaxis/lectura.");
            }
            findings.add(new Finding(id, kind, "REVIEW_REQUIRED", type.fqcn, relPath, line, false,
                    "Tipo top-level no alcanzable desde puntos de entrada conocidos. Requiere revisión manual.",
                    evidence));
        }
    }

    private List<ResourceText> loadTextResources(Path root, List<Path> allFiles, List<String> warnings) {
        List<ResourceText> result = new ArrayList<>();
        for (Path file : allFiles) {
            if (file.toString().endsWith(".java")) {
                continue;
            }
            String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = filename.lastIndexOf('.');
            if (dot < 0 || !TEXT_FILE_EXTENSIONS.contains(filename.substring(dot))) {
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > MAX_TEXT_RESOURCE_BYTES) {
                    warnings.add("Recurso de texto omitido por tamaño (>2 MB): " + relative(root, file));
                    continue;
                }
                result.add(new ResourceText(file, Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException ex) {
                warnings.add("No se pudo leer el recurso " + relative(root, file) + ": " + ex.getMessage());
            }
        }
        return result;
    }

    private Optional<SourceUnit> findUnit(List<SourceUnit> units, Node node) {
        return units.stream().filter(unit -> unit.compilationUnit != null
                && unit.compilationUnit == node.findCompilationUnit().orElse(null)).findFirst();
    }

    private Finding parseErrorFinding(Path root, Path file, String problem) {
        String relative = relative(root, file);
        return new Finding(findingId("PARSE_ERROR", relative, "archivo Java", 1), "PARSE_ERROR", "WARNING",
                "No analizado", relative, 1, false,
                "No se pudo analizar este archivo Java. Sus referencias podrían estar ausentes del grafo de usos.",
                List.of(problem == null ? "Error de análisis desconocido" : problem));
    }

    private boolean hasAnyAnnotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations() != null && !node.getAnnotations().isEmpty();
    }

    private boolean hasAnyAnnotation(NodeWithAnnotations<?> node, Set<String> names) {
        return annotationNames(node).stream().anyMatch(names::contains);
    }

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return annotationNames(node).contains(name);
    }

    private Set<String> annotationNames(NodeWithAnnotations<?> node) {
        Set<String> names = new LinkedHashSet<>();
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = annotation.getNameAsString();
            int dot = name.lastIndexOf('.');
            names.add(dot >= 0 ? name.substring(dot + 1) : name);
        }
        return names;
    }

    private boolean isFrameworkManaged(Set<String> annotations) {
        return annotations.stream().anyMatch(FRAMEWORK_MANAGED_ANNOTATIONS::contains);
    }

    private boolean hasModifier(NodeWithModifiers<?> node, Modifier.Keyword keyword) {
        return node.getModifiers().stream().anyMatch(modifier -> modifier.getKeyword() == keyword);
    }

    private boolean isDescendantOf(Node node, Node ancestor) {
        Node current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(word) + "(?![A-Za-z0-9_$])")
                .matcher(text).find();
    }

    private Optional<Integer> beginLine(Node node) {
        return node.getRange().map(range -> range.begin.line);
    }

    /** Returns [line-start offset, after-line-ending offset] only when the entire AST node occupies whole lines. */
    private Optional<int[]> safeLineEdit(SourceUnit unit, Node node) {
        Optional<Range> maybeRange = node.getRange();
        if (maybeRange.isEmpty()) {
            return Optional.empty();
        }
        Range range = maybeRange.get();
        int start = offsetFor(unit.source, range.begin);
        int endExclusive = offsetFor(unit.source, range.end) + 1;
        if (start < 0 || endExclusive > unit.source.length() || endExclusive <= start) {
            return Optional.empty();
        }
        int lineStart = unit.source.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int lineEnd = unit.source.indexOf('\n', endExclusive);
        if (lineEnd < 0) {
            lineEnd = unit.source.length();
        }
        if (!unit.source.substring(lineStart, start).isBlank()
                || !unit.source.substring(endExclusive, lineEnd).isBlank()) {
            return Optional.empty();
        }
        int removeEnd = lineEnd < unit.source.length() ? lineEnd + 1 : lineEnd;
        return Optional.of(new int[]{lineStart, removeEnd});
    }

    private boolean hasSafeLineRange(Node node, String source) {
        SourceUnit temp = new SourceUnit(Path.of("."), source, null, false, true);
        return safeLineEdit(temp, node).isPresent();
    }

    private int offsetFor(String source, Position position) {
        int line = 1;
        int offset = 0;
        while (line < position.line && offset < source.length()) {
            int next = source.indexOf('\n', offset);
            if (next < 0) {
                return -1;
            }
            offset = next + 1;
            line++;
        }
        int value = offset + position.column - 1;
        return value <= source.length() ? value : -1;
    }

    private String relative(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return file.toString();
        }
    }

    private String findingId(String kind, String file, String symbol, int line) {
        return UUID.nameUUIDFromBytes((kind + "|" + file + "|" + symbol + "|" + line).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static final class SourceUnit {
        private final Path path;
        private final String source;
        private final CompilationUnit compilationUnit;
        private final boolean testSource;
        private final boolean parseSuccessful;

        private SourceUnit(Path path, String source, CompilationUnit compilationUnit, boolean testSource,
                           boolean parseSuccessful) {
            this.path = path;
            this.source = source;
            this.compilationUnit = compilationUnit;
            this.testSource = testSource;
            this.parseSuccessful = parseSuccessful;
        }
    }

    private static final class TypeSymbol {
        private final SourceUnit unit;
        private final TypeDeclaration<?> declaration;
        private final String simpleName;
        private final String fqcn;
        private final String packageName;
        private final boolean isPublic;
        private final boolean testSource;
        private final boolean mapper;
        private final Set<String> annotations;

        private TypeSymbol(SourceUnit unit, TypeDeclaration<?> declaration, String simpleName,
                           String fqcn, String packageName, boolean isPublic, boolean testSource,
                           boolean mapper, Set<String> annotations) {
            this.unit = unit;
            this.declaration = declaration;
            this.simpleName = simpleName;
            this.fqcn = fqcn;
            this.packageName = packageName;
            this.isPublic = isPublic;
            this.testSource = testSource;
            this.mapper = mapper;
            this.annotations = annotations;
        }

        private boolean topLevel() {
            return declaration.findAncestor(TypeDeclaration.class).isEmpty();
        }
    }

    private record ResourceText(Path path, String content) {
    }

    private record FieldUse(int reads, int writes) {
    }
}
