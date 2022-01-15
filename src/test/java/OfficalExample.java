import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfficalExample {


    private TopologyTestDriver testDriver;
    private TestInputTopic<String, Long> inputTopic;
    private TestOutputTopic<String, Long> outputTopic;
    private KeyValueStore<String, Long> store;


    @BeforeEach
    public void setup() {
        final Topology topology = new Topology();
        topology.addSource("sourceProcessor", "input-topic");
        topology.addProcessor("aggregator", new CustomMaxAggregatorSupplier(), "sourceProcessor");
        topology.addStateStore(
                Stores.keyValueStoreBuilder(
                        Stores.inMemoryKeyValueStore("aggStore"),
                        Serdes.String(),
                        Serdes.Long()).withLoggingDisabled(), // need to disable logging to allow store pre-populating
                "aggregator");
        topology.addSink("sinkProcessor", "result-topic", "aggregator");

        // setup test driver
        final Properties props = new Properties();
        props.setProperty(StreamsConfig.APPLICATION_ID_CONFIG, "maxAggregation");
        props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.Long().getClass().getName());
        testDriver = new TopologyTestDriver(topology, props);

        Serde<String> stringSerde = new Serdes.StringSerde();
        Serde<Long> longSerde = new Serdes.LongSerde();

        // setup test topics
        inputTopic = testDriver.createInputTopic("input-topic", stringSerde.serializer(), longSerde.serializer());
        outputTopic = testDriver.createOutputTopic("result-topic", stringSerde.deserializer(), longSerde.deserializer());

        // pre-populate store
        store = testDriver.getKeyValueStore("aggStore");
        store.put("a", 21L);
    }

    @AfterEach
    public void tearDown() {
        testDriver.close();
    }

    @Test
    void shouldFlushStoreForFirstInput() {
        inputTopic.pipeInput("a", 1L);
        assertThat(outputTopic.readKeyValue()).isEqualTo(new KeyValue<>("a", 21L));
        assertThat(outputTopic.isEmpty()).isTrue();

    }

    @Test
    void shouldNotUpdateStoreForSmallerValue() {
        inputTopic.pipeInput("a", 1L);
        assertThat(store.get("a")).isEqualTo(21L);
        assertThat(outputTopic.readKeyValue()).isEqualTo((new KeyValue<>("a", 21L)));
        assertThat(outputTopic.isEmpty()).isTrue();
    }

    @Test
    void shouldNotUpdateStoreForLargerValue() {
        inputTopic.pipeInput("a", 42L);
        assertThat(store.get("a")).isEqualTo(42L);
        assertThat(outputTopic.readKeyValue()).isEqualTo((new KeyValue<>("a", 42L)));
        assertThat(outputTopic.isEmpty()).isTrue();
    }

    @Test
    void shouldUpdateStoreForNewKey() {
        inputTopic.pipeInput("b", 21L);
        assertThat(store.get("b")).isEqualTo((21L));
        assertThat(outputTopic.readKeyValue()).isEqualTo((new KeyValue<>("a", 21L)));
        assertThat(outputTopic.readKeyValue()).isEqualTo(new KeyValue<>("b", 21L));
        assertThat(outputTopic.isEmpty()).isTrue();
    }

    @Test
    void shouldPunctuateIfEvenTimeAdvances() {
        final Instant recordTime = Instant.now();
        inputTopic.pipeInput("a", 1L, recordTime);
        assertThat(outputTopic.readKeyValue()).isEqualTo(new KeyValue<>("a", 21L));

        inputTopic.pipeInput("a", 1L, recordTime);
        assertThat(outputTopic.isEmpty()).isTrue();

        inputTopic.pipeInput("a", 1L, recordTime.plusSeconds(10L));
        assertThat(outputTopic.readKeyValue()).isEqualTo(new KeyValue<>("a", 21L));
        assertThat(outputTopic.isEmpty()).isTrue();
    }

    @Test
    void shouldPunctuateIfWallClockTimeAdvances() {
        testDriver.advanceWallClockTime(Duration.ofSeconds(60));
        assertThat(outputTopic.readKeyValue()).isEqualTo(new KeyValue<>("a", 21L));
        assertThat(outputTopic.isEmpty()).isTrue();
    }

    public static class CustomMaxAggregatorSupplier implements ProcessorSupplier<String, Long, String, Long> {

        @Override
        public org.apache.kafka.streams.processor.api.Processor<String, Long, String, Long> get() {
            return new CustomMaxAggregator();
        }
    }

    public static class CustomMaxAggregator implements Processor<String, Long, String, Long> {

        ProcessorContext<String, Long> context;
        private KeyValueStore<String, Long> store;

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
            this.context = context;
            context.schedule(Duration.ofSeconds(60), PunctuationType.WALL_CLOCK_TIME, time -> flushStore());
            context.schedule(Duration.ofSeconds(10), PunctuationType.STREAM_TIME, time -> flushStore());
            store = (KeyValueStore<String, Long>) context.getStateStore("aggStore");
        }

        private void flushStore() {
            final KeyValueIterator<String, Long> it = store.all();
            while (it.hasNext()) {
                KeyValue<String, Long> next = it.next();
                context.forward(new Record<>(next.key, next.value, System.currentTimeMillis()));
            }
        }

        @Override
        public void process(Record<String, Long> record) {
            String key = record.key();
            Long newValue = record.value();
            final Long oldValue = store.get(key);
            if (oldValue == null || newValue > oldValue) {
                store.put(key, newValue);
            }
        }

        @Override
        public void close() {
        }
    }
}