import java.util.Map;
import java.util.Properties;

import example.avro.Color;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singletonMap;

import static io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;

public class AvroSimpleTest {

  final String INPUT_TOPIC  = "in";
  final String OUTPUT_TOPIC = "out";


  private TopologyTestDriver             testDriver;
  private TestInputTopic<String, Color>  inputTopic;
  private TestOutputTopic<String, Color> outputTopic;


  private static final String SCHEMA_REGISTRY_SCOPE    = AvroSimpleTest.class.getName();
  private static final String MOCK_SCHEMA_REGISTRY_URL = "mock://" + SCHEMA_REGISTRY_SCOPE;

  @BeforeEach
  public void setup() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
    props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL);

    // Create Serdes used for test record keys and values
    Serde<String> stringSerde = Serdes.String();
    Serde<Color> avroColorSerde = new SpecificAvroSerde<>();

    Map<String, String> config = singletonMap(SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL);

    avroColorSerde.configure(config, false);

    StreamsBuilder builder = new StreamsBuilder();

    builder.stream(INPUT_TOPIC, Consumed.with(stringSerde, avroColorSerde, null, null))
           .filter((key, value) -> value.getName().length() > 3)
           .to(OUTPUT_TOPIC, Produced.with(stringSerde, avroColorSerde));

    testDriver = new TopologyTestDriver(builder.build(), props);

    inputTopic = testDriver.createInputTopic(INPUT_TOPIC, new StringSerializer(), avroColorSerde.serializer());
    outputTopic = testDriver.createOutputTopic(OUTPUT_TOPIC, new StringDeserializer(), avroColorSerde.deserializer());

  }

  @AfterEach
  public void tearDown() {
    testDriver.close();
  }

  @Test
  public void shouldFilter() {

    inputTopic.pipeInput(Color.newBuilder().setName("red").build());
    inputTopic.pipeInput(Color.newBuilder().setName("blue").build());

    assertThat(outputTopic.readValue().getName().toString()).isEqualTo("blue");

  }

}
