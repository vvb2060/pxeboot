package io.github.vvb2060.pxeboot;

import android.app.Activity;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ServerProcessController.Listener {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ServerProcessController controller = ServerProcessController.INSTANCE;

    private View configContainer;
    private View runningContainer;
    private TextView tftpRootView;
    private Spinner serverIpSpinner;
    private EditText httpRootEdit;
    private EditText httpPortEdit;
    private EditText autoexecEdit;
    private Button saveButton;
    private Button startButton;
    private Button stopButton;
    private ScrollView logScroll;
    private TextView logView;

    private File tftpRootDir;
    private File autoexecFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tftpRootDir = getExternalFilesDir("tftp");
        autoexecFile = new File(tftpRootDir, "autoexec.ipxe");
        bindViews();

        prepareTftpAssets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        controller.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private void bindViews() {
        var rootView = findViewById(android.R.id.content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            rootView.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                var mask = WindowInsets.Type.systemBars() | WindowInsets.Type.ime();
                var insets = windowInsets.getInsets(mask);
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        configContainer = findViewById(R.id.config_container);
        runningContainer = findViewById(R.id.running_container);
        tftpRootView = findViewById(R.id.tftp_root_view);
        serverIpSpinner = findViewById(R.id.server_ip_spinner);
        httpRootEdit = findViewById(R.id.http_root_edit);
        httpPortEdit = findViewById(R.id.http_port_edit);
        autoexecEdit = findViewById(R.id.autoexec_edit);
        saveButton = findViewById(R.id.save_button);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);

        var ipv4Addresses = findLocalIpv4();
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ipv4Addresses);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        serverIpSpinner.setAdapter(adapter);

        var httpRootDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        tftpRootView.setText(tftpRootDir.getAbsolutePath());
        httpRootEdit.setText(httpRootDir.getAbsolutePath());
        httpPortEdit.setText("80");

        saveButton.setOnClickListener(v -> saveAutoexec());
        startButton.setOnClickListener(v -> startServer());
        stopButton.setOnClickListener(v -> controller.stop());
    }

    private void prepareTftpAssets() {
        saveButton.setEnabled(false);
        startButton.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                ensureAssetTree(getAssets(), "");
                var autoexecContent = new String(Files.readAllBytes(autoexecFile.toPath()));
                runOnUiThread(() -> {
                    autoexecEdit.setText(autoexecContent);
                    saveButton.setEnabled(true);
                    startButton.setEnabled(true);
                });
            } catch (IOException e) {
                runOnUiThread(() -> showToast(e.getMessage()));
            }
        });
    }

    private void saveAutoexec() {
        var content = autoexecEdit.getText().toString();
        saveButton.setEnabled(false);
        ioExecutor.execute(() -> {
            try {
                Files.write(autoexecFile.toPath(), content.getBytes());
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    showToast(getString(R.string.save_success));
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    showToast(getString(R.string.save_failed) + ": " + e.getMessage());
                });
            }
        });
    }

    private void startServer() {
        var context = getApplicationContext();
        String tftpRoot = tftpRootView.getText().toString();
        String httpRoot = httpRootEdit.getText().toString();
        String httpPort = httpPortEdit.getText().toString();
        String serverIp = (String) serverIpSpinner.getSelectedItem();

        showRunningUi(true);
        ioExecutor.execute(() -> controller.start(context, serverIp, tftpRoot, httpRoot, httpPort));
    }

    @Override
    public void onStateChanged(String logText, boolean running) {
        runOnUiThread(() -> {
            stopButton.setEnabled(running);
            logView.setText(logText);
            logScroll.fullScroll(View.FOCUS_DOWN);
            var delay = running || logText.isEmpty() ? 0 : 3000;
            logView.postDelayed(() -> showRunningUi(running), delay);
        });
    }

    private void showRunningUi(boolean running) {
        configContainer.setVisibility(running ? View.GONE : View.VISIBLE);
        runningContainer.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private void ensureAssetTree(AssetManager assetManager, String assetPath) throws IOException {
        var children = assetManager.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetIfMissing(assetManager, assetPath);
            return;
        }

        for (var child : children) {
            String childPath = assetPath.isEmpty() ? child : assetPath + "/" + child;
            ensureAssetTree(assetManager, childPath);
        }
    }

    private void copyAssetIfMissing(AssetManager assetManager, String assetPath) throws IOException {
        if (!assetPath.endsWith(".efi") &&
            !assetPath.endsWith(".ipxe") &&
            !assetPath.endsWith(".kpxe")) {
            return;
        }
        var target = new File(tftpRootDir, assetPath);
        if (target.exists()) return;

        var parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent);
        }

        try (var input = assetManager.open(assetPath);
             var output = new FileOutputStream(target)) {
            var buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private List<String> findLocalIpv4() {
        var ipv4Addresses = new ArrayList<String>();

        var cm = getSystemService(ConnectivityManager.class);
        for (var network : cm.getAllNetworks()) {
            var properties = cm.getLinkProperties(network);
            if (properties == null) continue;
            for (var linkAddress : properties.getLinkAddresses()) {
                var address = linkAddress.getAddress();
                if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) {
                    ipv4Addresses.add(ipv4.getHostAddress());
                }
            }
        }
        return ipv4Addresses;
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
