package dev.djoxer.netmonitor.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.entity.LogEventEntity;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    private final List<LogEventEntity> items = new ArrayList<>();
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public void submit(List<LogEventEntity> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
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
        LogEventEntity e = items.get(position);
        String app = e.appName != null ? e.appName : (e.packageName != null ? e.packageName : "-");
        h.text.setText(sdf.format(new Date(e.timestamp))
                + "  [" + e.eventType + "]  "
                + app + "  "
                + (e.direction != null ? e.direction : "")
                + "  "
                + (e.detail != null ? e.detail : ""));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView text;
        Holder(View v) {
            super(v);
            text = v.findViewById(R.id.logText);
        }
    }
}
