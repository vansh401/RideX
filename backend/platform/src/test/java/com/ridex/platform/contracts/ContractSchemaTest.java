package com.ridex.platform.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.testing.EventTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CONTRACT TESTS. The JSON schemas in src/main/resources/contracts/events are the written agreement between services. These tests
 * make that agreement enforceable from both sides:
 *
 *  - what the PRODUCERS write (the event records, serialised exactly as the outbox does) must satisfy the schema;
 *  - what the CONSUMERS read (the sample events the services' tests publish) must satisfy it too;
 *  - the schema must not promise a field the records cannot carry, nor require one the records lack (drift in either direction).
 *
 * If you change an event: change the schema first, then the record, and keep old consumers working (add fields; never rename or
 * remove one without a new version). See docs/event-contracts.md.
 */
class ContractSchemaTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EventCodec codec = new EventCodec(mapper);

    private JsonNode schema(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/" + name)) {
            assertThat(in).as("schema file " + name).isNotNull();
            return mapper.readTree(in);
        }
    }

    private RideEventData ride() {
        return new RideEventData(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER",
                null, Instant.now(), "12 Lake Road", "90 Hill Street", new BigDecimal("252.00"), "INR", null, null, null, null);
    }

    private PaymentEventData payment(String status, String failure) {
        return new PaymentEventData(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("252.00"),
                "INR", status, failure, 1, "mock_ch_1", null, null, null);
    }

    private String json(String type, String key, Object data) {
        return codec.toJson(codec.envelope(type, key, data));
    }

    private List<String> problems(String schemaFile, JsonNode value) throws IOException {
        return MiniJsonSchema.validate(schema(schemaFile), value);
    }

    // ------------------------------------------------------------------ producers

    @Test
    void aRideEventWrittenByTheCoreSatisfiesTheEnvelopeAndTheDataSchema() throws IOException {
        JsonNode event = mapper.readTree(json(EventEnvelope.RIDE_STATUS_CHANGED, "ride-1", ride()));

        assertThat(problems("envelope.v1.json", event)).isEmpty();
        assertThat(problems("ride.status-changed.v1.json", event.get("data"))).isEmpty();
    }

    @Test
    void aRideEventWithoutADriverYetIsStillValid() throws IOException {
        RideEventData noDriver = new RideEventData(UUID.randomUUID(), UUID.randomUUID(), null, "SEARCHING_DRIVER", "CANCELLED", "SYSTEM",
                "no driver found", Instant.now(), "A", "B", null, null, null, null, null, null);

        JsonNode event = mapper.readTree(json(EventEnvelope.RIDE_STATUS_CHANGED, "ride-1", noDriver));

        assertThat(problems("ride.status-changed.v1.json", event.get("data"))).isEmpty();
    }

    @Test
    void paymentEventsWrittenByThePaymentServiceSatisfyTheSchema() throws IOException {
        JsonNode succeeded = mapper.readTree(json(EventEnvelope.PAYMENT_SUCCEEDED, "ride-1", payment("SUCCEEDED", null)));
        JsonNode failed = mapper.readTree(json(EventEnvelope.PAYMENT_FAILED, "ride-1", payment("FAILED", "card_declined")));

        assertThat(problems("envelope.v1.json", succeeded)).isEmpty();
        assertThat(problems("payment.v1.json", succeeded.get("data"))).isEmpty();
        assertThat(problems("payment.v1.json", failed.get("data"))).isEmpty();
    }

    @Test
    void driverReviewEventsSatisfyTheSchema() throws IOException {
        UUID driver = UUID.randomUUID();
        JsonNode approved = mapper.readTree(json(EventEnvelope.DRIVER_APPLICATION_REVIEWED, driver.toString(),
                new DriverReviewedEventData(driver, DriverReviewedEventData.APPROVED, null)));
        JsonNode rejected = mapper.readTree(json(EventEnvelope.DRIVER_APPLICATION_REVIEWED, driver.toString(),
                new DriverReviewedEventData(driver, DriverReviewedEventData.REJECTED, "Photo is blurry")));

        assertThat(problems("driver.application-reviewed.v1.json", approved.get("data"))).isEmpty();
        assertThat(problems("driver.application-reviewed.v1.json", rejected.get("data"))).isEmpty();
    }

    // ------------------------------------------------------------------ consumers

    @Test
    void theSampleEventsConsumerTestsPublishFollowTheContractToo() throws IOException {
        UUID ride = UUID.randomUUID(), rider = UUID.randomUUID(), driver = UUID.randomUUID();

        JsonNode rideEvent = mapper.readTree(EventTestSupport.rideEvent(UUID.randomUUID(), ride, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER", new BigDecimal("252.00")));
        JsonNode paymentEvent = mapper.readTree(EventTestSupport.paymentEvent(UUID.randomUUID(), "payment.failed", ride, rider, driver, new BigDecimal("252.00"), "card_declined"));
        JsonNode reviewEvent = mapper.readTree(EventTestSupport.driverReviewedEvent(UUID.randomUUID(), driver, "REJECTED", "Photo is blurry"));

        assertThat(problems("envelope.v1.json", rideEvent)).isEmpty();
        assertThat(problems("ride.status-changed.v1.json", rideEvent.get("data"))).isEmpty();
        assertThat(problems("envelope.v1.json", paymentEvent)).isEmpty();
        assertThat(problems("payment.v1.json", paymentEvent.get("data"))).isEmpty();
        assertThat(problems("envelope.v1.json", reviewEvent)).isEmpty();
        assertThat(problems("driver.application-reviewed.v1.json", reviewEvent.get("data"))).isEmpty();
    }

    @Test
    void aTolerantReaderAcceptsAFieldAddedByANewerProducer() throws IOException {
        JsonNode event = mapper.readTree(json(EventEnvelope.RIDE_STATUS_CHANGED, "ride-1", ride()));
        ((tools.jackson.databind.node.ObjectNode) event.get("data")).put("surgeReason", "rain");   // a field this version never heard of

        assertThat(problems("ride.status-changed.v1.json", event.get("data"))).as("the schema allows unknown fields").isEmpty();
        RideEventData read = codec.data(codec.parse(mapper.writeValueAsString(event)), RideEventData.class);
        assertThat(read.to()).as("and the reader still reads the event").isEqualTo("RIDE_COMPLETED");
    }

    // ------------------------------------------------------------------ the validator itself catches real breakage

    @Test
    void breakingChangesAreCaught() throws IOException {
        JsonNode good = mapper.readTree(json(EventEnvelope.RIDE_STATUS_CHANGED, "ride-1", ride())).get("data");
        tools.jackson.databind.node.ObjectNode renamed = ((tools.jackson.databind.node.ObjectNode) good.deepCopy());
        renamed.remove("rideId");
        renamed.put("ride_id", UUID.randomUUID().toString());   // a "harmless" rename: breaks every consumer
        tools.jackson.databind.node.ObjectNode retyped = ((tools.jackson.databind.node.ObjectNode) good.deepCopy());
        retyped.put("fareTotal", "252.00");                    // number became text

        assertThat(problems("ride.status-changed.v1.json", renamed)).anyMatch(p -> p.contains("missing required field 'rideId'"));
        assertThat(problems("ride.status-changed.v1.json", retyped)).anyMatch(p -> p.contains("fareTotal"));
    }

    // ------------------------------------------------------------------ no drift between schema and records

    @Test
    void everyPropertyOfTheSchemaExistsInTheRecordAndEveryRequiredOneIsNeverMissing() throws IOException {
        assertMatches("ride.status-changed.v1.json", RideEventData.class);
        assertMatches("payment.v1.json", PaymentEventData.class);
        assertMatches("driver.application-reviewed.v1.json", DriverReviewedEventData.class);
    }

    private void assertMatches(String schemaFile, Class<? extends Record> record) throws IOException {
        JsonNode schema = schema(schemaFile);
        List<String> fields = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            fields.add(component.getName());
        }
        List<String> declared = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(declared::add);
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asString()));

        assertThat(fields).as(record.getSimpleName() + " carries every property the schema promises").containsAll(declared);
        assertThat(declared).as("the schema documents every field of " + record.getSimpleName()).containsAll(fields);
        assertThat(declared).as("required fields are declared properties").containsAll(required);
    }
}
