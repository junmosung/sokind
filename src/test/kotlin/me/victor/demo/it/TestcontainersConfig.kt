package me.victor.demo.it

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 모든 통합 테스트가 공유하는 Postgres 컨테이너.
 *
 *  - @ServiceConnection이 Spring DataSource 빈에 컨테이너 정보를 자동 주입 → DynamicPropertySource 불필요.
 *  - 같은 application.yaml의 Flyway 설정이 컨테이너에 V1__init.sql을 자동 적용.
 *  - 컨테이너는 JVM 라이프사이클 동안 1개만 떠 있음(static-like). 테스트마다 TRUNCATE로 격리.
 *  - 이미지 태그는 main 코드의 docker-compose.yml과 동일하게 16-alpine으로 통일.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat")
}
