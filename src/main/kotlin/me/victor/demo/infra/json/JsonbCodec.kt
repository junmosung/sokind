package me.victor.demo.infra.json

import org.jooq.JSONB
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * JSONB 컬럼 ↔ Kotlin Map 변환 헬퍼.
 *
 * jOOQ의 [JSONB] 타입은 단순 String wrapper라 application 레벨에서 Map으로 파싱한다.
 * JPA를 쓰지 않는 이유는 이벤트 소싱 특성상 raw SQL/DSL 제어가 잦아 추상화가 오히려
 * 비용이기 때문이다 (jOOQ는 SQL을 그대로 보여주면서 타입 안전성을 더해줌).
 */
@Component
class JsonbCodec(private val mapper: ObjectMapper) {

    fun toJsonb(value: Map<String, Any?>): JSONB =
        JSONB.valueOf(mapper.writeValueAsString(value))

    @Suppress("UNCHECKED_CAST")
    fun fromJsonb(value: JSONB?): Map<String, Any?> = when (value) {
        null -> emptyMap()
        else -> mapper.readValue(value.data() ?: "{}", Map::class.java) as Map<String, Any?>
    }

    /**
     * 타입 안전 변환. snapshot.state처럼 도메인 객체를 그대로 JSON으로 저장할 때 사용.
     * Jackson이 Instant/Enum/data class를 자동 처리하므로 Map 경유보다 정확하고 빠르다.
     */
    fun <T : Any> toJsonbTyped(value: T): JSONB =
        JSONB.valueOf(mapper.writeValueAsString(value))

    fun <T : Any> fromJsonbTyped(value: JSONB, clazz: Class<T>): T =
        mapper.readValue(value.data() ?: error("empty jsonb"), clazz)
}
