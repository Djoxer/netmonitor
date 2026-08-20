package dev.djoxer.netmonitor.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import dev.djoxer.netmonitor.data.entity.TrafficSampleEntity;

@Dao
public interface TrafficSampleDao {

    @Insert
    void insert(TrafficSampleEntity sample);

    @Query("SELECT * FROM traffic_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    List<TrafficSampleEntity> getSince(long since);

    @Query("DELETE FROM traffic_samples WHERE timestamp < :before")
    void deleteOlderThan(long before);

    @Query("DELETE FROM traffic_samples")
    void clear();
}
