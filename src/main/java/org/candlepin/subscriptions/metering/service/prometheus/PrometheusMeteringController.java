/*
 * Copyright (c) 2020 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.metering.service.prometheus;

import io.micrometer.core.annotation.Timed;
import org.candlepin.subscriptions.event.EventController;
import org.candlepin.subscriptions.json.Event;
import org.candlepin.subscriptions.metering.MeteringEventFactory;
import org.candlepin.subscriptions.metering.MeteringException;
import org.candlepin.subscriptions.metering.MeteringProperties;
import org.candlepin.subscriptions.prometheus.ApiException;
import org.candlepin.subscriptions.prometheus.model.QueryResult;
import org.candlepin.subscriptions.prometheus.model.StatusType;
import org.candlepin.subscriptions.util.ApplicationClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * A controller class that defines the business logic related to any metrics that are gathered.
 */
@Component
public class PrometheusMeteringController {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMeteringController.class);

    private final PrometheusService prometheusService;
    private final EventController eventController;
    private final ApplicationClock clock;
    private final MeteringProperties meteringProperties;

    public PrometheusMeteringController(ApplicationClock clock, MeteringProperties meteringProperties,
        PrometheusService service, EventController eventController) {
        this.clock = clock;
        this.meteringProperties = meteringProperties;
        this.prometheusService = service;
        this.eventController = eventController;
    }

    @Timed("rhsm-subscriptions.metering.openshift")
    public void collectOpenshiftMetrics(String account, OffsetDateTime start, OffsetDateTime end)
        throws ApiException {

        // Reset the start/end dates to the top of the hour.
        // NOTE: If the prometheus query step changes, we will need to adjust this.
        QueryResult metricData = prometheusService.getOpenshiftData(account,
            clock.startOfHour(start), clock.startOfHour(end));

        if (StatusType.ERROR.equals(metricData.getStatus())) {
            throw new MeteringException(
                String.format("Unable to fetch openshift metrics: %s", metricData.getError())
            );
        }

        List<Event> events = new LinkedList<>();
        metricData.getData().getResult().forEach(r -> {
            Map<String, String> labels = r.getMetric();
            String clusterId = labels.getOrDefault("_id", "");
            String sla = labels.getOrDefault("support", "");

            // For the openshift metrics, we expect our results to be a 'matrix'
            // vector [(instant_time,value), ...] so we only look at the result's getValues()
            // data.
            r.getValues().forEach(measurement -> {
                BigDecimal time = measurement.get(0);
                BigDecimal value = measurement.get(1);
                Event event = MeteringEventFactory.openShiftClusterCores(account, clusterId, sla,
                    clock.dateFromUnix(time), value.doubleValue());
                events.add(event);

                if (log.isDebugEnabled()) {
                    log.debug(event.toString());
                }

                if (events.size() >= meteringProperties.getEventBatchSize()) {
                    log.info("Saving {} events", events.size());
                    eventController.saveAll(events);
                    events.clear();
                }
            });
        });

        // Flush the remainder
        if (!events.isEmpty()) {
            log.info("Saving remaining events: {}", events.size());
            eventController.saveAll(events);
        }
    }

}
