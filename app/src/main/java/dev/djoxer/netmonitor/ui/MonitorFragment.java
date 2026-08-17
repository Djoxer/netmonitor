package dev.djoxer.netmonitor.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MonitorFragment extends Fragment {

    private static final int REQUEST_VPN = 1001;

    private TextView statusText;
    private Switch switchBlockMode;
    private AppTileAdapter outAdapter;
    private AppTileAdapter inAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private boolean pendingBlockMode;
    private RuleRepository ruleRepository;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_monitor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ruleRepository = new RuleRepository(requireContext());

        statusText = view.findViewById(R.id.statusText);
        switchBlockMode = view.findViewById(R.id.switchBlockMode);
        Button btnStart = view.findViewById(R.id.btnStart);
        Button btnStop = view.findViewById(R.id.btnStop);
        Button btnClear = view.findViewById(R.id.btnClear);

        RecyclerView recyclerOut = view.findViewById(R.id.recyclerOut);
        RecyclerView recyclerIn = view.findViewById(R.id.recyclerIn);

        outAdapter = new AppTileAdapter(AppTileAdapter.Mode.OUT, this::showAppDialog);
        inAdapter = new AppTileAdapter(AppTileAdapter.Mode.IN, this::showAppDialog);

        recyclerOut.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerIn.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerOut.setAdapter(outAdapter);
        recyclerIn.setAdapter(inAdapter);

        btnStart.setOnClickListener(v -> prepareAndStartVpn());
        btnStop.setOnClickListener(v -> stopVpn());
        btnClear.setOnClickListener(v -> {
            NetVpnService.clearConnections();
            refreshLists();
        });

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshLists();
                handler.postDelayed(this, 1500);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void refreshLists() {
        List<ConnectionInfo> all = NetVpnService.getConnections();
        Map<String, AppGroup> outMap = new HashMap<>();
        Map<String, AppGroup> inMap = new HashMap<>();

        PackageManager pm = requireContext().getPackageManager();

        for (ConnectionInfo c : all) {
            String key = groupKey(c);
            if (c.seenOut) {
                AppGroup g = outMap.get(key);
                if (g == null) {
                    g = buildGroup(key, c, pm);
                    outMap.put(key, g);
                }
                g.bytesOut += c.bytesOut;
                g.connCount++;
                g.connections.add(c);
            }
            if (c.seenIn) {
                AppGroup g = inMap.get(key);
                if (g == null) {
                    g = buildGroup(key, c, pm);
                    inMap.put(key, g);
                }
                g.bytesIn += c.bytesIn;
                g.connCount++;
                g.connections.add(c);
            }
        }

        outAdapter.submit(new ArrayList<>(outMap.values()));
        inAdapter.submit(new ArrayList<>(inMap.values()));
    }

    private String groupKey(ConnectionInfo c) {
        if (c.packageName != null) return c.packageName;
        if (c.uid > 0) return "uid:" + c.uid;
        return "unknown";
    }

    private AppGroup buildGroup(String key, ConnectionInfo c, PackageManager pm) {
        AppGroup g = new AppGroup(key);
        g.packageName = c.packageName;
        g.uid = c.uid;
        g.displayName = c.appName != null ? c.appName
                : (c.packageName != null ? c.packageName : key);

        String blockKey = c.packageName != null ? c.packageName
                : (c.uid > 0 ? "uid:" + c.uid : key);
        g.blocked = BlockManager.getInstance().shouldBlockPackage(blockKey)
                || (c.uid > 0 && BlockManager.getInstance().shouldBlock(c.uid));

        if (c.packageName != null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(c.packageName, 0);
                g.icon = pm.getApplicationIcon(ai);
            } catch (Exception ignored) {
            }
        }
        return g;
    }

    private void showAppDialog(AppGroup group) {
        AppDetailDialog.newInstance(group)
                .show(getParentFragmentManager(), "app_detail");
    }

    private void prepareAndStartVpn() {
        pendingBlockMode = switchBlockMode.isChecked();
        Intent prepare = VpnService.prepare(requireContext());
        if (prepare != null) {
            startActivityForResult(prepare, REQUEST_VPN);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(requireContext(), NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_START);
        intent.putExtra(NetVpnService.EXTRA_BLOCK_MODE, pendingBlockMode);
        requireContext().startForegroundService(intent);
        statusText.setText(pendingBlockMode
                ? "Status: BLOCK mode"
                : "Status: FORWARD mode");
        statusText.setTextColor(pendingBlockMode ? 0xFFFF5722 : 0xFF4CAF50);
    }

    private void stopVpn() {
        Intent intent = new Intent(requireContext(), NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_STOP);
        requireContext().startService(intent);
        statusText.setText("Status: stopped");
        statusText.setTextColor(0xFFB0B0B0);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == android.app.Activity.RESULT_OK) {
            startVpnService();
        } else if (requestCode == REQUEST_VPN) {
            statusText.setText("Status: permission denied");
            statusText.setTextColor(0xFFFF0000);
        }
    }
}
