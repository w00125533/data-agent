package com.wireless.agent.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs commands inside Docker containers via "docker exec". */
public class DockerCommandRunner {

    private static final long DEFAULT_TIMEOUT_SEC = 120;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** Build the docker exec command array from container name and args. */
    public static List<String> buildCommand(String container, List<String> args) {
        var cmd = new ArrayList<String>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add(container);
        cmd.addAll(args);
        return cmd;
    }

    /** Execute a command inside a Docker container. */
    public Result exec(String container, List<String> args) {
        return exec(container, args, DEFAULT_TIMEOUT_SEC);
    }

    /** Execute with explicit timeout. */
    public Result exec(String container, List<String> args, long timeoutSec) {
        var cmd = buildCommand(container, args);
        try {
            var pb = new ProcessBuilder(cmd);
            var process = pb.start();

            // Read stdout and stderr concurrently to prevent deadlock
            var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> readStreamSafely(process.getInputStream()));
            var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> readStreamSafely(process.getErrorStream()));

            var finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1,
                        stdoutFuture.getNow(""),
                        stderrFuture.getNow(""),
                        true);
            }
            return new Result(process.exitValue(),
                    stdoutFuture.get(),
                    stderrFuture.get(),
                    false);
        } catch (java.util.concurrent.ExecutionException e) {
            return new Result(-1, "", "Stream read error: " + e.getCause().getMessage(), false);
        } catch (java.io.IOException e) {
            return new Result(-1, "", "System error (docker not found?): " + e.getMessage(), false);
        } catch (Exception e) {
            return new Result(-1, "", e.getMessage(), false);
        }
    }

    private String readStreamSafely(java.io.InputStream stream) {
        try {
            return readStream(new BufferedReader(new InputStreamReader(stream)));
        } catch (Exception e) {
            return "Error reading stream: " + e.getMessage();
        }
    }

    private String readStream(BufferedReader reader) throws Exception {
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}
