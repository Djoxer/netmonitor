package dev.djoxer.netmonitor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN = 1001;

    private TextView statusView;
    private TextView connectionsView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = new TextView(this);
        title.setText("NetMonitor");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Status: stopped");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setPadding(0, 0, 0, 16);
        root.addView(statusView);

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

        // Auto-refresh every 1.5 seconds
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionsList();
                handler.postDelayed(this, 1500);
            }
        };
        handler.post(refreshRunnable);
    }

    private void updateConnectionsList() {
        List<ConnectionInfo> list = NetVpnService.getConnections();
        if (list.isEmpty()) {
            connectionsView.setText("No connections yet.\nStart monitoring and use some apps.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Connections (").append(list.size()).append("):\n\n");

        // Show newest first (LinkedHashMap access-order)
        for (int i = list.size() - 1; i >= 0; i--) {
            sb.append(list.get(i).toString()).append("\n");
        }
        connectionsView.setText(sb.toString());
    }

    private void prepareAndStartVpn() {
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
        startForegroundService(intent);
        statusView.setText("Status: running");
        statusView.setTextColor(Color.parseColor("#4CAF50"));
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