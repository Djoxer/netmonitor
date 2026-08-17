package dev.djoxer.netmonitor.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "log_events")
public class LogEventEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;
    public String packageName;
    public String appName;
    public String eventType;  // CONNECT, BLOCKED, ALLOWED, INFO
    public String direction;  // OUT, IN, BOTH, -
    public String detail;

    public LogEventEntity(long timestamp, String packageName, String appName,
                          String eventType, String direction, String detail) {
        this.timestamp = timestamp;
        this.packageName = packageName;
        this.appName = appName;
        this.eventType = eventType;
        this.direction = direction;
        this.detail = detail;
    }
}
