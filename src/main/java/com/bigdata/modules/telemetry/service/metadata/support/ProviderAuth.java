package com.bigdata.modules.telemetry.service.metadata.support;

import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.common.http.AuthorizationHeader;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;

import java.util.List;
import java.util.Map;

/**
 * metadata provider 的 {@code auth} 配置 → {@code Authorization} 头。
 *
 * <p>Ambari / ClickHouse 这些「账号密码 + Basic」的 provider 共用这一份实现：密码只从
 * {@code password-ref} 解析，解析失败转成带 {@code CREDENTIAL} 要求的 {@code NOT_CONFIGURED}，
 * 不把明文密码写进配置或异常消息。</p>
 */
public final class ProviderAuth {

    private ProviderAuth() {
    }

    /**
     * 生成 Basic 头；{@code auth} 为空或 {@code type=none} 返回 null。
     *
     * @param defaultUser 未配置 {@code auth.username} 时使用的账号（各产品默认值不同）
     */
    public static String basic(ProviderSettings settings, String providerId, String defaultUser,
                               SecretResolver secretResolver) {
        Map<String, Object> auth = settings.map(providerId, "auth");
        if (auth.isEmpty() || "none".equalsIgnoreCase(String.valueOf(auth.get("type")))) {
            return null;
        }
        Object user = auth.get("username");
        Object passwordRef = auth.get("password-ref");
        String password;
        try {
            password = secretResolver.resolve(null,
                    passwordRef == null ? null : String.valueOf(passwordRef),
                    providerId + ".password");
        } catch (IllegalStateException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED, e.getMessage(),
                    List.of(new Requirement(RequirementKind.CREDENTIAL,
                            "telemetry.metadata.providers." + providerId + ".auth.password-ref",
                            "配置可解析的密码引用")));
        }
        return AuthorizationHeader.basic(user == null ? defaultUser : String.valueOf(user), password);
    }
}
