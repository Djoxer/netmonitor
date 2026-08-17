package dev.djoxer.netmonitor.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import dev.djoxer.netmonitor.data.entity.LogEventEntity;

@Dao
public interface LogEventDao {

    @Insert
    void insert(LogEventEntity event);

    @Insert
    void insertAll(List<LogEventEntity> events);

    @Query("SELECT * FROM log_events ORDER BY timestamp DESC LIMIT :limit")
    List<LogEventEntity> getRecent(int limit);

    @Query("DELETE FROM log_events")
    void clear();

    @Query("DELETE FROM log_events WHERE timestamp < :before")
    void deleteOlderThan(long before);
}
