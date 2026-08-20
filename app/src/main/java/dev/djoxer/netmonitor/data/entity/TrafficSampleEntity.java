package dev.djoxer.netmonitor.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "traffic_samples")
public class TrafficSampleEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;   // epoch ms
    public long bytesOut;    // cumulative snapshot at sample time
    public long bytesIn;
    public long bytesV4;
    public long bytesV6;
}
