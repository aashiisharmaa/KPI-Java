# TypeScript to Java Migration Report

This Spring Boot project is the Java equivalent of the existing TypeScript backend. The goal was to preserve routes, payloads, validation, auth, database behavior, and Python child-process execution as closely as possible.

## Status

- Build: `mvn -q -s local-settings.xml -f pom.xml compile` passes
- Python scripts: not modified
- Core architecture: preserved as closely as Spring Boot allows

## Project Structure

- `src/main/java/com/network/monitoring/Application.java`
- `src/main/java/com/network/monitoring/controller/*`
- `src/main/java/com/network/monitoring/service/*`
- `src/main/java/com/network/monitoring/entity/*`
- `src/main/java/com/network/monitoring/repository/*`
- `src/main/java/com/network/monitoring/security/*`
- `src/main/java/com/network/monitoring/config/*`
- `src/main/java/com/network/monitoring/util/*`
- `src/main/resources/application.yml`
- `pom.xml`
- `local-settings.xml`

## Module Map

| TypeScript file | Purpose | Java equivalent |
|---|---|---|
| `backend/src/server.ts` | App bootstrap, CORS, router mounting | `Application.java`, `config/WebConfig.java`, `config/DatabaseConfig.java` |
| `backend/src/routes/main.routes.ts` | Top-level route composition | Spring `@RequestMapping` on controllers, plus `config/WebConfig.java` for CORS |
| `backend/src/routes/auth.routes.ts` | Auth route composition | `controller/AuthController.java` |
| `backend/src/routes/upload.routes.ts` | Upload route composition | `controller/UploadController.java` |
| `backend/src/routes/dashboard.routes.ts` | Dashboard route composition | `controller/DashboardController.java` |
| `backend/src/routes/kpi.routes.ts` | KPI route composition | `controller/KpiController.java` |
| `backend/src/routes/setting.routes.ts` | Settings route composition | `controller/SettingController.java` |
| `backend/src/routes/sitedata.routes.ts` | Site data route composition | `controller/UploadController.java` and `service/UploadService.java` |
| `backend/src/controller/user.controller.ts` | Register, login, logout | `controller/AuthController.java`, `service/AuthService.java`, `security/JwtService.java`, `security/JwtAuthenticationFilter.java` |
| `backend/src/controller/upload.controller.ts` | Upload KPI/site/alarm data, history, delete, network/site/alarm views | `controller/UploadController.java`, `service/UploadService.java`, `service/FileStorageService.java`, `service/ParserService.java` |
| `backend/src/controller/sitedata.controller.ts` | Site data upload and listing | `controller/UploadController.java` and `service/UploadService.java` |
| `backend/src/controller/dashboard.controller.ts` | Dashboard stats and summary endpoints | `controller/DashboardController.java`, `service/DashboardService.java` |
| `backend/src/controller/kpi.controllers.ts` | KPI metrics, dynamic KPI cache endpoints, export | `controller/KpiController.java`, `service/KpiService.java`, `service/KpiCacheService.java` |
| `backend/src/controller/recommendation.controller.ts` | RCA recommendation run/export/presets | `controller/RecommendationController.java`, `service/RecommendationService.java` |
| `backend/src/controller/setting.controller.ts` | Settings/threshold get, update, import/export | `controller/SettingController.java`, `service/SettingService.java` |
| `backend/src/utils/auth.jwt.ts` | JWT generation and password hashing | `security/JwtService.java`, `service/AuthService.java` |
| `backend/src/utils/parserFile.ts` | File parsing | `service/ParserService.java` |
| `backend/src/utils/kpiDynamicCache.ts` | KPI dynamic cache file handling | `service/KpiCacheService.java` and KPI cache build logic in `service/UploadService.java` |
| `backend/src/utils/redisCache.ts` | Redis-backed cache helpers | `service/RedisService.java` and cache invalidation hooks in Java services |
| `backend/src/utils/response.ts` | Shared response wrapper | `dto/ApiResponse.java` plus direct `Map<String, Object>` responses in controllers |
| `backend/src/middleware/auth.middleware.ts` | Request auth guard | `security/JwtAuthenticationFilter.java` |
| `backend/src/middleware/multer.middleware.ts` | Multipart upload handling | Spring MVC multipart support in `controller/UploadController.java` |
| `backend/src/constants/constants.ts` | Shared status codes and auth messages | Inline controller/service constants and validation checks in Java |
| `backend/src/types/express.d.ts` | Express request typing augmentation | No direct Java equivalent needed |
| `backend/src/config/connectdb.ts` | Prisma/MySQL connection setup | `config/DatabaseConfig.java`, `application.yml` |
| `backend/src/config/prisma.ts` | Prisma client wiring | `repository/*` and Spring Data JPA / `JdbcTemplate` |
| `backend/prisma.config.ts` | Prisma build/runtime config | `pom.xml`, `application.yml` |
| `backend/prisma/schema.prisma` | Data model and relations | `entity/*` and `repository/*` classes |

## Entity and Repository Map

| Prisma model | Java entity | Java repository |
|---|---|---|
| `User` | `entity/User.java` | `repository/UserRepository.java` |
| `Session` | `entity/Session.java` | `repository/SessionRepository.java` |
| `UploadHistory` | `entity/UploadHistory.java` | `repository/UploadHistoryRepository.java` |
| `UploadData` | `entity/UploadData.java` | `repository/UploadDataRepository.java`, `repository/UploadDataRepositoryImpl.java`, `repository/UploadDataRepositoryCustom.java` |
| `SiteData` | `entity/SiteData.java` | `repository/SiteDataRepository.java` |
| `AlarmData` | `entity/AlarmData.java` | `repository/AlarmDataRepository.java` |
| `Threshold` | `entity/Threshold.java` | `repository/ThresholdRepository.java` |
| `Settings` | `entity/Settings.java` | `repository/SettingsRepository.java` |

## What Was Preserved

- Routes and endpoint names
- Request/response shapes as closely as Spring Boot allows
- Authentication and session flow
- MySQL schema behavior and relations
- File upload, history, delete, and listing flows
- KPI cache generation and dynamic KPI endpoints
- Recommendation execution through Python child processes
- Settings/threshold validation and import/export

## Small Java-Side Adjustments

- Prisma `createMany({ skipDuplicates: true })` was replaced with `JdbcTemplate` batch inserts using `INSERT IGNORE`
- Python execution uses `ProcessBuilder` instead of Node `child_process.spawn`
- File and cache paths are resolved from the workspace root so behavior matches the TypeScript project regardless of launch directory
- Dynamic KPI export uses Apache POI instead of SheetJS
- `DATABASE_URL` is accepted in Prisma/MySQL style and converted to a JDBC MySQL datasource
- Route modules were collapsed into Spring annotations instead of separate Express router files
- The commented-out TypeScript `sitedata.routes.ts` module is represented by the active site-data endpoints on the Java upload controller

## Environment Variables

- `DATABASE_URL`
- `DB_USER`
- `DB_PASSWORD`
- `PORT`
- `CORS_ORIGINS`
- `UPLOADS_BASE`
- `KPI_CACHE_DIR`
- `PYTHON_SERVICE_PATH`
- `PROJECT_VENV_PYTHON`
- `PYTHON_BIN`
- `REDIS_ENABLED`
- `REDIS_URL`
- `REDIS_TTL_SECONDS`
- `PERF_LOGS`
- `KPI_DEBUG_LOGS`
- `JWT_SECRET`

## Run Commands

```powershell
cd "C:\Kpi_monitoring\network monitoring_v03\backend-java"
& ".\tools\apache-maven-3.9.15\bin\mvn.cmd" -s ".\local-settings.xml" spring-boot:run
```

Build-only:

```powershell
cd "C:\Kpi_monitoring\network monitoring_v03\backend-java"
& ".\tools\apache-maven-3.9.15\bin\mvn.cmd" -s ".\local-settings.xml" compile
```

## Notes

- The Java backend is the active migration target.
- The TypeScript backend remains the source of truth for behavior parity checks.
- If you want a truly file-by-file code walkthrough next, the best next step is to open the relevant Java file and I can explain it line by line against its TypeScript origin.
