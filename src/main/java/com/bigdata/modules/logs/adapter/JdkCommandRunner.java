package com.bigdata.modules.logs.adapter;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JDK ProcessBuilder 实现：stdout / stderr 分别用守护线程读取，避免缓冲区写满导致子进程阻塞。
 */
@Component
public class JdkCommandRunner implements CommandRunner {

    /** 单路输出上限 4MB，超出部分丢弃但继续读空流 */
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    @Override
    public CommandResult run(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();

        StreamCollector out = new StreamCollector(process.getInputStream());
        StreamCollector err = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(out, "log-cmd-stdout");
        Thread errThread = new Thread(err, "log-cmd-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            outThread.join(1000);
            errThread.join(1000);
            return new CommandResult(-1, out.text(), err.text(), true);
        }
        outThread.join(2000);
        errThread.join(2000);
        return new CommandResult(process.exitValue(), out.text(), err.text(), false);
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int read;
            try (InputStream in = input) {
                while ((read = in.read(chunk)) != -1) {
                    int remaining = MAX_OUTPUT_BYTES - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(read, remaining));
                    }
                }
            } catch (Exception ignored) {
                // 进程被强杀时流会抛异常，忽略即可
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
