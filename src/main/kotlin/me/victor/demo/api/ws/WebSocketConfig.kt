package me.victor.demo.api.ws

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP over WebSocket.
 *  - 구독: /topic/sessions/{id}   - 발행: /app/sessions/{id}/events
 *  - 엔드포인트는 raw WS + SockJS fallback 둘 다 노출.
 *  - SimpleBroker(in-memory) → MVP. 다중 인스턴스 시 Redis/Rabbit relay로 교체.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS()
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
    }
}
