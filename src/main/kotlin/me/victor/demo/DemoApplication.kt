package me.victor.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

// @EnableAsync: SnapshotTrigger의 @Async 활성화. 기본 SimpleAsyncTaskExecutor.
// 운영에선 ThreadPoolTaskExecutor 빈으로 스레드 수 제한 권장.
@SpringBootApplication
@EnableAsync
class DemoApplication

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
