package com.bigdata.modules.retrieval.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 未命中提示 —— 「找不到」必须是可执行的信息，而不是空数组。
 *
 * <h3>为什么要有这个结构</h3>
 * <p>检索返回空列表时，模型只有两种选择：再猜一个关键词，或者基于想象继续产出。
 * 后者是臆造的源头。把「试过哪些词」与「放宽约束后最接近的候选」一并返回，
 * 模型才有可能收敛：要么改成候选里的词重试，要么明确告诉用户「未找到」。</p>
 *
 * @param <T> 近似候选的类型（库表命中项 / 知识片段）
 * @author dataforge
 */
public class NotFoundHint<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本次实际使用过的关键词 */
    private List<String> tried = new ArrayList<>();

    /** 放宽环境/分层/业务域约束后最接近的候选（仍经过权限过滤） */
    private List<T> nearest = new ArrayList<>();

    /** 给模型的下一步建议（中文，可直接执行） */
    private String suggest;

    public List<String> getTried() { return tried; }
    public void setTried(List<String> tried) { this.tried = tried; }
    public List<T> getNearest() { return nearest; }
    public void setNearest(List<T> nearest) { this.nearest = nearest; }
    public String getSuggest() { return suggest; }
    public void setSuggest(String suggest) { this.suggest = suggest; }
}
