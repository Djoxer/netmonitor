package dev.djoxer.netmonitor.ui;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dev.djoxer.netmonitor.R;

public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView about = view.findViewById(R.id.aboutText);

        String version = "?";
        try {
            PackageInfo p = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            version = p.versionName != null ? p.versionName : "?";
        } catch (Exception ignored) {
        }

        about.setText(
                "NetMonitor v" + version + "\n\n"
                        + "Non-root traffic monitor via local VpnService.\n"
                        + "• Live connections grouped by app\n"
                        + "• DNS / SNI hostnames (IPv4 + IPv6)\n"
                        + "• Global and per-app blocking + schedules\n"
                        + "• Async event log\n\n"
                        + "Package: dev.djoxer.netmonitor\n"
                        + "https://djoxer.dev\n\n"
                        + "────────────────────────\n"
                        + "Version history\n"
                        + "────────────────────────\n\n"
                        + HISTORY
        );
    }

    /**
     * Patch notes – prepend new releases at the top when shipping.
     */
    private static final String HISTORY = """
            \
            v1.12.3
            • Settings: block-mode switch, compact profile dropdown
            • Per-profile delete / Default reset icons
            
            v1.12.2
            • Tiles and console speed count forwarded traffic only
            • Blocked attempts no longer show fake data movement
            
            v1.12.1
            • Tab icons only; About/Settings order swap
            • Header theme toggle + centered session timer
            • Fix theme-switch crash on Settings/About
            • VPN status icon stays right-aligned
            
            v1.12.0
            • Profiles with blacklist / whitelist modes
            • Settings profile dropdown (activate, add, delete, mode)
            • Allow toggle for whitelist; rules per active profile
            • Bypass apps pinned on monitor; badge polish
            • Global block reflects BLOCK on tiles
            
            v1.11.0
            • Directional block (out / in independent)
            • Per-app VPN bypass (no monitor/log; needs restart)
            • Compact Out/In/Bypass toggles in app dialog
            • Tile badges for BLOCK and BYPASS
            • Header status icon fix on first VPN start
            • Console prompt shows device name
            
            v1.10.1
            • 24h samples recorded in VPN service (works in background)
            
            v1.10.0
            • Session runtime stopwatch in header while VPN is active
            • 24h chart polish (reset-safe deltas, fill, peak label)
            • Console text/background colors for light mode
            
            v1.9.0
            • 24h traffic chart from periodic samples
            • Room retention 24h; out/in rate lines on monitor
            
            v1.8.0
            • Icon toolbar (start/stop/clear/block) instead of text buttons
            • Console: ASCII borders, one IPv6, blinking prompt, fixed height
            • Global block toggles live while VPN is running
            • Placeholder for 24h traffic chart
            
            v1.7.1
            • Edge-to-edge layout with system bar insets (Android 15+)
            • Console line width reduced to avoid wrap artifacts
            • Button height/padding unified across Android versions
            • Header VPN status synced from service on resume
            
            v1.7.0
            • Terminal-style console on monitor (net, IPs, rate, prompt)
            • Fused IPv4/IPv6 share bar with byte labels
            • Light mode system bar icon contrast
            • VPN header status synced when returning to the app
            • ASCII-safe Wi-Fi strings for console alignment
            
            v1.6.0
            • Material 3 DayNight theme (light / dark / system)
            • Theme switch in Settings
            • Button style and spacing polish
            • Dialog button spacing for confirms and schedules
            • Settings tab stability fix
            
            v1.5.0
            • IPv4/IPv6 share as progress bars on monitor
            • App icons in log list
            • Search filter for app tiles and log events
            
            v1.4.0
            • Network type, provider, local IPv4/IPv6 on monitor
            • Live up/down speed from tracked traffic
            • IPv6 share stats; IPv6 VPN route only if device has IPv6
            • Header status icon (red stop / blinking play)
            • Log export as CSV or JSON
            
            v1.3.0
            • Export event log as CSV or JSON (share sheet)
            • App tiles: traffic only; connection count in detail dialog
            
            v1.2.1
            • App tiles show only traffic volume (no connection count)
            • Connection count moved into app detail dialog
            • About tab: scrollable patch notes / version history
            
            v1.2.0
            • IPv6 packet parse and TCP/UDP forwarding
            • AAAA DNS + SNI on IPv6
            • UDP RX buffer 2048; DNS session timeout 10s
            • Transport checksums for IPv4 and IPv6
            
            v1.1.0
            • UDP idle timeouts, max sessions, cleaner thread
            • Block key: package, uid:N, or unknown
            • Permanent block + schedules for unknown apps
            
            v1.0.0
            • First feature-complete release
            • Monitor tiles, log, settings, about
            • Global block, per-app block, schedules
            • Room log + rule persistence
            
            v0.13.0
            • Log direction IN on return path (RECV)
            
            v0.12.0
            • Tile stats by direction only
            • UID-based block rules
            
            v0.11.0
            • Inbound traffic counted from forwarder RX
            
            v0.10.0
            • App detail dialog with block schedules
            
            v0.9.0
            • Tab UI with app tiles and log
            
            v0.8.0
            • Per-app block engine, async log, Room
            
            v0.7.0
            • In/out direction markers and byte counters
            
            v0.6.0
            • DNS and TLS SNI hostnames
            
            v0.5.0
            • Reliable app name resolution
            
            v0.4.0
            • Modular VPN core, hardened TCP
            
            v0.3.0
            • Working TCP + UDP forwarding
            
            v0.2.0
            • UDP forwarding + global block mode
            
            v0.1.0
            • Initial project setup
            """;
}
