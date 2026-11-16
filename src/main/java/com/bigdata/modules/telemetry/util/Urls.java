package com.bigdata.modules.telemetry.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** URL 片段编码：路径参数里的空格必须是 %20，不能是表单语义的 '+'。 */
public final class Urls {

    private Urls() {
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
