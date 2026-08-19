package dev.djoxer.netmonitor.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
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
import java.util.Locale;
import java.util.Map;

import dev.djoxer.netmonitor.MainActivity;
import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.network.NetworkStatusHelper;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.ConnectionTracker;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MonitorFragment extends Fragment {

    private static final int REQUEST_VPN = 1001;

    private TextView networkInfoText;
    private TextView ipAddressText;
    private TextView speedText;
    private TextView ipStatsText;
    private Switch switchBlockMode;
    private AppTileAdapter outAdapter;
    private AppTileAdapter inAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private boolean pendingBlockMode;
    private NetworkStatusHelper networkStatusHelper;

    // Live speed: delta of tracked VPN bytes
    private long lastSampleBytesOut = -1;
    private long lastSampleBytesIn = -1;
    private long lastSampleTimeMs = 0;
    private ProgressBar barIpv4;
    private ProgressBar barIpv6;
    private String appFilter = "";
    private EditText appSearch;

    // tiny holder to avoid import cycle issues if RuleRepository path differs
    private dev.djoxer.netmonitor.data.RuleRepository ruleRepository;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_monitor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ruleRepository = new dev.djoxer.netmonitor.data.RuleRepository(requireContext());
        networkStatusHelper = new NetworkStatusHelper(requireContext());

        networkInfoText = view.findViewById(R.id.networkInfoText);
        ipAddressText = view.findViewById(R.id.ipAddressText);
        speedText = view.findViewById(R.id.speedText);
        ipStatsText = view.findViewById(R.id.ipStatsText);
        switchBlockMode = view.findViewById(R.id.switchBlockMode);
        Button btnStart = view.findViewById(R.id.btnStart);
        Button btnStop = view.findViewById(R.id.btnStop);
        Button btnClear = view.findViewById(R.id.btnClear);

        RecyclerView recyclerOut = view.findViewById(R.id.recyclerOut);
        RecyclerView recyclerIn = view.findViewById(R.id.recyclerIn);

        outAdapter = new AppTileAdapter(AppTileAdapter.Mode.OUT, this::showAppDialog);
        inAdapter = new AppTileAdapter(AppTileAdapter.Mode.IN, this::showAppDialog);

        barIpv4 = view.findViewById(R.id.barIpv4);
        barIpv6 = view.findViewById(R.id.barIpv6);

        appSearch = view.findViewById(R.id.appSearch);
        appSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                appFilter = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                refreshLists();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        recyclerOut.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerIn.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerOut.setAdapter(outAdapter);
        recyclerIn.setAdapter(inAdapter);

        btnStart.setOnClickListener(v -> prepareAndStartVpn());
        btnStop.setOnClickListener(v -> stopVpn());
        btnClear.setOnClickListener(v -> {
            NetVpnService.clearConnections();
            lastSampleBytesOut = -1;
            lastSampleBytesIn = -1;
            lastSampleTimeMs = 0;
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
        updateNetworkInfo();
        updateIpStats();
        updateSpeed();

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

        outAdapter.submit(filterGroups(new ArrayList<>(outMap.values())));
        inAdapter.submit(filterGroups(new ArrayList<>(inMap.values())));
    }

    private List<AppGroup> filterGroups(List<AppGroup> src) {
        if (appFilter == null || appFilter.isEmpty()) return src;
        List<AppGroup> out = new ArrayList<>();
        for (AppGroup g : src) {
            String name = g.displayName != null ? g.displayName.toLowerCase(Locale.US) : "";
            String pkg = g.packageName != null ? g.packageName.toLowerCase(Locale.US) : "";
            if (name.contains(appFilter) || pkg.contains(appFilter) || g.key.toLowerCase(Locale.US).contains(appFilter)) {
                out.add(g);
            }
        }
        return out;
    }

    private void updateNetworkInfo() {
        if (networkInfoText == null || ipAddressText == null) return;
        NetworkStatusHelper.Snapshot snap = networkStatusHelper.snapshot();

        networkInfoText.setText(String.format(Locale.US,
                "Network: %s · %s", snap.networkType, snap.provider));

        StringBuilder ips = new StringBuilder("IPs: ");
        boolean any = false;
        if (!snap.ipv4.isEmpty()) {
            ips.append("v4 ").append(join(snap.ipv4, ", "));
            any = true;
        }
        if (!snap.ipv6.isEmpty()) {
            if (any) ips.append("  ·  ");
            ips.append("v6 ").append(join(snap.ipv6, ", "));
            any = true;
        }
        if (!any) ips.append("–");
        ipAddressText.setText(ips.toString());
    }

    private void updateSpeed() {
        if (speedText == null) return;

        long totalOut = 0;
        long totalIn = 0;
        for (ConnectionInfo c : NetVpnService.getConnections()) {
            totalOut += c.bytesOut;
            totalIn += c.bytesIn;
        }

        long now = System.currentTimeMillis();
        if (lastSampleTimeMs <= 0 || lastSampleBytesOut < 0) {
            lastSampleBytesOut = totalOut;
            lastSampleBytesIn = totalIn;
            lastSampleTimeMs = now;
            speedText.setText("↑ 0 B/s   ↓ 0 B/s");
            return;
        }

        double seconds = (now - lastSampleTimeMs) / 1000.0;
        if (seconds < 0.3) return;

        long dOut = Math.max(0, totalOut - lastSampleBytesOut);
        long dIn = Math.max(0, totalIn - lastSampleBytesIn);
        long upBps = (long) (dOut / seconds);
        long downBps = (long) (dIn / seconds);

        lastSampleBytesOut = totalOut;
        lastSampleBytesIn = totalIn;
        lastSampleTimeMs = now;

        speedText.setText(String.format(Locale.US,
                "↑ %s/s   ↓ %s/s",
                formatRate(upBps),
                formatRate(downBps)));
    }

    private void updateIpStats() {
        if (ipStatsText == null) return;
        ConnectionTracker t = NetVpnService.getTracker();
        long v4 = t.bytesIpv4.get();
        long v6 = t.bytesIpv6.get();
        long sum = v4 + v6;
        if (sum <= 0) {
            ipStatsText.setText("IPv4 / IPv6: no traffic yet");
            if (barIpv4 != null) barIpv4.setProgress(0);
            if (barIpv6 != null) barIpv6.setProgress(0);
        } else {
            int pct6 = (int) Math.round(100.0 * v6 / sum);
            int pct4 = 100 - pct6;
            ipStatsText.setText(String.format(
                    Locale.US,
                    "IPv6 share: %d%%   (v4 %s · v6 %s)",
                    pct6,
                    formatBytes(v4),
                    formatBytes(v6)));
            if (barIpv4 != null) barIpv4.setProgress(pct4);
            if (barIpv6 != null) barIpv6.setProgress(pct6);
        }
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
            if (i >= 2 && list.size() > 3) {
                sb.append(sep).append("…");
                break;
            }
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }

    private static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B";
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.2f MB", bytesPerSec / (1024.0 * 1024.0));
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

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setVpnStatus(
                    pendingBlockMode ? MainActivity.STATUS_BLOCK : MainActivity.STATUS_FORWARD);
        }

        lastSampleBytesOut = -1;
        lastSampleBytesIn = -1;
        lastSampleTimeMs = 0;
    }

    private void stopVpn() {
        Intent intent = new Intent(requireContext(), NetVpnService.class);
        intent.setAction(NetVpnService.ACTION_STOP);
        requireContext().startService(intent);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setVpnStatus(MainActivity.STATUS_STOPPED);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == android.app.Activity.RESULT_OK) {
            startVpnService();
        } else if (requestCode == REQUEST_VPN) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).setVpnStatus(MainActivity.STATUS_STOPPED);
            }
        }
    }
}
