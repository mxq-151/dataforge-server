package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 智能体检索命中项 —— 只承载「判断是否为目标」所需的最小信息。
 *
 * <h3>为什么不返回字段清单</h3>
 * <p>检索阶段返回整表结构有两个后果：一是上下文被大量无关字段占满，模型注意力漂移；
 * 二是模型倾向于「既然已经拿到字段就直接开工」，跳过了对表本身是否正确的判断。
 * 因此这里只给摘要，确认目标后再调详情接口取结构（两段式检索）。</p>
 *
 * <p>{@code matchedBy} 与 {@code evidence} 是给模型做二次判断的依据，也是产出物里
 * 可以引用的证据来源 —— 没有证据的命中，模型无法解释自己为什么选了这张表。</p>
 *
 * @author dataforge
 */
public class AgentSearchItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资产类型：table / column / indicator */
    private String kind;

    /** table / indicator 用自身 id；column 用所属表 id（可直接用于取详情） */
    private Long id;

    /** 名称：表名 / 字段名 / 指标名 */
    private String name;

    /** 所属库；指标为空 */
    private String database;

    /** 字段命中时所属表名；其余为空 */
    private String table;

    /** 注释 / 业务定义摘要 */
    private String comment;

    /** 分层编码（表名规范解析结果） */
    private String layerCode;

    /** 业务域编码 */
    private String domainCode;

    /** 环境码；识别不出时为空 */
    private String env;

    /** 相关度 0~1，仅用于同批结果的相对排序，不是概率 */
    private Double score;

    /** 命中方式：name / comment / column / code / definition */
    private String matchedBy;

    /** 命中说明（中文，可直接进产出物作为依据） */
    private String evidence;

    /** 命中的关键词 */
    private List<String> matchedKeywords;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getLayerCode() { return layerCode; }
    public void setLayerCode(String layerCode) { this.layerCode = layerCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getMatchedBy() { return matchedBy; }
    public void setMatchedBy(String matchedBy) { this.matchedBy = matchedBy; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public List<String> getMatchedKeywords() { return matchedKeywords; }
    public void setMatchedKeywords(List<String> matchedKeywords) { this.matchedKeywords = matchedKeywords; }
}
