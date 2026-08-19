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
