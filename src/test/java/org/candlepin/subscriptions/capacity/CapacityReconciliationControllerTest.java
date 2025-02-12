/*
 * Copyright Red Hat, Inc.
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
package org.candlepin.subscriptions.capacity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.candlepin.subscriptions.capacity.files.ProductDenylist;
import org.candlepin.subscriptions.db.OfferingRepository;
import org.candlepin.subscriptions.db.SubscriptionRepository;
import org.candlepin.subscriptions.db.model.Offering;
import org.candlepin.subscriptions.db.model.Subscription;
import org.candlepin.subscriptions.db.model.SubscriptionMeasurement;
import org.candlepin.subscriptions.db.model.SubscriptionProductId;
import org.candlepin.subscriptions.resource.ResourceUtils;
import org.candlepin.subscriptions.utilization.api.model.MetricId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"capacity-ingress", "test"})
class CapacityReconciliationControllerTest {
  private static final String SOCKETS = MetricId.SOCKETS.toString().toUpperCase();
  private static final String CORES = MetricId.CORES.toString().toUpperCase();

  private static final OffsetDateTime NOW = OffsetDateTime.now();

  @Autowired CapacityReconciliationController capacityReconciliationController;

  @MockBean ProductDenylist denylist;

  @MockBean OfferingRepository offeringRepository;

  @MockBean SubscriptionRepository subscriptionRepository;

  @MockBean CapacityProductExtractor capacityProductExtractor;

  @MockBean
  KafkaTemplate<String, ReconcileCapacityByOfferingTask> reconcileCapacityByOfferingKafkaTemplate;

  @AfterEach
  void afterEach() {
    reset(subscriptionRepository, capacityProductExtractor, offeringRepository, denylist);
  }

  private static SubscriptionMeasurement createMeasurement(
      Subscription newSubscription, String measurementType, String metricId, double value) {
    var m = new SubscriptionMeasurement();
    m.setSubscription(newSubscription);
    m.setMeasurementType(measurementType);
    m.setMetricId(metricId);
    m.setValue(value);
    return m;
  }

  @Test
  void shouldAddNewMeasurementsAndProductIdsIfNotAlreadyExisting() {
    List<String> productIds = List.of("RHEL");
    Offering offering =
        Offering.builder()
            .productIds(Set.of(45))
            .cores(42)
            .hypervisorCores(43)
            .sockets(44)
            .hypervisorSockets(45)
            .sku("MCT3718")
            .build();

    Subscription newSubscription = createSubscription("456", 10);
    newSubscription.setOffering(offering);

    when(denylist.productIdMatches(any())).thenReturn(false);
    when(capacityProductExtractor.getProducts(offering.getProductIdsAsStrings()))
        .thenReturn(new HashSet<>(productIds));

    capacityReconciliationController.reconcileCapacityForSubscription(newSubscription);

    assertThat(
        newSubscription.getSubscriptionMeasurements(),
        containsInAnyOrder(
            createMeasurement(newSubscription, "PHYSICAL", CORES, 420.0),
            createMeasurement(newSubscription, "HYPERVISOR", CORES, 430.0),
            createMeasurement(newSubscription, "PHYSICAL", SOCKETS, 440.0),
            createMeasurement(newSubscription, "HYPERVISOR", SOCKETS, 450.0)));
    assertThat(
        newSubscription.getSubscriptionProductIds().stream()
            .map(SubscriptionProductId::getProductId)
            .collect(Collectors.toSet()),
        containsInAnyOrder("RHEL"));
  }

  @Test
  void shouldUpdateCapacitiesIfThereAreChanges() {

    Set<String> productIds = Set.of("RHEL", "RHEL Workstation");
    Offering updatedOffering =
        Offering.builder().productIds(Set.of(45, 25)).sku("MCT3718").cores(20).sockets(40).build();

    Subscription updatedSubscription = createSubscription("456", 10);

    updatedSubscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(updatedSubscription, "PHYSICAL", SOCKETS, 15.0));
    updatedSubscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(updatedSubscription, "PHYSICAL", CORES, 10.0));
    SubscriptionProductId subscriptionProductId = new SubscriptionProductId();
    subscriptionProductId.setProductId("RHEL");
    updatedSubscription.addSubscriptionProductId(subscriptionProductId);
    updatedSubscription.setOffering(updatedOffering);

    when(denylist.productIdMatches(any())).thenReturn(false);
    when(capacityProductExtractor.getProducts(updatedOffering.getProductIdsAsStrings()))
        .thenReturn(productIds);

    capacityReconciliationController.reconcileCapacityForSubscription(updatedSubscription);

    assertThat(
        updatedSubscription.getSubscriptionMeasurements(),
        containsInAnyOrder(
            createMeasurement(updatedSubscription, "PHYSICAL", CORES, 200.0),
            createMeasurement(updatedSubscription, "PHYSICAL", SOCKETS, 400.0)));
    assertEquals(
        updatedSubscription.getSubscriptionProductIds().stream()
            .map(SubscriptionProductId::getProductId)
            .collect(Collectors.toSet()),
        productIds);
  }

  @Test
  void shouldRemoveAllCapacitiesWhenProductIsOnDenylist() {
    Set<String> productIds = Set.of("RHEL", "RHEL Workstation");
    Offering updatedOffering =
        Offering.builder().productIds(Set.of(45, 25)).sku("MCT3718").cores(20).sockets(40).build();

    Subscription updatedSubscription = createSubscription("456", 10);

    updatedSubscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(updatedSubscription, "PHYSICAL", SOCKETS, 15.0));
    updatedSubscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(updatedSubscription, "PHYSICAL", CORES, 10.0));
    SubscriptionProductId subscriptionProductId = new SubscriptionProductId();
    subscriptionProductId.setProductId("RHEL");
    updatedSubscription.addSubscriptionProductId(subscriptionProductId);
    updatedSubscription.setOffering(updatedOffering);

    when(denylist.productIdMatches(any())).thenReturn(true);
    when(capacityProductExtractor.getProducts(updatedOffering.getProductIdsAsStrings()))
        .thenReturn(productIds);

    capacityReconciliationController.reconcileCapacityForSubscription(updatedSubscription);

    assertTrue(updatedSubscription.getSubscriptionMeasurements().isEmpty());
    assertTrue(updatedSubscription.getSubscriptionProductIds().isEmpty());
  }

  @Test
  void shouldAddNewCapacitiesAndRemoveAllStaleCapacities() {
    Set<String> productIds = Set.of("RHEL");
    Offering offering = Offering.builder().productIds(Set.of(45)).sku("MCT3718").cores(42).build();
    Subscription subscription = createSubscription("456", 10);
    Set<String> staleProductIds = Set.of("STALE RHEL", "STALE RHEL Workstation");
    staleProductIds.stream()
        .map(
            productId -> {
              var subscriptionProductId = new SubscriptionProductId();
              subscriptionProductId.setProductId(productId);
              return subscriptionProductId;
            })
        .forEach(subscription::addSubscriptionProductId);

    subscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(subscription, "PHYSICAL", CORES, 10.0));
    subscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(subscription, "PHYSICAL", SOCKETS, 15.0));
    subscription.setOffering(offering);

    when(denylist.productIdMatches(any())).thenReturn(false);
    when(capacityProductExtractor.getProducts(offering.getProductIdsAsStrings()))
        .thenReturn(productIds);

    capacityReconciliationController.reconcileCapacityForSubscription(subscription);

    assertThat(
        subscription.getSubscriptionMeasurements(),
        containsInAnyOrder(createMeasurement(subscription, "PHYSICAL", CORES, 420.0)));
    assertThat(
        subscription.getSubscriptionProductIds().stream()
            .map(SubscriptionProductId::getProductId)
            .collect(Collectors.toSet()),
        containsInAnyOrder("RHEL"));
  }

  @Test
  void shouldReconcileCapacityWithinLimitForOrgAndQueueTaskForNext() {

    Offering offering = Offering.builder().productIds(Set.of(45)).sku("MCT3718").build();

    Page<Subscription> subscriptions = mock(Page.class);
    when(subscriptions.hasNext()).thenReturn(true);

    when(subscriptionRepository.findByOfferingSku(
            "MCT3718", ResourceUtils.getPageable(0, 2, Sort.by("subscriptionId"))))
        .thenReturn(subscriptions);
    capacityReconciliationController.reconcileCapacityForOffering(offering.getSku(), 0, 2);
    verify(reconcileCapacityByOfferingKafkaTemplate)
        .send(
            "platform.rhsm-subscriptions.capacity-reconcile",
            ReconcileCapacityByOfferingTask.builder().sku("MCT3718").offset(2).limit(2).build());
  }

  @Test
  void enqueueShouldOnlyCreateKafkaMessage() {
    // Some clients (example, OfferingSyncController) should not wait for capacities to reconcile.
    // In that case, the client should be able to enqueue the first capacity reconciliation page,
    // rather than have it be worked on immediately.
    capacityReconciliationController.enqueueReconcileCapacityForOffering("MCT3718");
    verify(reconcileCapacityByOfferingKafkaTemplate)
        .send(
            "platform.rhsm-subscriptions.capacity-reconcile",
            ReconcileCapacityByOfferingTask.builder().sku("MCT3718").offset(0).limit(100).build());
    verifyNoInteractions(subscriptionRepository);
  }

  @Test
  void shouldNotAttemptToCreateDuplicateMeasurementsWhenNoChanges() {
    Set<String> productIds = Set.of("RHEL");
    Offering offering = Offering.builder().productIds(Set.of(45)).sku("MCT3718").cores(42).build();
    Subscription subscription = createSubscription("456", 1);

    subscription
        .getSubscriptionMeasurements()
        .add(createMeasurement(subscription, "PHYSICAL", CORES, 42.0));
    subscription.setOffering(offering);

    when(denylist.productIdMatches(any())).thenReturn(false);
    when(capacityProductExtractor.getProducts(offering.getProductIdsAsStrings()))
        .thenReturn(productIds);

    capacityReconciliationController.reconcileCapacityForSubscription(subscription);

    assertThat(
        subscription.getSubscriptionMeasurements(),
        containsInAnyOrder(createMeasurement(subscription, "PHYSICAL", CORES, 42.0)));
  }

  private Subscription createSubscription(String subId, int quantity) {
    return Subscription.builder()
        .orgId("123")
        .subscriptionId(subId)
        .quantity(quantity)
        .startDate(NOW)
        .endDate(NOW.plusDays(30))
        .build();
  }
}
