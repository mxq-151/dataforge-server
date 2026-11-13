package com.bigdata.modules.logs.adapter;

/**
 * HTTP 取文本的最小抽象，便于单测注入假实现（不启动真实服务）。
 */
public interface HttpLogClient {

    HttpTextResponse get(String url, java.util.Map<String, String> headers, java.time.Duration timeout) throws Exception;
}
