package me.victor.demo

import me.victor.demo.it.IntegrationTestSupport
import org.junit.jupiter.api.Test

/**
 * Spring 컨텍스트가 정상적으로 로드되는지 확인하는 최소 테스트.
 * IntegrationTestSupport를 상속해서 Testcontainers/Postgres가 자동 기동된다.
 */
class DemoApplicationTests : IntegrationTestSupport() {

    @Test
    fun contextLoads() {
        // @SpringBootTest가 컨텍스트 로드 자체를 검증.
    }
}
