import java.util.Map;
import java.util.Properties;

import example.avro.Car;
import example.avro.Color;
import example.avro.Order;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singletonMap;

import static io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;

class AvroJoinTest {

    final String INPUT_TOPIC_CAR = "in_car";
    final String INPUT_TOPIC_COLOR = "in_color";
    final String OUTPUT_TOPIC = "out";


    private TopologyTestDriver testDriver;


    private TestInputTopic<String, Car> inputTopicCar;
    private TestInputTopic<String, Color> inputTopicColor;
    private TestOutputTopic<String, Order> outputTopic;


    private static final String SCHEMA_REGISTRY_SCOPE = AvroJoinTest.class.getName();
    private static final String MOCK_SCHEMA_REGISTRY_URL = "mock://" + SCHEMA_REGISTRY_SCOPE;

    @BeforeEach
    public void setup() {

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL);

        // Create Serdes used for test record keys and values
        Serde<String> stringSerde = Serdes.String();
        Serde<Color> avroColorSerde = new SpecificAvroSerde<>();
        Serde<Car> avroCarSerde = new SpecificAvroSerde<>();
        Serde<Order> avroOderSerde = new SpecificAvroSerde<>();

        Map<String, String> config = singletonMap(SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL);
        avroColorSerde.configure(config, false);
        avroCarSerde.configure(config, false);
        avroOderSerde.configure(config, false);

        StreamsBuilder builder = new StreamsBuilder();

        KTable<String, Car> cars = builder.table(INPUT_TOPIC_CAR, Consumed.with(stringSerde, avroCarSerde, null, null));

        KStream<String, Color> colors = builder.stream(INPUT_TOPIC_COLOR, Consumed.with(stringSerde, avroColorSerde, null, null));

        colors.join(cars, (color, car) -> Order.newBuilder().setCarName(car.getBrand()).setColorName(color.getName()).build())
                .to(OUTPUT_TOPIC, Produced.with(stringSerde, avroOderSerde));

        testDriver = new TopologyTestDriver(builder.build(), props);

        inputTopicCar = testDriver.createInputTopic(INPUT_TOPIC_CAR, stringSerde.serializer(), avroCarSerde.serializer());

        inputTopicColor = testDriver.createInputTopic(INPUT_TOPIC_COLOR, stringSerde.serializer(), avroColorSerde.serializer());

        outputTopic = testDriver.createOutputTopic(OUTPUT_TOPIC, stringSerde.deserializer(), avroOderSerde.deserializer());

    }

    @AfterEach
    public void tearDown() {
        testDriver.close();
    }

    @Test
    void shouldJoinByKeys() {

        inputTopicCar.pipeInput("21", Car.newBuilder().setBrand("honda").build());
        inputTopicCar.pipeInput("35", Car.newBuilder().setBrand("mercedes").build());

        inputTopicColor.pipeInput("21", Color.newBuilder().setName("red").build());
        inputTopicColor.pipeInput("35", Color.newBuilder().setName("blue").build());

        assertThat(outputTopic.getQueueSize()).isEqualTo(2L);

        outputTopic.readValuesToList().forEach(order -> {
            if (order.getColorName().toString().equals("red")) {
                assertThat(order.getCarName()).hasToString("honda");
            } else {
                assertThat(order.getCarName()).hasToString("mercedes");
            }
        });

    }

}
