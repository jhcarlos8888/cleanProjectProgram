# cleanProjectProgram

Herramienta local para analizar proyectos backend Java y localizar código que posiblemente no se utiliza. Está construida con **Java 21**, **Spring Boot 4.1.1**, Gradle **8.14.5** y JavaParser **3.28.2**.

> **Principio de seguridad:** el modo normal solo analiza y genera un informe. La edición requiere solicitar expresamente `--apply=true`, los archivos originales se respaldan fuera del proyecto y el programa nunca elimina constructores. Ningún analizador estático puede demostrar ausencia absoluta de uso si el proyecto construye nombres dinámicamente, usa reflexión o depende de configuración externa no incluida.

## Requisitos

- JDK 21 instalado y disponible con `java -version`.
- Conexión a Internet la primera vez para descargar Gradle y las dependencias desde sus repositorios oficiales.
- En Linux/macOS: `curl`, `unzip`, `sha256sum` y `awk` para el lanzador `./gradlew`.

El lanzador `gradlew` descarga Gradle 8.14.5 en la caché del usuario si todavía no está presente y valida el ZIP con el SHA-256 publicado junto a la distribución oficial. En Windows se usa `gradlew.bat` y PowerShell.

## Compilar y ejecutar pruebas

Desde la carpeta `cleanProjectProgram`:

```bash
./gradlew clean test
```

En Windows:

```bat
gradlew.bat clean test
```

## Modo línea de comandos

Analizar un proyecto (sin cambiarlo):

```bash
./gradlew bootRun --args='--project-path="/ruta/absoluta/mi-backend" --report-file="/tmp/clean-project-report.json"'
```

Ejemplo Linux:

```bash
./gradlew bootRun --args='--project-path="/home/juan/proyectos/api-pedidos" --report-file="/tmp/api-pedidos-report.json"'
```

El resumen se muestra en la terminal y el informe JSON se guarda donde indique `--report-file`. Si no se proporciona, se escribe `cleanProjectProgram-report.json` en la carpeta del propio analizador (salvo que sea la misma ruta que el proyecto examinado).

### Aplicar cambios conservadores

Primero analiza, revisa el JSON y asegúrate de tener un respaldo o un commit. Luego puedes ejecutar:

```bash
./gradlew bootRun --args='--project-path="/ruta/absoluta/mi-backend" --apply=true --report-file="/tmp/clean-project-report.json"'
```

Si el proyecto es un repositorio Git y contiene archivos modificados o no rastreados, la operación se detiene. Después de revisar esos cambios y decidir que quieres asumir el riesgo, se puede indicar también `--allow-dirty=true`; no se recomienda hacerlo en un repositorio de trabajo compartido.

La copia de seguridad se crea como una carpeta hermana del proyecto con un nombre similar a `mi-backend.cleanProjectProgram-backup-20261018-153000`. Los cambios aplicados se vuelven a analizar y el informe guardado corresponde al análisis posterior a la limpieza.

### Qué puede retirar automáticamente

1. Métodos `private` sin anotaciones, sin sobrecargas con el mismo nombre y sin referencias reconocibles fuera de su propia declaración.
2. Atributos `private` sin referencias, sin inicializador, sin anotaciones y con una declaración aislada que puede quitarse sin reescribir otros atributos.
3. Constantes `static final` (públicas, protegidas o package-private) sin referencias reconocibles en el proyecto.
4. Tipos top-level de visibilidad de paquete, únicos en su archivo, sin anotaciones, sin `main` y sin referencias detectables en el código o los recursos compatibles.

Las reglas son deliberadamente estrictas. Si hay errores de lectura o sintaxis Java, **se cancelan todas las eliminaciones automáticas**.

## Modo API REST local

Inicia el servidor sin `--project-path`:

```bash
./gradlew bootRun
```

El servidor escucha en `127.0.0.1:8080`, no en todas las interfaces de red.

Comprobar estado:

```bash
curl http://127.0.0.1:8080/api/v1/health
```

Solicitar análisis:

```bash
curl -X POST http://127.0.0.1:8080/api/v1/analysis \
  -H 'Content-Type: application/json' \
  -d '{"projectPath":"/ruta/absoluta/mi-backend"}'
```

La API solo analiza: no expone un endpoint HTTP de borrado.

## Qué analiza y qué protege

- Métodos privados sin llamadas ni referencias reconocibles y métodos no privados sin uso detectado (los no privados son solo advertencias para revisión).
- Atributos privados no usados y atributos que solo parecen escribirse, pero nunca leerse.
- Constantes `static final` sin referencias reconocibles en el proyecto (públicas, protegidas o package-private).
- Variables locales sin referencias reconocibles y parámetros aparentemente no utilizados de métodos privados. Nunca se eliminan automáticamente.
- Imports sin referencias reconocibles en el archivo (no se eliminan automáticamente).
- Tipos top-level no alcanzables desde puntos de entrada conocidos (endpoints REST, métodos `main`, componentes Spring). Requiere revisión manual.
- Clases, interfaces, enums y records sin referencias detectables.
- Interfaces `@Mapper` sin referencias detectables, presentadas como revisión manual.
- Llamadas por referencia a método (`objeto::metodo`), cadenas que parecen reflejar nombres y recursos de configuración comunes (`.xml`, `.yml`, `.yaml`, `.properties`, `.json`, `.gradle`, `.kts`, etc.) como evidencia conservadora de uso.
- Las fuentes dentro de `build/`, `target/`, `out/` y carpetas de código generado se excluyen del análisis de candidatos. No se proponen implementaciones generadas por MapStruct para eliminación.
- Las fuentes de pruebas pueden aportar evidencia de uso, pero no se proponen como candidatas de limpieza.
- Los constructores (incluidos los vacíos, con todos los atributos, `this(...)` y `super(...)`) no se analizan para borrado y nunca se editan.
- Componentes de Spring, controladores, entidades JPA, clases configuradas por anotaciones conocidas y miembros anotados se protegen del borrado automático.

## Cómo interpretar el informe

Cada hallazgo incluye `kind`, `severity`, `file`, `line`, `autoRemovable`, explicación y evidencias. `autoRemovable: true` significa que el hallazgo pasó las reglas conservadoras locales, no que exista una prueba matemática de ausencia de uso. El modo por defecto no cambia archivos.

Algunos patrones dinámicos no se pueden resolver de forma fiable solo a partir de los archivos fuente: reflexión construida en tiempo de ejecución, llamadas desde scripts externos, configuraciones remotas, convenciones de frameworks propios, consumo por otros repositorios y metaprogramación. Revisa siempre los hallazgos, el diff y ejecuta compilación y pruebas del backend después de aplicar cambios.

## Estructura

```text
cleanProjectProgram/
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── gradle/wrapper/gradle-wrapper.properties
├── src/main/java/com/cleanprojectprogram/
│   ├── CleanProjectProgramApplication.java
│   ├── analysis/
│   ├── cli/CliRunner.java
│   ├── service/ProjectAnalyzer.java
│   ├── service/ProjectCleaner.java
│   └── web/
└── src/test/java/com/cleanprojectprogram/service/ProjectAnalyzerTest.java
```
