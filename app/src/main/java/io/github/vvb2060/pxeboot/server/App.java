package io.github.vvb2060.pxeboot.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class App {

    public static void main(String[] args) throws InterruptedException {
        var config = AppConfig.fromArgs(args);

        var runtime = new ServerRuntime(config);
        runtime.start();

        var controlThread = new Thread(() -> readControlInput(runtime), "stdin-control");
        controlThread.setDaemon(true);
        controlThread.start();

        runtime.awaitTermination();
    }

    private static void readControlInput(ServerRuntime runtime) {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) continue;
                if ("stop".equals(command)) {
                    runtime.stop();
                    return;
                }
            }

            System.out.println("Stdin closed, terminate the server.");
            runtime.stop();
        } catch (IOException e) {
            System.err.println("Control input failed: " + e.getMessage());
            runtime.stop();
        }
    }
}
