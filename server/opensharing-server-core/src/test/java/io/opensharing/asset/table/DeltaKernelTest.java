package io.opensharing.asset.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.delta.kernel.Snapshot;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.TableImpl;
import io.opensharing.http.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DeltaKernelTest {

  @Test
  void returnsLatestVersionWithoutATimestamp() {
    Engine engine = mock(Engine.class);
    TableImpl table = mock(TableImpl.class);
    Snapshot latest = mock(Snapshot.class);
    when(latest.getVersion()).thenReturn(7L);
    when(table.getLatestSnapshot(engine)).thenReturn(latest);

    assertEquals(7, DeltaKernel.versionAtOrAfter(table, engine, null));
  }

  @Test
  void usesKernelVersionAtOrAfterTimestamp() {
    Engine engine = mock(Engine.class);
    TableImpl table = mock(TableImpl.class);
    when(table.getVersionAtOrAfterTimestamp(engine, 500)).thenReturn(0L);
    when(table.getVersionAtOrAfterTimestamp(engine, 2000)).thenReturn(1L);
    when(table.getVersionAtOrAfterTimestamp(engine, 2500)).thenReturn(2L);
    when(table.getVersionAtOrAfterTimestamp(engine, 6000))
        .thenThrow(new KernelException("timestamp after latest"));

    assertEquals(0, DeltaKernel.versionAtOrAfter(table, engine, Instant.ofEpochMilli(500)));
    assertEquals(1, DeltaKernel.versionAtOrAfter(table, engine, Instant.ofEpochMilli(2000)));
    assertEquals(2, DeltaKernel.versionAtOrAfter(table, engine, Instant.ofEpochMilli(2500)));

    ApiException afterLatest =
        assertThrows(
            ApiException.class,
            () -> DeltaKernel.versionAtOrAfter(table, engine, Instant.ofEpochMilli(6000)));
    assertEquals(HttpStatus.BAD_REQUEST, afterLatest.getStatus());
  }
}
