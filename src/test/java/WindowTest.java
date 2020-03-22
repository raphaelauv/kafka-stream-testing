import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.kafka.streams.kstream.Suppressed.BufferConfig.unbounded;
import static org.assertj.core.api.Assertions.assertThat;

public class WindowTest {

  final String INPUT_TOPIC  = "in";
  final String OUTPUT_TOPIC = "out";

  private TopologyTestDriver               testDriver;
  private TestInputTopic<String, Integer>  inputTopic;
  private TestOutputTopic<String, Integer> outputTopic;


  @BeforeEach
  public void setup() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
    props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);
    try {
      props.put(StreamsConfig.STATE_DIR_CONFIG,
        Files.createTempDirectory("tumbling-windows").toAbsolutePath().toString());
    } catch (IOException e) {

    }

    StreamsBuilder builder = new StreamsBuilder();

    TimeWindows windows = TimeWindows
      .of(Duration.ofSeconds(1))
      .advanceBy(Duration.ofSeconds(1))
      .grace(Duration.ofMillis(150L)); //a grace is necessary for suppress

    builder.stream(INPUT_TOPIC, Consumed.with(new Serdes.StringSerde(), new Serdes.IntegerSerde(), null, null))
           .groupByKey()
           .windowedBy(windows)
           .count()
           .suppress(Suppressed.untilWindowCloses(unbounded()))
           .mapValues((readOnlyKey, value) -> value.intValue())
           .toStream((key, value) -> key.key())
           .to(OUTPUT_TOPIC, Produced.with(new Serdes.StringSerde(), new Serdes.IntegerSerde()));

    testDriver = new TopologyTestDriver(builder.build(), props);

    inputTopic = testDriver.createInputTopic(INPUT_TOPIC, new StringSerializer(), new IntegerSerializer());
    outputTopic = testDriver.createOutputTopic(OUTPUT_TOPIC, new StringDeserializer(), new IntegerDeserializer());

  }

  @AfterEach
  public void tearDown() {
    testDriver.close();
  }

  @Test
  public void shouldWindow() {

    Instant time = ZonedDateTime.now().toInstant();

    inputTopic.pipeInput("a", 5, time);
    inputTopic.pipeInput("b", 2, time);
    inputTopic.pipeInput("a", 3, time);

    time = time.plusMillis(100);

    inputTopic.pipeInput("a", 9, time);
    inputTopic.pipeInput("b", 11, time);

    time = time.plusSeconds(10);

    inputTopic.pipeInput("c", 9, time);
    inputTopic.pipeInput("c", 11, time);
    inputTopic.pipeInput("b", 5, time);
    inputTopic.pipeInput("b", 9, time);

    time = time.plusSeconds(4);
    inputTopic.pipeInput("dump", 1, time);

    assertThat(outputTopic.getQueueSize()).isEqualTo(4);

    List<KeyValue<String, Integer>> a = Arrays.asList(
      new KeyValue<>("a", 3),
      new KeyValue<>("b", 2),
      new KeyValue<>("b", 2),
      new KeyValue<>("c", 2)
    );

    assertThat(outputTopic.readKeyValuesToList()).isEqualTo(a);

  }

}
