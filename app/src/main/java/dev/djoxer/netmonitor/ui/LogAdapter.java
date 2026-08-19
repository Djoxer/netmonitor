package dev.djoxer.netmonitor.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.entity.LogEventEntity;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    private final List<LogEventEntity> allItems = new ArrayList<>();
    private final List<LogEventEntity> visible = new ArrayList<>();
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private PackageManager packageManager;
    private String filter = "";

    public void setPackageManager(PackageManager pm) {
        this.packageManager = pm;
    }

    public void submit(List<LogEventEntity> data) {
        allItems.clear();
        if (data != null) allItems.addAll(data);
        applyFilter();
    }

    public void setFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase(Locale.US);
        applyFilter();
    }

    private void applyFilter() {
        visible.clear();
        if (filter.isEmpty()) {
            visible.addAll(allItems);
        } else {
            for (LogEventEntity e : allItems) {
                if (matches(e, filter)) visible.add(e);
            }
        }
        notifyDataSetChanged();
    }

    private boolean matches(LogEventEntity e, String q) {
        return contains(e.appName, q)
                || contains(e.packageName, q)
                || contains(e.eventType, q)
                || contains(e.direction, q)
                || contains(e.detail, q);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.US).contains(q);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        LogEventEntity e = visible.get(position);
        String app = e.appName != null ? e.appName
                : (e.packageName != null ? e.packageName : "-");
        h.text.setText(sdf.format(new Date(e.timestamp))
                + "  [" + e.eventType + "]  "
                + app + "  "
                + (e.direction != null ? e.direction : "")
                + "  "
                + (e.detail != null ? e.detail : ""));

        Drawable icon = resolveIcon(e.packageName);
        if (icon != null) {
            h.icon.setImageDrawable(icon);
        } else {
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    private Drawable resolveIcon(String packageName) {
        if (packageName == null || packageManager == null) return null;
        if (packageName.startsWith("uid:") || packageName.equals("unknown")) return null;
        if (iconCache.containsKey(packageName)) return iconCache.get(packageName);
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName, 0);
            Drawable d = packageManager.getApplicationIcon(ai);
            iconCache.put(packageName, d);
            return d;
        } catch (Exception e) {
            iconCache.put(packageName, null);
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView text;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.logIcon);
            text = v.findViewById(R.id.logText);
        }
    }
}
