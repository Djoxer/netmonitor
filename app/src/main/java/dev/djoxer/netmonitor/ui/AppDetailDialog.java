package dev.djoxer.netmonitor.ui;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.BlockSchedule;
import dev.djoxer.netmonitor.data.RuleRepository;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;

public class AppDetailDialog extends DialogFragment {

    private static final String ARG_KEY = "key";
    private static final String ARG_NAME = "name";
    private static final String ARG_PKG = "pkg";
    private static final String ARG_UID = "uid";

    private AppGroup group;
    private RuleRepository ruleRepository;
    private ScheduleAdapter scheduleAdapter;
    private Switch switchPermanent;

    public static AppDetailDialog newInstance(AppGroup group) {
        AppDetailDialog d = new AppDetailDialog();
        Bundle b = new Bundle();
        b.putString(ARG_KEY, group.key);
        b.putString(ARG_NAME, group.displayName);
        b.putString(ARG_PKG, group.packageName);
        b.putInt(ARG_UID, group.uid);
        d.setArguments(b);
        d.group = group;
        return d;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ruleRepository = new RuleRepository(requireContext());

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_app_detail, null, false);

        ImageView icon = view.findViewById(R.id.detailIcon);
        TextView name = view.findViewById(R.id.detailAppName);
        TextView pkg = view.findViewById(R.id.detailPackage);
        switchPermanent = view.findViewById(R.id.switchPermanentBlock);
        RecyclerView recyclerSchedules = view.findViewById(R.id.recyclerSchedules);
        Button btnAdd = view.findViewById(R.id.btnAddSchedule);
        TextView connections = view.findViewById(R.id.detailConnections);
        Button btnClose = view.findViewById(R.id.btnCloseDetail);

        Bundle args = getArguments();
        String displayName = args != null ? args.getString(ARG_NAME, "?") : "?";
        String packageName = args != null ? args.getString(ARG_PKG) : null;
        int uid = args != null ? args.getInt(ARG_UID, -1) : -1;

        name.setText(displayName);
        pkg.setText(packageName != null ? packageName : "no package");

        if (group != null && group.icon != null) {
            icon.setImageDrawable(group.icon);
        } else {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        // Connections text
        StringBuilder sb = new StringBuilder();
        if (group != null && group.connections != null) {
            int n = 0;
            for (ConnectionInfo c : group.connections) {
                sb.append(c.protocol).append(" ")
                        .append(c.hostname != null ? c.hostname : c.destIp)
                        .append(":").append(c.destPort)
                        .append("  ↑").append(fmt(c.bytesOut))
                        .append(" ↓").append(fmt(c.bytesIn))
                        .append("\n");
                if (++n >= 100) {
                    sb.append("…\n");
                    break;
                }
            }
        }
        if (sb.length() == 0) sb.append("No connections.");
        connections.setText(sb.toString());

        // Permanent block
        boolean perm = packageName != null
                && BlockManager.getInstance().isPermanentlyBlocked(packageName);
        switchPermanent.setChecked(perm);
        switchPermanent.setEnabled(packageName != null);
        switchPermanent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (packageName == null) return;
            ruleRepository.setPermanentBlockAsync(packageName, uid, displayName, isChecked);
        });

        // Schedules
        scheduleAdapter = new ScheduleAdapter(schedule -> {
            if (packageName == null) return;
            ruleRepository.deleteScheduleAsync(schedule.id, packageName, () ->
                    requireActivity().runOnUiThread(this::reloadSchedules));
        });
        recyclerSchedules.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerSchedules.setAdapter(scheduleAdapter);

        btnAdd.setEnabled(packageName != null);
        btnAdd.setOnClickListener(v -> showAddScheduleDialog(packageName));

        btnClose.setOnClickListener(v -> dismiss());

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();

        // Load schedules after view ready
        view.post(this::reloadSchedules);

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    private void reloadSchedules() {
        Bundle args = getArguments();
        String packageName = args != null ? args.getString(ARG_PKG) : null;
        if (packageName == null || scheduleAdapter == null) {
            if (scheduleAdapter != null) scheduleAdapter.submit(null);
            return;
        }
        ruleRepository.getSchedulesAsync(packageName, list -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> scheduleAdapter.submit(list));
        });
    }

    private void showAddScheduleDialog(String packageName) {
        if (packageName == null) return;

        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_schedule, null, false);

        CheckBox[] days = {
                form.findViewById(R.id.dayMon),
                form.findViewById(R.id.dayTue),
                form.findViewById(R.id.dayWed),
                form.findViewById(R.id.dayThu),
                form.findViewById(R.id.dayFri),
                form.findViewById(R.id.daySat),
                form.findViewById(R.id.daySun)
        };
        EditText editStart = form.findViewById(R.id.editStart);
        EditText editEnd = form.findViewById(R.id.editEnd);

        new AlertDialog.Builder(requireContext())
                .setTitle("Add block schedule")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < 7; i++) {
                        if (days[i].isChecked()) mask |= (1 << i);
                    }
                    if (mask == 0) {
                        Toast.makeText(requireContext(), "Select at least one day", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Integer start = parseTime(editStart.getText().toString().trim());
                    Integer end = parseTime(editEnd.getText().toString().trim());
                    if (start == null || end == null) {
                        Toast.makeText(requireContext(), "Time format HH:mm", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ruleRepository.addScheduleAsync(packageName, mask, start, end, () ->
                            requireActivity().runOnUiThread(this::reloadSchedules));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Integer parseTime(String text) {
        try {
            String[] p = text.split(":");
            if (p.length != 2) return null;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return h * 60 + m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.getDefault(), "%.1fMB", bytes / (1024f * 1024f));
    }
}
