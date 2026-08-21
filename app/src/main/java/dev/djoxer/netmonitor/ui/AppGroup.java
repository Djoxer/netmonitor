package dev.djoxer.netmonitor.ui;

import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

import dev.djoxer.netmonitor.vpn.ConnectionInfo;

public class AppGroup {

    public final String key;
    public String displayName;
    public String packageName;
    public int uid = -1;
    public Drawable icon;

    public long bytesOut;
    public long bytesIn;
    public int connCount;

    public boolean blocked;
    public boolean blockedOut;
    public boolean blockedIn;
    public boolean bypass;
    public boolean allowed;

    public final List<ConnectionInfo> connections = new ArrayList<>();

    public AppGroup(String key) {
        this.key = key;
        this.displayName = key;
    }
}
