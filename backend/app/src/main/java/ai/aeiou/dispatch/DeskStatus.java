package ai.aeiou.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The desk's status line: the worker status plus, on a read-only desk, when its replica last synced
 * from the writer's Postgres. One place, shared by {@code GET /status} and the event stream.
 */
@Component
class DeskStatus {
  private final WaveWorker wave;
  private final WriterLease lease;
  private final PostgresReplica replica;
  private final ObjectMapper json;

  DeskStatus(WaveWorker wave, WriterLease lease, PostgresReplica replica, ObjectMapper json) {
    this.wave = wave;
    this.lease = lease;
    this.replica = replica;
    this.json = json;
  }

  Map<String, Object> current() {
    Map<String, Object> status = json.convertValue(wave.status(), new TypeReference<>() {});
    status.put("read_only", lease.readOnly());
    if (lease.readOnly()) {
      status.put("replica_synced", replica.lastSync() == null ? null : replica.lastSync().toString());
      status.put("replica_error", replica.error());
    }
    return status;
  }
}
