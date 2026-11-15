/**
 * v1 运行时取数：作业发现、单作业快照、配置参数与时间点对比。
 *
 * <p>端点（均在 {@code /api/telemetry/v1} 下）：
 * <ul>
 *   <li>{@code POST /discovery/query}：scope = sources / jobs / components；</li>
 *   <li>{@code POST /runtime/snapshot}：状态 + 组件 + 指标族 + checkpoint；</li>
 *   <li>{@code POST /runtime/config}：作业 / 集群 / executor 配置（已脱敏）；</li>
 *   <li>{@code POST /runtime/compare}：与进程内更早一份快照做差。</li>
 * </ul>
 *
 * <h3>compare 的两条限制</h3>
 * <ol>
 *   <li>历史只存在进程内环形缓冲（容量 {@code telemetry.v1.runtime.history-capacity}，默认 10），
 *       <b>进程重启历史即丢</b>，不做持久化；</li>
 *   <li>多实例部署时每个实例各自持有一份缓冲，<b>互不可见、跨实例不可比</b>；
 *       要做跨实例对比必须先把快照写入同一存储。</li>
 * </ol>
 *
 * <p>指标取不到时固定返回 {@code UNAVAILABLE} / {@code NOT_SUPPORTED} 并带 reason，不补 0。</p>
 */
package com.bigdata.modules.telemetry.v1.runtime;
