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
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.network.NetworkStatusHelper;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.ConnectionTracker;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MonitorFragment extends Fragment {

    private static final int REQUEST_VPN = 1001;

    private Switch switchBlockMode;
    private TextView terminalText;
    private ScrollView terminalScroll;
    private TextView labelIpv4;
    private TextView labelIpv6;
    private View barPartV4;
    private View barPartV6;
    private EditText appSearch;

    private AppTileAdapter outAdapter;
    private AppTileAdapter inAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private boolean pendingBlockMode;

    private RuleRepository ruleRepository;
    private NetworkStatusHelper networkStatusHelper;

    private String appFilter = "";

    // Live speed
    private long lastSampleBytesOut = -1;
    private long lastSampleBytesIn = -1;
    private long lastSampleTimeMs = 0;
    private String lastRateLine = "up 0 B/s  down 0 B/s";

    // Terminal prompt blink
    private boolean promptVisible = true;

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
        networkStatusHelper = new NetworkStatusHelper(requireContext());

        switchBlockMode = view.findViewById(R.id.switchBlockMode);
        terminalText = view.findViewById(R.id.terminalText);
        terminalScroll = view.findViewById(R.id.terminalScroll);
        labelIpv4 = view.findViewById(R.id.labelIpv4);
        labelIpv6 = view.findViewById(R.id.labelIpv6);
        barPartV4 = view.findViewById(R.id.barPartV4);
        barPartV6 = view.findViewById(R.id.barPartV6);
        appSearch = view.findViewById(R.id.appSearch);

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
            lastSampleBytesOut = -1;
            lastSampleBytesIn = -1;
            lastSampleTimeMs = 0;
            lastRateLine = "up 0 B/s  down 0 B/s";
            refreshLists();
        });

        if (appSearch != null) {
            appSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    appFilter = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                    refreshLists();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                promptVisible = !promptVisible;
                refreshLists();
                handler.postDelayed(this, 1500);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncVpnStatusFromService();
        }
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void refreshLists() {
        updateSpeedSample();
        updateTerminal();
        updateIpBars();

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

    // ------------------------------------------------------------------
    // Terminal console
    // ------------------------------------------------------------------

    private static String ascii(String s) {
        if (s == null) return "";
        return s
                .replace('\u2011', '-') // non-breaking hyphen
                .replace('\u2010', '-')
                .replace('\u2013', '-') // en-dash
                .replace('\u2014', '-') // em-dash
                .replace('\u00A0', ' '); // nbsp
    }

    private void updateTerminal() {
        if (terminalText == null) return;

        NetworkStatusHelper.Snapshot snap = networkStatusHelper.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("+-- netmonitor console ---------------------------+\n");
        String netLine = ascii(snap.networkType + " / " + snap.provider);
        sb.append("| net : ").append(pad(netLine, 42)).append("|\n");

        if (snap.ipv4.isEmpty()) {
            sb.append("| IPv4: ").append(pad("–", 42)).append("|\n");
        } else {
            for (int i = 0; i < snap.ipv4.size(); i++) {
                String label = (i == 0) ? "IPv4: " : "      ";
                sb.append("| ").append(label).append(pad(snap.ipv4.get(i), 42)).append("|\n");
            }
        }

        if (snap.ipv6.isEmpty()) {
            sb.append("| IPv6: ").append(pad("–", 42)).append("|\n");
        } else {
            for (int i = 0; i < snap.ipv6.size(); i++) {
                String label = (i == 0) ? "IPv6: " : "      ";
                sb.append("| ").append(label).append(pad(snap.ipv6.get(i), 42)).append("|\n");
            }
        }

        sb.append("| rate: ").append(pad(lastRateLine, 42)).append("|\n");
        sb.append("+-------------------------------------------------+\n");
        sb.append(promptVisible ? "netmonitor@device:~$ _" : "netmonitor@device:~$  ");

        terminalText.setText(sb.toString());
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() > width) return s.substring(0, width);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    // ------------------------------------------------------------------
    // Speed
    // ------------------------------------------------------------------

    private void updateSpeedSample() {
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
            lastRateLine = "up 0 B/s  down 0 B/s";
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

        lastRateLine = "up " + formatRate(upBps) + "/s  down " + formatRate(downBps) + "/s";
    }

    // ------------------------------------------------------------------
    // Fused IPv4 / IPv6 bar
    // ------------------------------------------------------------------

    private void updateIpBars() {
        ConnectionTracker t = NetVpnService.getTracker();
        long v4 = t.bytesIpv4.get();
        long v6 = t.bytesIpv6.get();
        long sum = v4 + v6;

        if (labelIpv4 != null) {
            labelIpv4.setText("IPv4 (" + formatBytes(v4) + ")");
        }
        if (labelIpv6 != null) {
            labelIpv6.setText("(" + formatBytes(v6) + ") IPv6");
        }

        if (barPartV4 == null || barPartV6 == null) return;

        LinearLayout.LayoutParams p4 =
                (LinearLayout.LayoutParams) barPartV4.getLayoutParams();
        LinearLayout.LayoutParams p6 =
                (LinearLayout.LayoutParams) barPartV6.getLayoutParams();

        if (sum <= 0) {
            p4.weight = 1f;
            p6.weight = 0f;
        } else {
            p4.weight = (float) v4;
            p6.weight = (float) v6;
        }
        barPartV4.setLayoutParams(p4);
        barPartV6.setLayoutParams(p6);
    }

    // ------------------------------------------------------------------
    // Groups / filter
    // ------------------------------------------------------------------

    private List<AppGroup> filterGroups(List<AppGroup> src) {
        if (appFilter == null || appFilter.isEmpty()) return src;
        List<AppGroup> out = new ArrayList<>();
        for (AppGroup g : src) {
            String name = g.displayName != null ? g.displayName.toLowerCase(Locale.US) : "";
            String pkg = g.packageName != null ? g.packageName.toLowerCase(Locale.US) : "";
            String key = g.key != null ? g.key.toLowerCase(Locale.US) : "";
            if (name.contains(appFilter) || pkg.contains(appFilter) || key.contains(appFilter)) {
                out.add(g);
            }
        }
        return out;
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

    // ------------------------------------------------------------------
    // VPN control
    // ------------------------------------------------------------------

    private void prepareAndStartVpn() {
        pendingBlockMode = switchBlockMode != null && switchBlockMode.isChecked();
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

    // ------------------------------------------------------------------
    // Format helpers
    // ------------------------------------------------------------------

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
}
