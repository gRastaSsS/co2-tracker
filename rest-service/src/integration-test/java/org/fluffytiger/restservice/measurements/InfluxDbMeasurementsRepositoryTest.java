package org.fluffytiger.restservice.measurements;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.fluffytiger.restservice.sensors.MeasureRecord;
import org.fluffytiger.restservice.sensors.repository.InfluxDbMeasurementsRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

class InfluxDbMeasurementsRepositoryTest extends AbstractInfluxDbTest {
    @Autowired
    private InfluxDBClient influxDB;

    @Autowired
    private InfluxDbMeasurementsRepository repository;

    @Test
    void test_ContainerStarts() {
        Assertions.assertTrue(db.isRunning());
        Assertions.assertEquals(List.of(EXPOSED_PORT), db.getExposedPorts());
        Assertions.assertNotNull(db.getHost());
    }

    @Test
    void test_Insert() {
        OffsetDateTime time = OffsetDateTime.now();
        UUID sensorId = UUID.fromString("e0000000-c03b-4a04-a25b-38f39be8ebba");
        var queryApi = influxDB.getQueryApi();

        var selectQuery = String.format(
            "from(bucket:\"%s\") " +
                "|> range(start:-1h) " +
                "|> filter(fn:(r) => r.sensor == \"%s\")",
            BUCKET_NAME, sensorId
        );

        var result = queryApi.query(selectQuery);
        Assertions.assertTrue(result.isEmpty());

        int co2 = 1000;
        repository.insertRecord(new MeasureRecord(co2, time, sensorId));

        await()
            .atMost(10, TimeUnit.SECONDS)
            .until(() -> !queryApi.query(selectQuery).isEmpty());

        result = queryApi.query(selectQuery);
        Assertions.assertEquals(result.size(), 1);
        System.out.println(result.get(0));
    }

    @Test
    void test_GetMaxOverLastNDays() {
        UUID sensorId = UUID.fromString("e0000001-c03b-4a04-a25b-38f39be8ebba");

        Assertions.assertEquals(0, repository.getMaxOverLastNDays(sensorId, 1));

        List<Integer> co2 = List.of(100, 200, 300, 400, 200);
        insertData(co2, sensorId);
        Assertions.assertEquals(400, repository.getMaxOverLastNDays(sensorId, 1));

        co2 = List.of(200, 300, 500, -600, 0);
        insertData(co2, sensorId);
        Assertions.assertEquals(500, repository.getMaxOverLastNDays(sensorId, 1));
    }

    @Test
    void test_GetAverageOverLastNDays() {
        UUID sensorId = UUID.fromString("e0000002-c03b-4a04-a25b-38f39be8ebba");

        Assertions.assertEquals(0.0, repository.getAverageOverLastNDays(sensorId, 1).doubleValue());

        List<Integer> co2 = List.of(100, 200, 400, 200);
        insertData(co2, sensorId);

        double expected = co2.stream()
            .mapToDouble(Integer::doubleValue)
            .average()
            .orElse(0);

        Assertions.assertEquals(expected, repository.getAverageOverLastNDays(sensorId, 1), 0.001);
    }

    @Test
    void test_GetLastMeasurements() {
        UUID sensorId = UUID.fromString("e0000003-c03b-4a04-a25b-38f39be8ebba");

        Assertions.assertTrue(repository.getLastMeasurements(sensorId, 10).isEmpty());

        List<Integer> co2 = List.of(100, 200, 300, 400);
        insertData(co2, sensorId);

        List<Integer> last = co2.subList(1, co2.size());
        last = new ArrayList<>(last);
        Collections.reverse(last);

        Assertions.assertEquals(last, repository.getLastMeasurements(sensorId, 3));
    }

    @Test
    void test_GetLastMeasurements_MultipleSensors() {
        UUID sensorId1 = UUID.fromString("e0000004-c03b-4a04-a25b-38f39be8ebba");
        UUID sensorId2 = UUID.fromString("e0000005-c03b-4a04-a25b-38f39be8ebba");

        List<Integer> co2 = List.of(100, 200, 300, 400);
        insertData(co2, sensorId1);

        List<Integer> last = new ArrayList<>(co2);
        Collections.reverse(last);

        Assertions.assertEquals(last, repository.getLastMeasurements(sensorId1, 10));
        Assertions.assertTrue(repository.getLastMeasurements(sensorId2, 10).isEmpty());
    }

    private void insertData(List<Integer> co2, UUID sensorId) {
        var api = influxDB.getWriteApiBlocking();

        for (var measure : co2) {
            Point point = Point.measurement("co2")
                .addTag("sensor", sensorId.toString())
                .addField("co2_value", measure)
                .time(Instant.now().toEpochMilli(), WritePrecision.MS);

            api.writePoint(point);
        }
    }
}
