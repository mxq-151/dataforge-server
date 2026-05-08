package com.bigdata.modules.development.config;

import com.bigdata.modules.development.controller.LSPWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class LSPWebSocketConfig implements WebSocketConfigurer {

    private final LSPWebSocketHandler lspWebSocketHandler;

    public LSPWebSocketConfig(LSPWebSocketHandler lspWebSocketHandler) {
        this.lspWebSocketHandler = lspWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(lspWebSocketHandler, "/ws/lsp")
                .setAllowedOriginPatterns("*");
    }
}
