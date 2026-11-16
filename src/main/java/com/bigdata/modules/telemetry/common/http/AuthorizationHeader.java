package com.bigdata.modules.telemetry.common.http;

import com.bigdata.modules.telemetry.config.properties.Auth;
import com.bigdata.modules.telemetry.util.Strings;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 按 {@code telemetry.backends.*[].auth} 配置生成 {@code Authorization} 请求头。
 *
 * <p>{@code type=none} 或未配置返回 {@code null}；未知类型抛 {@link IllegalArgumentException}，
 * 由调用方决定转成 {@code BackendException} 还是启动失败。</p>
 */
public final class AuthorizationHeader {

    private AuthorizationHeader() {
    }

    public static String of(Auth auth, SecretResolver resolver, String owner) {
        if (auth == null || Strings.isBlank(auth.getType())
                || "none".equalsIgnoreCase(auth.getType())) {
            return null;
        }
        if ("basic".equalsIgnoreCase(auth.getType())) {
            String username = resolver.resolve(auth.getUsername(), auth.getUsernameRef(),
                    owner + ".username");
            String password = resolver.resolve(auth.getPassword(), auth.getPasswordRef(),
                    owner + ".password");
            return basic(username, password);
        }
        if ("bearer".equalsIgnoreCase(auth.getType())) {
            return "Bearer " + resolver.resolve(auth.getToken(), auth.getTokenRef(),
                    owner + ".token");
        }
        throw new IllegalArgumentException("未知认证类型: " + auth.getType());
    }

    /** HTTP Basic：{@code base64(username:password)}。 */
    public static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
