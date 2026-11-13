package com.bigdata.modules.runtime.adapter;

import com.bigdata.modules.logs.model.ServiceType;
import com.bigdata.modules.runtime.model.RuntimeMode;
import com.bigdata.modules.runtime.vo.RuntimeComponentVO;
import com.bigdata.modules.runtime.vo.RuntimeConfigVO;
import com.bigdata.modules.runtime.vo.RuntimeJobVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;

import java.util.List;

/**
 * 运行时指标适配器：每个服务（Flink / Spark）实现一份。
 *
 * <p>与日志适配器的区别：没有游标、没有文本流；输入是作业 + 指标族，输出是结构化快照。</p>
 */
public interface RuntimeAdapter {

    boolean supports(ServiceType serviceType, RuntimeMode mode);

    /** 作业发现：Flink /jobs/overview；Spark SHS /applications 或 YARN RM 应用列表 */
    List<RuntimeJobVO> listJobs(RuntimeQueryContext context);

    /** 组件列表：Flink TaskManager / Spark executor（可选附带指标） */
    List<RuntimeComponentVO> listComponents(RuntimeQueryContext context);

    /** 单作业运行时快照：状态 + 组件 + 指定指标族 + checkpoint */
    RuntimeSnapshotVO snapshot(RuntimeQueryContext context);

    /** 配置参数：作业配置、集群配置、executor/TM 资源（已脱敏） */
    RuntimeConfigVO config(RuntimeQueryContext context);
}
