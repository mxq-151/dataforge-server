package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 智能体检索的结构化过滤条件。
 *
 * <h3>为什么过滤条件必须显式入参</h3>
 * <p>「环境 / 分层 / 业务域 / 数据源」是<b>枚举型约束</b>，判定结果是确定的；
 * 交给模型从结果里自行挑选，只会把确定性工作变成概率问题（同一关键词下
 * {@code _test/_gray/_prod} 三套库同名表会被随机选中）。因此这些条件在服务端
 * 直接下推到查询，模型只负责决定「要不要收紧、收紧到哪一档」。</p>
 *
 * <p>任何一项为空都表示「该项不过滤」，与既有资产检索口径一致。</p>
 *
 * @author dataforge
 */
public class AgentFilters implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 环境码：prod / gray / test；按库名后缀解析，识别不出环境的表在显式过滤时被排除 */
    private String env;

    /** 分层编码（ods/dwd/dws/ads/dim/dqc/tmp），多选取并集 */
    private List<String> layers;

    /** 业务域编码，多选取并集 */
    private List<String> domains;

    /** 数据源名称，多选取并集；按名称而非 id，便于模型直接使用知识库中出现的称谓 */
    private List<String> datasources;

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public List<String> getLayers() { return layers; }
    public void setLayers(List<String> layers) { this.layers = layers; }
    public List<String> getDomains() { return domains; }
    public void setDomains(List<String> domains) { this.domains = domains; }
    public List<String> getDatasources() { return datasources; }
    public void setDatasources(List<String> datasources) { this.datasources = datasources; }
}
