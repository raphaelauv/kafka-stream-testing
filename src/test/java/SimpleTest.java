import java.util.Properties;

import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SimpleTest {

  private final String INPUT_TOPIC  = "in";
  private final String OUTPUT_TOPIC = "out";

  private TopologyTestDriver               testDriver;
  private TestInputTopic<String, Integer>  inputTopic;
  private TestOutputTopic<String, Integer> outputTopic;

  @BeforeEach
  public void setup() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");

    StreamsBuilder builder = new StreamsBuilder();

    builder.stream(INPUT_TOPIC, Consumed.with(new Serdes.StringSerde(), new Serdes.IntegerSerde(), null, null))
           .filter((key, value) -> value > 2)
           .mapValues((readOnlyKey, value) -> value + 1)
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
  public void shouldFilterAndMap() {
    inputTopic.pipeInput(5);
    inputTopic.pipeInput(3);
    inputTopic.pipeInput(2);
    inputTopic.pipeInput(9);

    assertThat(outputTopic.getQueueSize()).isEqualTo(3);
    assertThat(outputTopic.readValue()).isEqualTo(6);
  }

}
