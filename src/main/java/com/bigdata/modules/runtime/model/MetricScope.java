package com.bigdata.modules.runtime.model;

/** 指标所属层级：决定从哪个端点和哪个组件维度取数 */
public enum MetricScope {

    /** 作业级（Flink /jobs/{id}/metrics，Spark /jobs 汇总） */
    JOB,
    /** Flink vertex / subtask 级 */
    VERTEX,
    /** Flink TaskManager 级 */
    TASKMANAGER,
    /** Spark executor 级 */
    EXECUTOR,
    /** Spark stage 级 */
    STAGE
}
