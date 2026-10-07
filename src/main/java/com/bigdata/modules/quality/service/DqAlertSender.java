package com.bigdata.modules.quality.service;

import com.bigdata.modules.quality.entity.DqAlertLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警投递器。
 *
 * <h3>为什么只做 Webhook</h3>
 * <p>界面上的渠道编码是既成契约（{@code AlertTab.tsx::CHANNEL_OPTIONS}）：INBOX / EMAIL / WEBHOOK，
 * 且注释写明「第一期只落站内记录，邮件与 Webhook 由服务端决定是否投递」。其中：</p>
 * <ul>
 *   <li><b>INBOX</b> 不需要投递 —— 落 {@code dq_alert_log} 就是送达，状态直接 SENT；</li>
 *   <li><b>WEBHOOK</b> 是本类负责的：地址走配置 {@code alert.webhook.url}，未配置就保持待发，
 *       不假装成功。企业微信 / 钉钉的群机器人都是 Webhook，因此这一个通道能覆盖主要场景；</li>
 *   <li><b>EMAIL</b> 需要 SMTP 配置（账号、授权码、发信域），平台目前没有任何邮件配置。
 *       不做半成品：保持 PENDING 并在 {@code failReason} 里写明「邮件通道未配置」，
 *       让人一眼看出是"没配"而不是"发失败"。</li>
 * </ul>
 *
 * <h3>投递为什么不算失败</h3>
 * <p>告警是通知，不是校验本身。投递失败只落 FAILED + 原因供重投，绝不冒泡成异常 ——
 * 否则一次 Webhook 抖动会把质检任务的执行结果带崩。</p>
 */
@Component
public class DqAlertSender {

    private static final Logger log = LoggerFactory.getLogger(DqAlertSender.class);

    /** 告警渠道编码（与前端 AlertTab.tsx::CHANNEL_OPTIONS 逐字对应） */
    public static final String CHANNEL_INBOX = "INBOX";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEBHOOK = "WEBHOOK";

    /** 投递状态：待发 / 已送达 / 投递失败 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    @Value("${alert.webhook.url:}")
    private String webhookUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** Webhook 地址是否已配置（未配置时投递保持待发） */
    public boolean isWebhookConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * 投递一条 Webhook 告警。
     *
     * @param checkStatus 触发本次告警的校验结果状态（ERROR / WARN / FAILED）——
     *                    告警记录表里没有这一列，但接收人最关心的就是它，所以随报文一起发
     * @return {@code null} 表示投递成功；否则返回失败原因，由调用方落 {@code failReason}
     */
    public String sendWebhook(DqAlertLog alert, String checkStatus) {
        if (!isWebhookConfigured()) {
            return "未配置告警 Webhook 地址（alert.webhook.url），已登记待发";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", alert.getTaskId());
            payload.put("runId", alert.getRunId());
            payload.put("ruleId", alert.getRuleId());
            payload.put("ruleName", alert.getRuleName());
            payload.put("checkStatus", checkStatus);
            payload.put("channel", alert.getChannel());
            payload.put("receivers", alert.getReceiver());
            payload.put("sentAt", LocalDateTime.now().toString());

            HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(webhookUrl)))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return "Webhook 返回 HTTP " + response.statusCode();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Webhook 投递被中断";
        } catch (Exception e) {
            log.warn("告警 Webhook 投递失败: ruleName={}, url={}", alert.getRuleName(), webhookUrl, e);
            return "Webhook 投递失败: " + e.getMessage();
        }
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
