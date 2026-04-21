package com.network.monitoring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.monitoring.service.KpiCacheService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    private final KpiCacheService kpiCacheService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.python-service-path}")
    private String pythonServicePath;

    @Value("${app.python-venv-path:}")
    private String pythonVenvPath;

    @Value("${app.python-bin:}")
    private String pythonBin;

    @Value("${app.perf-logs-enabled:false}")
    private String perfLogsEnabledRaw;

    public RecommendationService(KpiCacheService kpiCacheService) {
        this.kpiCacheService = kpiCacheService;
    }

    public RecommendationResult runRecommendation(long fileId, Map<String, Object> body, boolean exportExcel) throws IOException {
        long startedAt = System.currentTimeMillis();
        ensurePythonEntry();

        Map<String, Object> cache = kpiCacheService.readKpiCache(fileId);
        if (cache == null) {
            throw new IllegalStateException("KPI cache not found for fileId " + fileId);
        }
        List<Map<String, Object>> rows = toCanonicalRows(cache);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No KPI rows found in cache for recommendation.");
        }

        Path workDir = Files.createTempDirectory("rca-run-");
        Path inputPath = workDir.resolve("input.xlsx");
        Path configPath = workDir.resolve("config.json");
        Path jsonOutPath = workDir.resolve("result.json");
        Path excelOutPath = workDir.resolve("result.xlsx");

        writeWorkbook(rows, inputPath);
        Map<String, Object> config = buildPythonConfig(body);
        Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config), StandardCharsets.UTF_8);

        List<String> args = new ArrayList<>();
        args.add(resolvePythonBin());
        args.add(getPythonMainPath().toString());
        args.add("run");
        args.add("--input");
        args.add(inputPath.toString());
        args.add("--config");
        args.add(configPath.toString());
        args.add("--json-out");
        args.add(jsonOutPath.toString());
        if (exportExcel) {
            args.add("--export-excel");
            args.add("--excel-out");
            args.add(excelOutPath.toString());
        }

        ProcessResult result = executeProcess(args, getWorkspaceRoot());
        if (result.exitCode != 0) {
            cleanup(workDir);
            throw new IllegalStateException(result.stderr.isBlank() ? result.stdout : result.stderr);
        }

        if (!Files.exists(jsonOutPath)) {
            cleanup(workDir);
            throw new IllegalStateException("Python RCA did not produce JSON output.");
        }

        Map<String, Object> payload = mapper.readValue(Files.readString(jsonOutPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        if (isTruthy(perfLogsEnabledRaw)) {
            logPerf("runRecommendation", fileId, rows.size(), System.currentTimeMillis() - startedAt);
        }

        return new RecommendationResult(payload, exportExcel ? excelOutPath : null, workDir);
    }

    public List<String> listPresets() throws IOException {
        Map<String, Object> presets = readPresetsFile();
        return new ArrayList<>(presets.keySet());
    }

    public void savePreset(String name, Map<String, Object> config) throws IOException {
        ensurePythonEntry();
        Path workDir = Files.createTempDirectory("rca-preset-");
        try {
            Path configPath = workDir.resolve("preset.json");
            Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config), StandardCharsets.UTF_8);
            List<String> args = List.of(
                    resolvePythonBin(),
                    getPythonMainPath().toString(),
                    "save-preset",
                    "--name",
                    name,
                    "--config",
                    configPath.toString()
            );
            ProcessResult result = executeProcess(args, getWorkspaceRoot());
            if (result.exitCode != 0) {
                throw new IllegalStateException(result.stderr.isBlank() ? result.stdout : result.stderr);
            }
        } finally {
            cleanup(workDir);
        }
    }

    public Map<String, Object> loadPreset(String name) throws IOException {
        ensurePythonEntry();
        Path workDir = Files.createTempDirectory("rca-preset-");
        try {
            Path jsonOutPath = workDir.resolve("preset.json");
            List<String> args = List.of(
                    resolvePythonBin(),
                    getPythonMainPath().toString(),
                    "load-preset",
                    "--name",
                    name,
                    "--json-out",
                    jsonOutPath.toString()
            );
            ProcessResult result = executeProcess(args, getWorkspaceRoot());
            if (result.exitCode != 0) {
                throw new IllegalStateException(result.stderr.isBlank() ? result.stdout : result.stderr);
            }
            if (!Files.exists(jsonOutPath)) {
                throw new IllegalStateException("Preset output was not generated.");
            }
            return mapper.readValue(Files.readString(jsonOutPath, StandardCharsets.UTF_8), new TypeReference<>() {});
        } finally {
            cleanup(workDir);
        }
    }

    private void writeWorkbook(List<Map<String, Object>> rows, Path outputPath) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Input");
            LinkedHashSet<String> headers = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                headers.addAll(row.keySet());
            }
            int headerIndex = 0;
            Row headerRow = sheet.createRow(0);
            for (String header : headers) {
                Cell cell = headerRow.createCell(headerIndex++);
                cell.setCellValue(header);
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                int colIndex = 0;
                for (String header : headers) {
                    Cell cell = excelRow.createCell(colIndex++);
                    Object value = row.get(header);
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean bool) {
                        cell.setCellValue(bool);
                    } else {
                        cell.setCellValue(value.toString());
                    }
                }
            }
            Files.createDirectories(outputPath.getParent());
            try (var stream = Files.newOutputStream(outputPath)) {
                workbook.write(stream);
            }
        }
    }

    private Map<String, Object> buildPythonConfig(Map<String, Object> body) {
        Map<String, Object> config = new HashMap<>();
        config.put("vendor", body.getOrDefault("vendor", "nokia"));
        config.put("preset", body.get("preset"));
        config.put("sheet_name", body.get("sheetName"));
        config.put("column_mapping", body.getOrDefault("columnMapping", Map.of()));
        config.put("kpi_mapping", body.getOrDefault("kpiMapping", Map.of()));
        config.put("selected_kpis", body.getOrDefault("selectedKpis", List.of()));
        config.put("kpis", body.getOrDefault("kpis", Map.of()));
        config.put("rca_thresholds", body.getOrDefault("rcaThresholds", Map.of()));
        config.put("severity_thresholds", body.getOrDefault("severityThresholds", Map.of()));
        config.put("anomaly_thresholds", body.getOrDefault("anomalyThresholds", Map.of()));
        config.put("date_mode", "pre_post".equals(body.get("dateMode")) ? "pre_post" : "single");

        Map<String, Object> dateFilter = new HashMap<>();
        dateFilter.put("start_date", body.getOrDefault("startDate", null));
        dateFilter.put("end_date", body.getOrDefault("endDate", null));
        config.put("date_filter", dateFilter);

        Map<String, Object> preDateFilter = new HashMap<>();
        preDateFilter.put("start_date", body.getOrDefault("preStartDate", null));
        preDateFilter.put("end_date", body.getOrDefault("preEndDate", null));
        config.put("pre_date_filter", preDateFilter);

        Map<String, Object> postDateFilter = new HashMap<>();
        postDateFilter.put("start_date", body.getOrDefault("postStartDate", null));
        postDateFilter.put("end_date", body.getOrDefault("postEndDate", null));
        config.put("post_date_filter", postDateFilter);
        return config;
    }

    private Map<String, Object> readPresetsFile() throws IOException {
        Path path = getPythonServiceDir().resolve("rca_presets.json");
        if (!Files.exists(path)) {
            return Map.of();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(content, new TypeReference<>() {});
    }

    private void ensurePythonEntry() {
        Path mainPath = getPythonMainPath();
        if (!Files.exists(mainPath)) {
            throw new IllegalStateException("Python RCA entry not found: " + mainPath);
        }
    }

    private String resolvePythonBin() {
        String envBin = pythonBin == null ? "" : pythonBin.trim();
        if (!envBin.isEmpty() && canExecutePython(envBin)) {
            return envBin;
        }
        String venvPython = pythonVenvPath == null ? "" : pythonVenvPath.trim();
        if (!venvPython.isEmpty() && canExecutePython(venvPython)) {
            return venvPython;
        }
        Path defaultVenvPython = getWorkspaceRoot().resolve("python service").resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (Files.exists(defaultVenvPython) && canExecutePython(defaultVenvPython.toString())) {
            return defaultVenvPython.toString();
        }
        if (canExecutePython("python")) {
            return "python";
        }
        throw new IllegalStateException("Python runtime could not be resolved.");
    }

    private boolean canExecutePython(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            return code == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path getPythonServiceDir() {
        Path configured = Paths.get(pythonServicePath == null || pythonServicePath.isBlank() ? "python service" : pythonServicePath.trim());
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        return getWorkspaceRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private Path getPythonMainPath() {
        return getPythonServiceDir().resolve("main.py");
    }

    private Path getWorkspaceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cwdName = cwd.getFileName();
        String name = cwdName == null ? "" : cwdName.toString().toLowerCase();
        if ("backend-java".equals(name) || "backend".equals(name)) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent;
            }
        }
        if (Files.exists(cwd.resolve("python service")) || Files.exists(cwd.resolve("backend")) || Files.exists(cwd.resolve("backend-java"))) {
            return cwd;
        }
        return cwd;
    }

    private ProcessResult executeProcess(List<String> args, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(cwd.toFile());
        Process process = builder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Python process interrupted", ex);
        }
        return new ProcessResult(exitCode, stdout, stderr);
    }

    private String readStream(java.io.InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
            return builder.toString().trim();
        }
    }

    private List<Map<String, Object>> toCanonicalRows(Map<String, Object> cache) {
        List<Map<String, Object>> rawRecords = getRecordList(cache.get("records"));
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (Map<String, Object> record : rawRecords) {
            Map<String, Object> row = new HashMap<>();
            row.put("date", record.getOrDefault("date", null));
            row.put("cellname", record.getOrDefault("cellName", null));
            row.put("site", record.getOrDefault("site", null));
            row.put("tech", record.getOrDefault("tech", null));
            row.put("sectorid", record.getOrDefault("sectorid", null));
            row.put("sectorname", record.getOrDefault("sectorname", null));
            row.put("groups", record.getOrDefault("groups", null));
            Map<String, Object> dimensions = getObjectMap(record.get("dimensions"));
            Map<String, Object> metrics = getObjectMap(record.get("metrics"));
            dimensions.forEach(row::put);
            metrics.forEach(row::put);
            canonical.add(row);
        }
        return canonical;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String)
                    .collect(Collectors.toMap(
                            entry -> (String) entry.getKey(),
                            entry -> entry.getValue()
                    ));
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRecordList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    public void cleanup(Path directory) {
        if (directory != null && Files.exists(directory)) {
            try {
                Files.walk(directory)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    private void logPerf(String operation, long fileId, int rowCount, long elapsedMillis) {
        if (isTruthy(perfLogsEnabledRaw)) {
            System.out.println(String.format("[RECOMMENDATION] op=%s fileId=%d rows=%d elapsedMs=%d", operation, fileId, rowCount, elapsedMillis));
        }
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String cleaned = value.trim().toLowerCase();
        return cleaned.equals("1") || cleaned.equals("true") || cleaned.equals("yes") || cleaned.equals("on");
    }

    public static class RecommendationResult {
        public final Map<String, Object> payload;
        public final Path excelPath;
        public final Path workDir;

        public RecommendationResult(Map<String, Object> payload, Path excelPath, Path workDir) {
            this.payload = payload;
            this.excelPath = excelPath;
            this.workDir = workDir;
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
