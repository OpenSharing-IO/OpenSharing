package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.engine.Engine;
import io.opensharing.http.ApiException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeltaTableVersionReaderTest {

  @Test
  void returnsLatestVersionWithoutATimestamp() {
    Engine engine = mock(Engine.class);
    Table table = mock(Table.class);
    Snapshot latest = snapshot(engine, 7, 7000);
    when(table.getLatestSnapshot(engine)).thenReturn(latest);

    assertEquals(7, DeltaTableVersionReader.versionAtOrAfter(table, engine, null));
  }

  @Test
  void returnsEarliestVersionAtOrAfterTimestamp() {
    Engine engine = mock(Engine.class);
    Table table = mock(Table.class);
    Map<Long, Snapshot> snapshots =
        Map.of(
            0L, snapshot(engine, 0, 1000),
            1L, snapshot(engine, 1, 2000),
            2L, snapshot(engine, 2, 4000),
            3L, snapshot(engine, 3, 5000));
    when(table.getLatestSnapshot(engine)).thenReturn(snapshots.get(3L));
    when(table.getSnapshotAsOfVersion(eq(engine), anyLong()))
        .thenAnswer(call -> snapshots.get(call.getArgument(1, Long.class)));

    assertEquals(
        0,
        DeltaTableVersionReader.versionAtOrAfter(
            table, engine, Instant.ofEpochMilli(500)));
    assertEquals(
        1,
        DeltaTableVersionReader.versionAtOrAfter(
            table, engine, Instant.ofEpochMilli(2000)));
    assertEquals(
        2,
        DeltaTableVersionReader.versionAtOrAfter(
            table, engine, Instant.ofEpochMilli(2500)));
    assertThrows(
        ApiException.class,
        () ->
            DeltaTableVersionReader.versionAtOrAfter(
                table, engine, Instant.ofEpochMilli(6000)));
  }

  private static Snapshot snapshot(Engine engine, long version, long timestamp) {
    Snapshot snapshot = mock(Snapshot.class);
    when(snapshot.getVersion()).thenReturn(version);
    when(snapshot.getTimestamp(engine)).thenReturn(timestamp);
    return snapshot;
  }
}
