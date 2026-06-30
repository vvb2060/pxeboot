package io.github.vvb2060.pxeboot.server;

import java.util.ArrayList;
import java.util.List;

final class ServerRuntime {
    private final DhcpServer dhcpServer;
    private final TftpServer tftpServer;
    private final HttpFileServer httpFileServer;
    private final List<Thread> threads = new ArrayList<>();

    ServerRuntime(AppConfig config) {
        this.dhcpServer = new DhcpServer(config);
        this.tftpServer = new TftpServer(config);
        this.httpFileServer = new HttpFileServer(config);
    }

    void start() {
        startThread("dhcp-server", dhcpServer::start);
        startThread("tftp-server", tftpServer::start);
        startThread("http-server", httpFileServer::start);
    }

    private void startThread(String name, Runnable serverTask) {
        var thread = new Thread(() -> {
            try {
                serverTask.run();
            } catch (RuntimeException e) {
                System.err.println(e.getMessage());
            }
        }, name);
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
    }

    void awaitTermination() throws InterruptedException {
        for (var thread : threads) {
            thread.join();
        }
    }

    void stop() {
        for (var thread : threads) {
            thread.interrupt();
        }
    }
}
