package com.bigdata.modules.logs.adapter;

import java.time.Duration;
import java.util.List;

/**
 * 命令执行抽象（YARN 聚合日志走 {@code yarn logs}）。
 *
 * <p>参数以数组形式传入，不经过 shell，避免命令注入；输出有上限，防止超大日志打爆内存。</p>
 */
public interface CommandRunner {

    CommandResult run(List<String> command, Duration timeout) throws Exception;
}
