package com.bigdata.modules.telemetry.v1.metadata.spi;

/**
 * 服务版本 / 配置的取数扩展点。
 *
 * <p>实现只需是 Spring Bean：{@code @Component} + {@code telemetry.metadata.providers.<id>}
 * 配置块，不需要改编排器、注册表或响应模型。{@code providerId} 全局唯一，重复即启动失败。</p>
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>只返回原始值，不自行脱敏——核心 {@code MetadataMasker} 统一处理；</li>
 *   <li>失败抛 {@link com.bigdata.modules.telemetry.v1.spi.BackendException}，带类型化 code 与
 *       requirements；取不到就是 NOT_CONFIGURED / UNAVAILABLE，禁止返回假数据或默认版本；</li>
 *   <li>不得把原始配置值、密码、token 写进异常消息或日志；</li>
 *   <li>必须遵守 {@link MetadataQuery#timeout()}。</li>
 * </ul>
 */
public interface ServiceMetadataProvider {

    /** 全局唯一标识，用于配置键、目录与 attempts[]。 */
    String providerId();

    MetadataCapability capability();

    /** 是否启用；由 provider 自己从配置读取，false 时不参与路由。 */
    boolean enabled();

    /** 一次只处理一个 target。 */
    MetadataFetchResult fetch(MetadataQuery query);
}
