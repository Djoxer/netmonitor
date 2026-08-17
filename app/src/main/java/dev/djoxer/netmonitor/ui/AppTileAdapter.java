package dev.djoxer.netmonitor.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.djoxer.netmonitor.R;

public class AppTileAdapter extends RecyclerView.Adapter<AppTileAdapter.Holder> {

    public interface Listener {
        void onAppClicked(AppGroup group);
    }

    private final List<AppGroup> items = new ArrayList<>();
    private final Listener listener;

    public AppTileAdapter(Listener listener) {
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
        h.stats.setText("↑" + format(g.bytesOut) + "  ↓" + format(g.bytesIn)
                + "  ·  " + g.connCount + " conn");
        if (g.icon != null) {
            h.icon.setImageDrawable(g.icon);
        } else {
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        h.badge.setVisibility(g.blocked ? View.VISIBLE : View.GONE);
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
