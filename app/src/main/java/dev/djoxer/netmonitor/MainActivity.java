package dev.djoxer.netmonitor;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.ConnectionTracker;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN = 1001;

    private TextView statusView;
    private TextView connectionsView;
    private Switch blockSwitch;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    private boolean pendingBlockMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.parseColor("#121212"));

        // Title + version
        TextView title = new TextView(this);
        title.setText("NetMonitor  v" + getAppVersion());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Status: stopped");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setPadding(0, 0, 0, 16);
        root.addView(statusView);

        // Block mode switch
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setPadding(0, 0, 0, 16);

        TextView switchLabel = new TextView(this);
        switchLabel.setText("Block mode (no internet)");
        switchLabel.setTextColor(Color.LTGRAY);
        switchLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        switchRow.addView(switchLabel);

        blockSwitch = new Switch(this);
        blockSwitch.setPadding(24, 0, 0, 0);
        switchRow.addView(blockSwitch);
        root.addView(switchRow);

        // Buttons
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button startBtn = new Button(this);
        startBtn.setText("Start");
        startBtn.setOnClickListener(v -> prepareAndStartVpn());
        buttons.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setOnClickListener(v -> stopVpn());
        buttons.addView(stopBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("Clear");
        clearBtn.setOnClickListener(v -> {
            NetVpnService.clearConnections();
            updateConnectionsList();
        });
        buttons.addView(clearBtn);

        root.addView(buttons);

        connectionsView = new TextView(this);
        connectionsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        connectionsView.setTextColor(Color.LTGRAY);
        connectionsView.setTextIsSelectable(true);
        connectionsView.setPadding(0, 24, 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(connectionsView);
        root.addView(scroll);

        setContentView(root);

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionsList();
                handler.postDelayed(this, 1500);
            }
        };
        handler.post(refreshRunnable);
    }

    private String getAppVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private void updateConnectionsList() {
        StringBuilder sb = new StringBuilder();
        ConnectionTracker t = NetVpnService.getTracker();

        sb.append("UDP fwd: ").append(t.udpForwarded.get())
                .append(" | TCP fwd: ").append(t.tcpForwarded.get())
                .append(" | ClientPayloads: ").append(t.tcpClientPayloads.get())
                .append("\n")
                .append("SYN: ").append(t.tcpSynSeen.get())
                .append(" | Sessions: ").append(t.tcpSessionsCreated.get())
                .append(" | ConnectErr: ").append(t.tcpConnectErrors.get())
                .append(" | Hosts: ").append(t.getHostnameResolver().size())
                .append("\n\n");

        List<ConnectionInfo> list = NetVpnService.getConnections();
        if (list.isEmpty()) {
            sb.append("No connections yet.");
        } else {
            sb.append("Connections (").append(list.size()).append("):\n\n");
            for (int i = list.size() - 1; i >= 0; i--) {
                sb.append(list.get(i).toString()).append("\n");
            }
        }
        connectionsView.setText(sb.toString());
    }

    private void prepareAndStartVpn() {
        pendingBlockMode = blockSwitch.isChecked();
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQUEST_VPN);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_START);
        intent.putExtra(NetVpnService.EXTRA_BLOCK_MODE, pendingBlockMode);
        startForegroundService(intent);

        if (pendingBlockMode) {
            statusView.setText("Status: BLOCK mode (no internet)");
            statusView.setTextColor(Color.parseColor("#FF5722"));
        } else {
            statusView.setText("Status: FORWARD mode (UDP+TCP)");
            statusView.setTextColor(Color.parseColor("#4CAF50"));
        }
    }

    private void stopVpn() {
        Intent intent = new Intent(this, NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_STOP);
        startService(intent);
        statusView.setText("Status: stopped");
        statusView.setTextColor(Color.LTGRAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == REQUEST_VPN) {
            statusView.setText("Status: permission denied");
            statusView.setTextColor(Color.RED);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }
}