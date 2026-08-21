package dev.djoxer.netmonitor.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.djoxer.netmonitor.R;

public class AppTileAdapter extends RecyclerView.Adapter<AppTileAdapter.Holder> {

    public enum Mode { OUT, IN }

    public interface Listener {
        void onAppClicked(AppGroup group);
    }

    private final List<AppGroup> items = new ArrayList<>();
    private final Listener listener;
    private final Mode mode;

    public AppTileAdapter(Mode mode, Listener listener) {
        this.mode = mode;
        this.listener = listener;
    }

    public void submit(List<AppGroup> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_tile, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        AppGroup g = items.get(position);
        h.name.setText(g.displayName);

        if (mode == Mode.OUT) {
            h.stats.setText("^ " + format(g.bytesOut));
        } else {
            h.stats.setText("v " + format(g.bytesIn));
        }

        if (g.icon != null) {
            h.icon.setImageDrawable(g.icon);
        } else {
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        // Bypass wins over direction block; shown on BOTH columns
        if (g.bypass) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText("BYPASS");
            h.badge.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.bar_ipv4));
        } else if (mode == Mode.OUT && g.blockedOut) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText("BLOCK");
            h.badge.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.status_block));
        } else if (mode == Mode.IN && g.blockedIn) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText("BLOCK");
            h.badge.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.status_block));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAppClicked(g);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String format(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name, stats, badge;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.appIcon);
            name = v.findViewById(R.id.appName);
            stats = v.findViewById(R.id.appStats);
            badge = v.findViewById(R.id.blockBadge);
        }
    }
}
