package dev.djoxer.netmonitor.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import dev.djoxer.netmonitor.data.TrafficSampler;
import dev.djoxer.netmonitor.network.NetworkStatusHelper;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.ConnectionTracker;
import dev.djoxer.netmonitor.vpn.NetVpnService;

public class MonitorFragment extends Fragment {

    private static final int REQUEST_VPN = 1001;
    private static final int CONSOLE_WIDTH = 46;

    private String deviceHostLabel = "device";
    private ImageButton btnStart;
    private ImageButton btnStop;
    private ImageButton btnClear;
    private ImageButton btnBlockMode;

    private TextView labelIpv4;
    private TextView labelIpv6;
    private View barPartV4;
    private View barPartV6;
    private View chartContainer;
    private TrafficChartView trafficChart;
    private long lastChartLoadMs = 0;

    private TextView terminalText;
    private boolean promptCursorOn = false;

    private AppTileAdapter outAdapter;
    private AppTileAdapter inAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    private boolean pendingBlockMode;
    private boolean blockModeSelected;
    private String appFilter = "";

    private NetworkStatusHelper networkStatusHelper;

    private long lastSampleBytesOut = -1;
    private long lastSampleBytesIn = -1;
    private long lastSampleTimeMs = 0;

    private long lastUpBps = 0;
    private long lastDownBps = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_monitor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        networkStatusHelper = new NetworkStatusHelper(requireContext());
        resolveDeviceHostName();

        btnStart = view.findViewById(R.id.btnStart);
        btnStop = view.findViewById(R.id.btnStop);
        btnClear = view.findViewById(R.id.btnClear);
        btnBlockMode = view.findViewById(R.id.btnBlockMode);

        labelIpv4 = view.findViewById(R.id.labelIpv4);
        labelIpv6 = view.findViewById(R.id.labelIpv6);
        barPartV4 = view.findViewById(R.id.barPartV4);
        barPartV6 = view.findViewById(R.id.barPartV6);
        chartContainer = view.findViewById(R.id.chartContainer);
        terminalText = view.findViewById(R.id.terminalText);
        trafficChart = view.findViewById(R.id.trafficChart);
        if (chartContainer != null) chartContainer.setVisibility(View.VISIBLE);

        EditText appSearch = view.findViewById(R.id.appSearch);
        RecyclerView recyclerOut = view.findViewById(R.id.recyclerOut);
        RecyclerView recyclerIn = view.findViewById(R.id.recyclerIn);

        outAdapter = new AppTileAdapter(AppTileAdapter.Mode.OUT, this::showAppDialog);
        inAdapter = new AppTileAdapter(AppTileAdapter.Mode.IN, this::showAppDialog);
        recyclerOut.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerIn.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerOut.setAdapter(outAdapter);
        recyclerIn.setAdapter(inAdapter);

        blockModeSelected = false;
        updateBlockButtonUi();

        btnStart.setOnClickListener(v -> prepareAndStartVpn());
        btnStop.setOnClickListener(v -> stopVpn());
        btnClear.setOnClickListener(v -> {
            NetVpnService.clearConnections();
            lastSampleBytesOut = -1;
            lastSampleBytesIn = -1;
            lastSampleTimeMs = 0;
            lastUpBps = 0;
            lastDownBps = 0;
            refreshLists();
        });
        btnBlockMode.setOnClickListener(v -> {
            blockModeSelected = !blockModeSelected;
            updateBlockButtonUi();

            if (NetVpnService.isServiceRunning()) {
                NetVpnService.setBlockModeLive(blockModeSelected);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).setVpnStatus(
                            blockModeSelected
                                    ? MainActivity.STATUS_BLOCK
                                    : MainActivity.STATUS_FORWARD);
                }
            }
            // Console reflects new state on next refresh (~1.5s)
            updateConsoleStatus();
        });

        if (appSearch != null) {
            appSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    appFilter = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                    refreshLists();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        writeBootConsole();

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshLists();
                handler.postDelayed(this, 1500);
            }
        };
    }

    private void resolveDeviceHostName() {
        String name = null;
        try {
            name = Settings.Global.getString(
                    requireContext().getContentResolver(),
                    Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = android.os.Build.MODEL;
        }
        if (name == null || name.trim().isEmpty()) {
            name = "device";
        }
        // Console is ASCII-only; keep prompt width stable
        name = ascii(name.trim()).replace(' ', '-');
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        deviceHostLabel = name;
    }

    private void updateBlockButtonUi() {
        if (btnBlockMode == null) return;
        btnBlockMode.setAlpha(blockModeSelected ? 1f : 0.4f);
        btnBlockMode.setSelected(blockModeSelected);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).syncVpnStatusFromService();
        }
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void refreshLists() {
        updateSpeedSample();
        updateIpStats();
        updateConsoleStatus();

        long now = System.currentTimeMillis();
        if (trafficChart != null && now - lastChartLoadMs > 15_000L) {
            lastChartLoadMs = now;
            TrafficSampler.getInstance().loadLast24h(requireContext(), samples -> {
                if (getActivity() == null || trafficChart == null) return;
                getActivity().runOnUiThread(() -> trafficChart.setSamples(samples));
            });
        }

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

        // Pin bypass apps so they stay editable without traffic
        for (String pkg : BlockManager.getInstance().getBypassPackages()) {
            if (!outMap.containsKey(pkg)) {
                outMap.put(pkg, buildBypassPinGroup(pkg, pm));
            } else {
                outMap.get(pkg).bypass = true;
            }
            if (!inMap.containsKey(pkg)) {
                inMap.put(pkg, buildBypassPinGroup(pkg, pm));
            } else {
                inMap.get(pkg).bypass = true;
            }
        }

        List<AppGroup> outList = filterGroups(new ArrayList<>(outMap.values()));
        List<AppGroup> inList = filterGroups(new ArrayList<>(inMap.values()));
        sortBypassFirst(outList);
        sortBypassFirst(inList);

        outAdapter.submit(outList);
        inAdapter.submit(inList);
    }

    private AppGroup buildBypassPinGroup(String packageName, PackageManager pm) {
        AppGroup g = new AppGroup(packageName);
        g.packageName = packageName;
        g.bypass = true;
        g.blockedOut = false;
        g.blockedIn = false;
        g.blocked = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            g.displayName = pm.getApplicationLabel(ai).toString();
            g.icon = pm.getApplicationIcon(ai);
            g.uid = ai.uid;
            BlockManager.getInstance().registerUid(ai.uid, packageName);
        } catch (Exception e) {
            g.displayName = packageName;
        }
        return g;
    }

    private void sortBypassFirst(List<AppGroup> list) {
        list.sort((a, b) -> {
            if (a.bypass == b.bypass) {
                String na = a.displayName != null ? a.displayName : "";
                String nb = b.displayName != null ? b.displayName : "";
                return na.compareToIgnoreCase(nb);
            }
            return a.bypass ? -1 : 1;
        });
    }

    // ------------------------------------------------------------------
    // Console
    // ------------------------------------------------------------------

    private void writeBootConsole() {
        if (terminalText == null) return;
        terminalText.setText(buildConsoleBody(
                "net  -",
                "v4   -",
                "v6   -",
                "spd  up 0B/s  dn 0B/s",
                "vpn  stopped"
        ));
    }

    private void updateConsoleStatus() {
        promptCursorOn = !promptCursorOn;

        if (terminalText == null || networkStatusHelper == null) return;

        NetworkStatusHelper.Snapshot snap = networkStatusHelper.snapshot();

        // ASCII only – never Wi‑Fi special hyphen (U+2011) or middle dots
        String netType = ascii(snap.networkType);
        String provider = ascii(snap.provider);

        // Prefer one global IPv6 only (counterpart to one IPv4); ignore extra v6
        String v4 = snap.ipv4.isEmpty() ? "-" : snap.ipv4.get(0);
        String v6 = snap.ipv6.isEmpty() ? "-" : snap.ipv6.get(0);

        String netLine = "net  " + netType + " - " + provider;
        String ip4Line = "v4   " + v4;
        String ip6Line = "v6   " + v6;
        String spdLine = "spd  ^ " + formatRate(lastUpBps) + "/s  v " + formatRate(lastDownBps) + "/s";

        String vpnState;
        if (NetVpnService.isServiceRunning()) {
            vpnState = NetVpnService.isBlockMode() ? "VPN BLOCK" : "VPN FORWARD";
        } else {
            vpnState = "VPN stopped";
        }
        String vpnLine = "vpn  " + vpnState + (blockModeSelected ? " - block armed" : "");

        terminalText.setText(buildConsoleBody(netLine, ip4Line, ip6Line, spdLine, vpnLine));
    }

    private String buildConsoleBody(String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(boxLine("+", "-")).append('\n');
        for (String row : rows) {
            sb.append(padRow("| " + trimTo(ascii(row), CONSOLE_WIDTH - 2))).append('\n');
        }
        sb.append(boxLine("+", "-")).append('\n');
        if (promptCursorOn) {
            sb.append("netmonitor@").append(deviceHostLabel).append(":~$ _");
        } else {
            sb.append("netmonitor@").append(deviceHostLabel).append(":~$ ");
        }
        return sb.toString();
    }

    private static String ascii(String s) {
        if (s == null) return "-";
        return s
                .replace('\u2011', '-') // Wi-Fi non-breaking hyphen
                .replace('\u2010', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('\u00A0', ' ')
                .replace('·', '-')
                .replace('•', '-')
                .replace('↑', '^')
                .replace('↓', 'v');
    }

    private String boxLine(String corner, String fill) {
        StringBuilder sb = new StringBuilder(corner);
        for (int i = 0; i < CONSOLE_WIDTH - 2; i++) sb.append(fill);
        sb.append(corner);
        return sb.toString();
    }

    private String padRow(String content) {
        if (content.length() >= CONSOLE_WIDTH - 1) {
            return content.substring(0, CONSOLE_WIDTH - 1) + "|";
        }
        StringBuilder sb = new StringBuilder(content);
        while (sb.length() < CONSOLE_WIDTH - 1) sb.append(' ');
        sb.append('|');
        return sb.toString();
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String joinLimited(List<String> list, int maxItems) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), maxItems);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        if (list.size() > maxItems) sb.append("…");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Speed + IP share
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
            lastUpBps = 0;
            lastDownBps = 0;
            return;
        }

        double seconds = (now - lastSampleTimeMs) / 1000.0;
        if (seconds < 0.3) return;

        lastUpBps = (long) (Math.max(0, totalOut - lastSampleBytesOut) / seconds);
        lastDownBps = (long) (Math.max(0, totalIn - lastSampleBytesIn) / seconds);

        lastSampleBytesOut = totalOut;
        lastSampleBytesIn = totalIn;
        lastSampleTimeMs = now;
    }

    private void updateIpStats() {
        ConnectionTracker t = NetVpnService.getTracker();
        long v4 = t.bytesIpv4.get();
        long v6 = t.bytesIpv6.get();
        long sum = v4 + v6;

        if (labelIpv4 != null) {
            labelIpv4.setText(String.format(Locale.US, "IPv4 (%s)", formatBytes(v4)));
        }
        if (labelIpv6 != null) {
            labelIpv6.setText(String.format(Locale.US, "(%s) IPv6", formatBytes(v6)));
        }

        if (barPartV4 != null && barPartV6 != null) {
            LinearLayout.LayoutParams lp4 = (LinearLayout.LayoutParams) barPartV4.getLayoutParams();
            LinearLayout.LayoutParams lp6 = (LinearLayout.LayoutParams) barPartV6.getLayoutParams();
            if (sum <= 0) {
                lp4.weight = 1f;
                lp6.weight = 0f;
            } else {
                lp4.weight = (float) v4;
                lp6.weight = (float) v6;
            }
            barPartV4.setLayoutParams(lp4);
            barPartV6.setLayoutParams(lp6);
        }
    }

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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }

    private static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + "B";
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1fK", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.2fM", bytesPerSec / (1024.0 * 1024.0));
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

        BlockManager.AppRule rule = BlockManager.getInstance().getRule(blockKey);
        boolean bypass = (rule != null && rule.bypass)
                || (c.uid > 0 && BlockManager.getInstance().isBypassUid(c.uid));
        boolean allowed = rule != null && rule.allowed;

        boolean out = BlockManager.getInstance().shouldBlockOutPackage(blockKey)
                || (c.uid > 0 && BlockManager.getInstance().shouldBlockOut(c.uid));
        boolean in = BlockManager.getInstance().shouldBlockInPackage(blockKey)
                || (c.uid > 0 && BlockManager.getInstance().shouldBlockIn(c.uid));

        // Global VPN block mode → treat all non-bypass as blocked both ways
        boolean globalBlock = NetVpnService.isBlockMode();
        if (globalBlock && !bypass) {
            out = true;
            in = true;
        }

        g.bypass = bypass;
        g.allowed = allowed && !globalBlock;
        g.blockedOut = !bypass && (globalBlock || (!allowed && out));
        g.blockedIn = !bypass && (globalBlock || (!allowed && in));
        g.blocked = g.blockedOut || g.blockedIn;

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
        pendingBlockMode = blockModeSelected;
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
            MainActivity act = (MainActivity) getActivity();
            act.markVpnStartPending();
            act.setVpnStatus(
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
