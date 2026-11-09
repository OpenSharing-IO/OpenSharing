package io.opensharing.asset.table.delta;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import org.junit.jupiter.api.Test;

class StaxProviderTest {

  @Test
  void hadoopLeavesTheJvmStaxProvidersAlone() {
    List<Object> factories =
        List.of(
            XMLInputFactory.newInstance(),
            XMLOutputFactory.newInstance(),
            XMLEventFactory.newInstance());
    for (Object factory : factories) {
      String name = factory.getClass().getName();
      assertFalse(name.startsWith("org.apache.hadoop.shaded."), name);
    }
  }
}
