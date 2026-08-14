package dev.djoxer.netmonitor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN = 1001;

    private TextView statusView;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = new TextView(this);
        title.setText("NetMonitor");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        statusView = new TextView(this);
        statusView.setText("Status: VPN stopped");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setTextColor(Color.LTGRAY);
        statusView.setPadding(0, 0, 0, 48);
        layout.addView(statusView);

        startButton = new Button(this);
        startButton.setText("Start Monitoring");
        startButton.setOnClickListener(v -> prepareAndStartVpn());
        layout.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("Stop Monitoring");
        stopButton.setOnClickListener(v -> stopVpn());
        layout.addView(stopButton);

        setContentView(layout);
    }

    private void prepareAndStartVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            // User must confirm the VPN connection
            startActivityForResult(prepare, REQUEST_VPN);
        } else {
            // Already prepared
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_START);
        startForegroundService(intent);

        statusView.setText("Status: VPN starting / running");
        statusView.setTextColor(Color.parseColor("#4CAF50"));
    }

    private void stopVpn() {
        Intent intent = new Intent(this, NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_STOP);
        startService(intent);

        statusView.setText("Status: VPN stopped");
        statusView.setTextColor(Color.LTGRAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == REQUEST_VPN) {
            statusView.setText("Status: VPN permission denied");
            statusView.setTextColor(Color.RED);
        }
    }
}