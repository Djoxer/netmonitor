package dev.djoxer.netmonitor.vpn;

public class ConnectionInfo {

    public final String protocol;
    public final String destIp;   // remote peer IP
    public final int destPort;    // remote peer port
    public final int srcPort;     // local port
    public final long timestamp;

    public int uid = -1;
    public String packageName = null;
    public String appName = null;
    public String hostname = null;

    public int packetCount = 1;
    public long bytesOut = 0;
    public long bytesIn = 0;

    public boolean seenOut = false;
    public boolean seenIn = false;

    public ConnectionInfo(String protocol, String destIp, int destPort, int srcPort) {
        this.protocol = protocol;
        this.destIp = destIp;
        this.destPort = destPort;
        this.srcPort = srcPort;
        this.timestamp = System.currentTimeMillis();
    }

    public String getKey() {
        return protocol + "|" + srcPort + "|" + destIp + "|" + destPort;
    }

    public long totalBytes() {
        return bytesOut + bytesIn;
    }

    private String directionArrow() {
        if (seenOut && seenIn) return "↕";
        if (seenOut) return "↑";
        if (seenIn) return "↓";
        return "·";
    }

    @Override
    public String toString() {
        String appPart;
        if (appName != null && !appName.isEmpty()) {
            appPart = appName;
        } else if (packageName != null) {
            appPart = packageName;
        } else if (uid > 0) {
            appPart = friendlyUid(uid);
        } else {
            appPart = "unknown";
        }

        String dest = (hostname != null && !hostname.isEmpty()) ? hostname : destIp;

        return directionArrow() + " " + appPart
                + "  →  " + protocol + " " + dest + ":" + destPort
                + "  (↑" + formatBytes(bytesOut) + " ↓" + formatBytes(bytesIn)
                + ", " + packetCount + " pkts)";
    }

    private static String friendlyUid(int uid) {
        switch (uid) {
            case 0: return "root";
            case 1000: return "system";
            case 1001: return "radio";
            case 2000: return "shell";
            case 1051: return "dns_tether";
            default:
                if (uid >= 10000) return "app:" + uid;
                return "uid:" + uid;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }
}
