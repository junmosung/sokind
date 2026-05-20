plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    // jOOQ 공식 codegen 플러그인.
    id("org.jooq.jooq-codegen-gradle") version "3.20.10"
}

group = "me.victor"
version = "0.0.1-SNAPSHOT"
description = "demo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
}

// Spring Boot 4.0.6 기본 정렬을 override해서 codegen ↔ runtime jOOQ 버전 통일.
ext["jooq.version"] = "3.20.10"
// Testcontainers 2.x는 코어만 publish됨 — postgresql/junit-jupiter 모듈은 1.x만 존재.
ext["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // jOOQ: 컴파일 타임 타입 안전 + SQL DSL. ON CONFLICT/GREATEST/JSONB 모두 1급 시민.
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Boot 4: Flyway autoconfig가 spring-boot-flyway 모듈로 분리 — 명시 추가 필요.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.postgresql:postgresql")

    // codegen은 자체 classpath라 JDBC 드라이버 별도 등록.
    jooqCodegen("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Boot 4: TestRestTemplate은 spring-boot-resttestclient, 의존 RestTemplateBuilder는 spring-boot-restclient로 분리.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    // WebSocketStompClient의 MappingJackson2MessageConverter용 — Kotlin data class 역직렬화.
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // @ServiceConnection으로 Postgres 컨테이너 자동 연결 — `./gradlew test` 한 번으로 검증.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // @Async 완료 폴링용 DSL.
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.awaitility:awaitility-kotlin")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 루트의 openapi.yaml을 SoT로 두고 빌드 시 static/으로 복사 → Swagger UI가 /openapi.yaml로 접근.
tasks.named<Copy>("processResources") {
    from("openapi.yaml") { into("static") }
}

// ----- jOOQ codegen -----
// 라이브 Postgres(docker compose)를 introspect → Postgres 고유 기능 100% 반영.
// 빌드 전 `docker compose up -d postgres` 필요. 생성물은 build/generated-sources, 커밋하지 않음.
val jooqGenDir = layout.buildDirectory.dir("generated-sources/jooq/main").get().asFile

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = "jdbc:postgresql://localhost:5432/chat"
            user = "chat"
            password = "chat"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
                // Flyway 메타 + pgcrypto 익스텐션의 helper는 도메인이 아님.
                excludes = """
                    flyway_schema_history
                    | pgp_armor_headers
                    | armor.* | crypt.* | dearmor.* | digest.* | encrypt.* | decrypt.*
                    | gen_random_.* | gen_salt.* | hmac.*
                    | pgp_.*
                """.trimIndent()
            }
            generate {
                isRecords = true
                isPojosAsKotlinDataClasses = true
                isFluentSetters = true
            }
            target {
                packageName = "me.victor.demo.jooq"
                directory = jooqGenDir.absolutePath
            }
        }
    }
}

sourceSets.named("main") { java.srcDirs(jooqGenDir) }

// 컴파일 전에 codegen 실행 → 스키마 변경 즉시 반영.
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
