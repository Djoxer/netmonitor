package dev.djoxer.netmonitor.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of current device network: type, provider label, local IPs.
 */
public class NetworkStatusHelper {

    private static final String TAG = "NetworkStatusHelper";

    public static final class Snapshot {
        public String networkType = "None";      // Wi‑Fi, Mobile, …
        public String provider = "–";           // SSID or carrier or transport
        public final List<String> ipv4 = new ArrayList<>();
        public final List<String> ipv6 = new ArrayList<>();
    }

    private final Context appContext;
    private final ConnectivityManager cm;

    public NetworkStatusHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.cm = appContext.getSystemService(ConnectivityManager.class);
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        if (cm == null) return s;

        try {
            Network active = cm.getActiveNetwork();
            if (active == null) {
                s.networkType = "None";
                s.provider = "No active network";
                return s;
            }

            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            LinkProperties props = cm.getLinkProperties(active);

            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    s.networkType = "Wi‑Fi";
                    s.provider = wifiLabel(caps);
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    s.networkType = "Mobile";
                    s.provider = cellularLabel();
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    s.networkType = "Ethernet";
                    s.provider = "Ethernet";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    s.networkType = "VPN";
                    s.provider = "Underlying VPN/network";
                } else {
                    s.networkType = "Other";
                    s.provider = "Unknown transport";
                }
            }

            if (props != null) {
                for (LinkAddress la : props.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null) continue;
                    // strip zone id %wlan0
                    int zone = host.indexOf('%');
                    if (zone >= 0) host = host.substring(0, zone);

                    if (addr instanceof Inet4Address) {
                        s.ipv4.add(host);
                    } else if (addr instanceof Inet6Address) {
                        Inet6Address v6 = (Inet6Address) addr;
                        if (v6.isLinkLocalAddress()) continue;
                        s.ipv6.add(host);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "snapshot failed", e);
        }
        return s;
    }

    private String wifiLabel(NetworkCapabilities caps) {
        // SSID often needs location permission; avoid hard dependency
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Caps may expose owner UID etc.; SSID via WifiInfo needs extra perms
            }
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) appContext.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!ssid.equals("<unknown ssid>")) return ssid;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi label", e);
        }
        return "Wi‑Fi";
    }

    private String cellularLabel() {
        try {
            TelephonyManager tm = appContext.getSystemService(TelephonyManager.class);
            if (tm != null) {
                String name = tm.getNetworkOperatorName();
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cellular label", e);
        }
        return "Mobile network";
    }
}
