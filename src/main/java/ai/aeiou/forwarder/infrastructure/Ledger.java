package ai.aeiou.forwarder.infrastructure;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.api.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Ledger {
  public final JdbcTemplate db;

  public Ledger(JdbcTemplate db) {
    this.db = db;
  }

  // Always acquire before modifying projections or allocating a stream sequence.
  public long lock() {
    return db.queryForObject("select seq from stream_clock where id=1 for update", Long.class);
  }

  public long cursor() {
    return db.queryForObject("select seq from stream_clock where id=1", Long.class);
  }

  public List<JsonNode> read(String sql, Object... args) {
    return db.query(sql, (rs, i) -> parse(rs.getString(1)), args);
  }

  public ObjectNode shipment(String id) {
    return (ObjectNode)
        read("select data from shipment where id=?", id).stream()
            .findFirst()
            .orElseThrow(Problem::missing);
  }

  public void save(ObjectNode data) {
    String id = data.at("/session/id").asText();
    if (db.update("update shipment set data=? where id=?", data.toString(), id) == 0)
      db.update("insert into shipment(id,data) values (?,?)", id, data.toString());
  }

  public void event(String id, String kind, String summary, Instant at, Integer version) {
    long seq = lock() + 1;
    var event =
        obj(
            "sequence",
            Long.toString(seq),
            "sessionId",
            id,
            "sessionVersion",
            version,
            "kind",
            kind,
            "summary",
            summary,
            "at",
            at.toString());
    db.update("update stream_clock set seq=? where id=1", seq);
    db.update("insert into change_event values (?,?,?)", seq, id, event.toString());
  }

  public void changed(ObjectNode data, String kind, String summary, Instant at) {
    ObjectNode s = (ObjectNode) data.get("session");
    int v = s.path("version").asInt() + 1;
    s.put("version", v).put("updatedAt", at.toString());
    save(data);
    event(str(s, "id"), kind, summary, at, v);
  }

  public void schedule(String key, String id, String action, Instant due, JsonNode payload) {
    if (db.queryForObject("select count(*) from job where id=?", Integer.class, key) == 0)
      db.update(
          "insert into job(id,shipment_id,action,due_at,state,payload) values(?,?,?,?,'READY',?)",
          key,
          id,
          action,
          java.sql.Timestamp.from(due),
          payload.toString());
  }

  public void message(String sid, ObjectNode mail) {
    db.update(
        "insert into mail(id,shipment_id,upstream_id,data) values(?,?,?,?)",
        str(mail, "id"),
        sid,
        mail.path("upstreamId").asText(null),
        mail.toString());
  }
}
