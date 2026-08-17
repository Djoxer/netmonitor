package dev.djoxer.netmonitor.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockSchedule;

public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.Holder> {

    public interface Listener {
        void onDelete(BlockSchedule schedule);
    }

    private final List<BlockSchedule> items = new ArrayList<>();
    private final Listener listener;

    private static final String[] DAY_NAMES = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    public ScheduleAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<BlockSchedule> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_schedule_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        BlockSchedule s = items.get(position);
        h.text.setText(formatSchedule(s));
        h.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(s);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static String formatSchedule(BlockSchedule s) {
        StringBuilder days = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (((s.daysMask >> i) & 1) != 0) {
                if (days.length() > 0) days.append(",");
                days.append(DAY_NAMES[i]);
            }
        }
        if (days.length() == 0) days.append("-");
        return days + "  " + minuteToTime(s.startMinute) + " – " + minuteToTime(s.endMinute);
    }

    private static String minuteToTime(int m) {
        int h = m / 60;
        int min = m % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, min);
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView text;
        Button btnDelete;

        Holder(View v) {
            super(v);
            text = v.findViewById(R.id.scheduleText);
            btnDelete = v.findViewById(R.id.btnDeleteSchedule);
        }
    }
}
