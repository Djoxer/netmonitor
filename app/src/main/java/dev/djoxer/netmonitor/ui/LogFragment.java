package dev.djoxer.netmonitor.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.R;
import dev.djoxer.netmonitor.data.AppDatabase;
import dev.djoxer.netmonitor.data.entity.LogEventEntity;

public class LogFragment extends Fragment {

    private LogAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recycler = view.findViewById(R.id.recyclerLog);
        Button btnRefresh = view.findViewById(R.id.btnRefreshLog);

        adapter = new LogAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> loadLogs());
        loadLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLogs();
    }

    private void loadLogs() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<LogEventEntity> list = AppDatabase.getInstance(requireContext())
                    .logEventDao()
                    .getRecent(200);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> adapter.submit(list));
            }
        });
    }
}
