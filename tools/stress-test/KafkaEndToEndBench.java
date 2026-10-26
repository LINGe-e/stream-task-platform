import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka 端到端压测驱动器，测量生产吞吐、端到端吞吐、延迟百分位和记录失败率。
 */
public final class KafkaEndToEndBench {
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String PAYLOAD_PREFIX = "stress-payload-";

    private KafkaEndToEndBench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String bootstrap = required(options, "bootstrap");
        String inputTopic = required(options, "input-topic");
        List<String> outputTopics = Arrays.stream(required(options, "output-topics").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
        String caseId = required(options, "case-id");
        int count = Integer.parseInt(required(options, "count"));
        double rate = Double.parseDouble(options.getOrDefault("rate", "0"));
        int partitions = Integer.parseInt(options.getOrDefault("partitions", "4"));
        long drainTimeoutMs = Long.parseLong(options.getOrDefault("drain-timeout-ms", "30000"));
        String resultPath = required(options, "result");

        if (outputTopics.isEmpty() || count <= 0 || rate < 0 || partitions <= 0) {
            throw new IllegalArgumentException("输出 Topic、消息数、分区数和速率参数无效");
        }

        int expectedPerTopic = count;
        int expectedTotal = expectedPerTopic * outputTopics.size();
        Map<String, Set<Long>> seenByTopic = new ConcurrentHashMap<>();
        outputTopics.forEach(topic -> seenByTopic.put(topic, ConcurrentHashMap.newKeySet()));
        ConcurrentLinkedQueue<Long> endToEndLatenciesMs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> producerLatenciesMs = new ConcurrentLinkedQueue<>();
        AtomicLong receivedUnique = new AtomicLong();
        AtomicLong receivedTotal = new AtomicLong();
        AtomicLong duplicateRecords = new AtomicLong();
        AtomicLong producerAcked = new AtomicLong();
        AtomicLong producerFailed = new AtomicLong();
        AtomicBoolean producerDone = new AtomicBoolean();
        AtomicBoolean stopConsumers = new AtomicBoolean();
        AtomicLong firstEmitNs = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastReceiveNs = new AtomicLong(Long.MIN_VALUE);
        AtomicLong consumerDeadlineNs = new AtomicLong();

        long benchmarkStartNs = System.nanoTime();
        List<Thread> consumers = new ArrayList<>();
        CountDownLatch assignmentLatch = new CountDownLatch(outputTopics.size());

        for (String outputTopic : outputTopics) {
            Thread consumerThread = new Thread(() -> consume(
                    bootstrap,
                    outputTopic,
                    caseId,
                    expectedPerTopic,
                    seenByTopic.get(outputTopic),
                    endToEndLatenciesMs,
                    receivedUnique,
                    receivedTotal,
                    duplicateRecords,
                    firstEmitNs,
                    lastReceiveNs,
                    producerDone,
                    stopConsumers,
                    assignmentLatch,
                    consumerDeadlineNs::get
            ), "consumer-" + outputTopic);
            consumerThread.setDaemon(true);
            consumers.add(consumerThread);
            consumerThread.start();
        }

        if (!assignmentLatch.await(15, TimeUnit.SECONDS)) {
            stopConsumers.set(true);
            throw new IllegalStateException("Kafka Consumer 未在 15 秒内完成分区分配");
        }

        long producerStartNs = System.nanoTime();
        produce(
                bootstrap,
                inputTopic,
                caseId,
                count,
                rate,
                partitions,
                producerAcked,
                producerFailed,
                producerLatenciesMs,
                firstEmitNs
        );
        long producerEndNs = System.nanoTime();
        producerDone.set(true);
        consumerDeadlineNs.set(producerEndNs + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs));

        for (Thread consumerThread : consumers) {
            consumerThread.join(drainTimeoutMs + 5000);
        }
        stopConsumers.set(true);
        for (Thread consumerThread : consumers) {
            consumerThread.interrupt();
        }

        long lastReceive = lastReceiveNs.get();
        if (lastReceive == Long.MIN_VALUE) {
            lastReceive = System.nanoTime();
        }
        long wallNs = Math.max(1, lastReceive - benchmarkStartNs);
        long producerNs = Math.max(1, producerEndNs - producerStartNs);
        double producerSeconds = producerNs / 1_000_000_000.0;
        double wallSeconds = wallNs / 1_000_000_000.0;
        long unique = receivedUnique.get();
        long missing = Math.max(0, expectedTotal - unique);

        Map<String, Long> failureRateByTopic = new java.util.LinkedHashMap<>();
        for (String outputTopic : outputTopics) {
            long topicUnique = seenByTopic.get(outputTopic).size();
            long topicMissing = Math.max(0, expectedPerTopic - topicUnique);
            failureRateByTopic.put(outputTopic, topicMissing);
        }

        String json = "{\n"
                + "  \"case_id\": " + quote(caseId) + ",\n"
                + "  \"input_topic\": " + quote(inputTopic) + ",\n"
                + "  \"output_topics\": " + quoteList(outputTopics) + ",\n"
                + "  \"count_per_output\": " + expectedPerTopic + ",\n"
                + "  \"expected_outputs\": " + expectedTotal + ",\n"
                + "  \"produced\": " + count + ",\n"
                + "  \"producer_acked\": " + producerAcked.get() + ",\n"
                + "  \"producer_failed\": " + producerFailed.get() + ",\n"
                + "  \"received_total\": " + receivedTotal.get() + ",\n"
                + "  \"received_unique\": " + unique + ",\n"
                + "  \"missing_outputs\": " + missing + ",\n"
                + "  \"duplicate_records\": " + duplicateRecords.get() + ",\n"
                + "  \"record_failure_rate\": " + rate(missing, expectedTotal) + ",\n"
                + "  \"producer_throughput_rps\": " + fixed(producerAcked.get() / producerSeconds, 2) + ",\n"
                + "  \"end_to_end_throughput_rps\": " + fixed(unique / wallSeconds, 2) + ",\n"
                + "  \"producer_seconds\": " + fixed(producerSeconds, 3) + ",\n"
                + "  \"measurement_seconds\": " + fixed(wallSeconds, 3) + ",\n"
                + "  \"producer_latency_ms\": " + latencyJson(producerLatenciesMs) + ",\n"
                + "  \"end_to_end_latency_ms\": " + latencyJson(endToEndLatenciesMs) + ",\n"
                + "  \"missing_by_output\": " + numericMapJson(failureRateByTopic) + "\n"
                + "}\n";
        java.nio.file.Files.writeString(java.nio.file.Path.of(resultPath), json, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println(json);
    }

    private static void produce(
            String bootstrap,
            String topic,
            String caseId,
            int count,
            double rate,
            int partitions,
            AtomicLong acked,
            AtomicLong failed,
            ConcurrentLinkedQueue<Long> producerLatenciesMs,
            AtomicLong firstEmitNs
    ) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "stress-producer-" + caseId);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);

        char[] payload = createPayload();
        long startNs = System.nanoTime();
        long intervalNs = rate > 0 ? (long) (1_000_000_000.0 / rate) : 0;

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (int sequence = 0; sequence < count; sequence++) {
                if (intervalNs > 0) {
                    long scheduledNs = startNs + intervalNs * sequence;
                    long sleepNs = scheduledNs - System.nanoTime();
                    if (sleepNs > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleepNs);
                    }
                }
                long emitNs = System.nanoTime();
                firstEmitNs.accumulateAndGet(emitNs, Math::min);
                String value = caseId + FIELD_SEPARATOR
                        + sequence + FIELD_SEPARATOR
                        + emitNs + FIELD_SEPARATOR
                        + new String(payload);
                int partition = sequence % partitions;
                producer.send(new ProducerRecord<>(topic, partition, null, value), (metadata, exception) -> {
                    long ackNs = System.nanoTime();
                    if (exception == null) {
                        acked.incrementAndGet();
                        producerLatenciesMs.add(Math.round((ackNs - emitNs) / 1_000_000.0));
                    } else {
                        failed.incrementAndGet();
                    }
                });
            }
            producer.flush();
        }
    }

    private static void consume(
            String bootstrap,
            String topic,
            String caseId,
            int expected,
            Set<Long> seen,
            ConcurrentLinkedQueue<Long> latenciesMs,
            AtomicLong receivedUnique,
            AtomicLong receivedTotal,
            AtomicLong duplicateRecords,
            AtomicLong firstEmitNs,
            AtomicLong lastReceiveNs,
            AtomicBoolean producerDone,
            AtomicBoolean stopConsumers,
            CountDownLatch assignmentLatch,
            DeadlineSupplier deadlineSupplier
    ) {
        String groupId = "stress-bench-" + topic + "-" + caseId + "-" + java.util.UUID.randomUUID();
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "stress-consumer-" + caseId + "-" + topic);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(topic));
            boolean assigned = false;
            while (!stopConsumers.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!assigned) {
                    assigned = !consumer.assignment().isEmpty();
                    if (assigned) {
                        assignmentLatch.countDown();
                    }
                }
                for (ConsumerRecord<String, String> record : records) {
                    String value = record.value();
                    if (value == null || !value.startsWith(caseId + FIELD_SEPARATOR)) {
                        continue;
                    }
                    String[] fields = value.split(FIELD_SEPARATOR, -1);
                    if (fields.length < 3) {
                        continue;
                    }
                    receivedTotal.incrementAndGet();
                    long sequence = Long.parseLong(fields[1]);
                    long emitNs = Long.parseLong(fields[2]);
                    if (seen.add(sequence)) {
                        receivedUnique.incrementAndGet();
                        long receiveNs = System.nanoTime();
                        latenciesMs.add(Math.round((receiveNs - emitNs) / 1_000_000.0));
                        firstEmitNs.accumulateAndGet(emitNs, Math::min);
                        lastReceiveNs.accumulateAndGet(receiveNs, Math::max);
                    } else {
                        duplicateRecords.incrementAndGet();
                    }
                }
                if (seen.size() >= expected) {
                    break;
                }
                if (producerDone.get() && System.nanoTime() >= deadlineSupplier.get()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            if (!stopConsumers.get()) {
                throw exception;
            }
        }
    }

    private static char[] createPayload() {
        int length = 128;
        char[] payload = new char[length];
        String prefix = PAYLOAD_PREFIX;
        for (int index = 0; index < length; index++) {
            payload[index] = index < prefix.length() ? prefix.charAt(index) : (char) ('a' + (index % 26));
        }
        return payload;
    }

    private static String latencyJson(ConcurrentLinkedQueue<Long> values) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            return "{\"count\":0,\"avg\":0,\"p50\":0,\"p95\":0,\"p99\":0,\"max\":0}";
        }
        double average = Arrays.stream(sorted).average().orElse(0);
        return "{\"count\":" + sorted.length
                + ",\"avg\":" + fixed(average, 2)
                + ",\"p50\":" + fixed(percentile(sorted, 0.50), 2)
                + ",\"p95\":" + fixed(percentile(sorted, 0.95), 2)
                + ",\"p99\":" + fixed(percentile(sorted, 0.99), 2)
                + ",\"max\":" + fixed(sorted[sorted.length - 1], 2)
                + "}";
    }

    private static double percentile(long[] sorted, double percentile) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = percentile * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    private static String numericMapJson(Map<String, Long> values) {
        return values.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quoteList(List<String> values) {
        return values.stream().map(KafkaEndToEndBench::quote).collect(Collectors.joining(",", "[", "]"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static String fixed(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new java.util.HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("参数必须成对提供：" + args[index]);
            }
            options.put(args[index].substring(2), args[index + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface DeadlineSupplier {
        long get();
    }
}
