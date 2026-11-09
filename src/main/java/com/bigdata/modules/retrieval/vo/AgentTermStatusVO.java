package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 术语词典现状 —— 让「术语扩展到底有没有生效」「哪些定义被覆盖了」看得见。
 *
 * <h3>为什么需要这个接口</h3>
 * <p>术语扩展是静默生效的：词典为空时检索照常工作，只是少了同义召回；重复定义时按更新时间
 * 取最新，被忽略的一方不会收到任何提示。这两件事都必须可观测，否则「为什么模型没认出
 * 这个业务词」只能靠猜 —— 而答案往往是「知识库里根本没有术语表」或「术语写在了另一篇文档里」。</p>
 *
 * @author dataforge
 */
public class AgentTermStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 生效的术语条数（按标准说法去重） */
    private int termCount;

    /** 参与解析的业务知识文档数；0 表示知识库里没有可解析的文档 */
    private int documentCount;

    /** 上次加载时间；空表示尚未加载 */
    private String loadedAt;

    /** 重复定义处数 */
    private int conflictCount;

    /** 重复定义明细（最多返回 100 条，避免响应过大） */
    private List<Conflict> conflicts = new ArrayList<>();

    /** 给人看的结论：词典型是否正常、为空的原因、需要清理什么 */
    private String hint;

    /** 一条重复定义 */
    public static class Conflict implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 冲突的词（小写归一） */
        private String key;

        /** 实际生效的来源：knowledge:{文档 id}:{文档标题} */
        private String winnerSource;

        /** 被忽略的来源 */
        private String ignoredSource;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getWinnerSource() { return winnerSource; }
        public void setWinnerSource(String winnerSource) { this.winnerSource = winnerSource; }
        public String getIgnoredSource() { return ignoredSource; }
        public void setIgnoredSource(String ignoredSource) { this.ignoredSource = ignoredSource; }
    }

    public int getTermCount() { return termCount; }
    public void setTermCount(int termCount) { this.termCount = termCount; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public String getLoadedAt() { return loadedAt; }
    public void setLoadedAt(String loadedAt) { this.loadedAt = loadedAt; }
    public int getConflictCount() { return conflictCount; }
    public void setConflictCount(int conflictCount) { this.conflictCount = conflictCount; }
    public List<Conflict> getConflicts() { return conflicts; }
    public void setConflicts(List<Conflict> conflicts) { this.conflicts = conflicts; }
    public String getHint() { return hint; }
    public void setHint(String hint) { this.hint = hint; }
}
