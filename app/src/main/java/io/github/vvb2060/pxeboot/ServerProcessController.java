package io.github.vvb2060.pxeboot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import io.github.vvb2060.pxeboot.server.App;

final class ServerProcessController {
    interface Listener {
        void onStateChanged(String logText, boolean running);
    }

    static final ServerProcessController INSTANCE = new ServerProcessController();

    private final Object lock = new Object();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final StringBuilder logBuffer = new StringBuilder();

    private Process process;
    private OutputStream controlStream;
    private boolean running;
    private boolean stopping;

    void addListener(Listener listener) {
        listeners.add(listener);
        Snapshot snapshot;
        synchronized (lock) {
            snapshot = new Snapshot(logBuffer.toString(), running);
        }
        listener.onStateChanged(snapshot.logText(), snapshot.running());
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void start(String apkPath, String serverIp, String tftpRoot, String httpRoot, String httpPort) {
        synchronized (lock) {
            if (running) {
                appendLogLocked("PXE Server is already running.\n");
                notifyListenersLocked();
                return;
            }

            logBuffer.setLength(0);
            appendLogLocked("Starting PXE Server...\n");

            try {
                String command = buildCommand(apkPath, serverIp, tftpRoot, httpRoot, httpPort);
                process = new ProcessBuilder("su", "-c", command).start();
                controlStream = process.getOutputStream();
                running = true;
                stopping = false;
                notifyListenersLocked();
                startPumpThread("pxe-stdout", process.getInputStream(), false);
                startPumpThread("pxe-stderr", process.getErrorStream(), true);
                startWaitThread(process);
            } catch (IOException e) {
                appendLogLocked("Starting PXE Server failed: " + e.getMessage() + "\n");
                clearProcessLocked();
                notifyListenersLocked();
            }
        }
    }

    void stop() {
        Process currentProcess;
        OutputStream currentControl;
        synchronized (lock) {
            if (!running || process == null) {
                return;
            }
            if (stopping) {
                return;
            }
            stopping = true;
            currentProcess = process;
            currentControl = controlStream;
            appendLogLocked("Stopping PXE Server...\n");
            notifyListenersLocked();
        }

        try {
            if (currentControl != null) {
                currentControl.write("stop\n".getBytes(StandardCharsets.UTF_8));
                currentControl.flush();
                currentControl.close();
            }
        } catch (IOException ignored) {
        }

        new Thread(() -> forceStopIfNeeded(currentProcess), "pxe-stop-watchdog").start();
    }

    private void forceStopIfNeeded(Process currentProcess) {
        try {
            if (currentProcess.waitFor(5, TimeUnit.SECONDS)) {
                return;
            }
            currentProcess.destroy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startPumpThread(String name, InputStream stream, boolean stderr) {
        var thread = new Thread(() -> pumpStream(stream, stderr), name);
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpStream(InputStream stream, boolean stderr) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog((stderr ? "[stderr] " : "") + line + '\n');
            }
        } catch (IOException e) {
            appendLog("Failed to read log: " + e.getMessage() + '\n');
        }
    }

    private void startWaitThread(Process startedProcess) {
        var thread = new Thread(() -> waitForExit(startedProcess), "pxe-waiter");
        thread.setDaemon(true);
        thread.start();
    }

    private void waitForExit(Process startedProcess) {
        int exitCode;
        try {
            exitCode = startedProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            appendLog("Waiting for PXE Server to exit was interrupted.\n");
            return;
        }

        synchronized (lock) {
            if (process != startedProcess) {
                return;
            }
            appendLogLocked("PXE Server has exited, exitCode=" + exitCode + "\n");
            clearProcessLocked();
            notifyListenersLocked();
        }
    }

    private void appendLog(String message) {
        synchronized (lock) {
            appendLogLocked(message);
            notifyListenersLocked();
        }
    }

    private void appendLogLocked(String message) {
        logBuffer.append(message);
        int maxChars = 1024 * 1024;
        if (logBuffer.length() > maxChars) {
            logBuffer.delete(0, logBuffer.length() - maxChars);
        }
    }

    private void notifyListenersLocked() {
        var snapshot = new Snapshot(logBuffer.toString(), running);
        for (var listener : listeners) {
            listener.onStateChanged(snapshot.logText(), snapshot.running());
        }
    }

    private void clearProcessLocked() {
        running = false;
        stopping = false;
        process = null;
        controlStream = null;
    }

    private static String buildCommand(String apkPath, String serverIp,
                                       String tftpRoot, String httpRoot, String httpPort) {
        return "CLASSPATH=" + shellEscape(apkPath)
            + " exec app_process / " + App.class.getName()
            + " --server-ip=" + serverIp
            + " --tftp-root=" + shellEscape(tftpRoot)
            + " --http-root=" + shellEscape(httpRoot)
            + " --http-port=" + httpPort
            + (BuildConfig.DEBUG ? " --verbose=true" : "");
    }

    private static String shellEscape(String value) {
        return '\'' + value.replace("'", "'\"'\"'") + '\'';
    }

    private record Snapshot(String logText, boolean running) {
    }
}
