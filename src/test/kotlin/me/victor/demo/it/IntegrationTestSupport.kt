package me.victor.demo.it

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * 모든 IT 테스트의 공통 베이스.
 *
 *  - @SpringBootTest로 풀 컨텍스트 + RANDOM_PORT (병렬 실행 시 충돌 없음).
 *  - @Import로 Testcontainers 빈 등록.
 *  - 각 테스트 메서드 시작 전에 TRUNCATE로 DB 격리 (Flyway DDL은 유지).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfig::class)
abstract class IntegrationTestSupport {

    @Autowired
    protected lateinit var rest: TestRestTemplate

    @Autowired
    protected lateinit var dsl: DSLContext

    @LocalServerPort
    protected var port: Int = 0

    protected fun baseUrl(): String = "http://localhost:$port"

    @BeforeEach
    fun cleanDatabase() {
        // 테이블 순서는 FK 의존 무관 (CASCADE) — RESTART IDENTITY로 BIGSERIAL도 1로 리셋.
        dsl.execute("TRUNCATE events, participants, sessions, snapshots RESTART IDENTITY CASCADE")
    }
}
