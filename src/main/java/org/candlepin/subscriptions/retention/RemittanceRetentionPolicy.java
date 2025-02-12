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
package org.candlepin.subscriptions.retention;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.candlepin.subscriptions.util.ApplicationClock;
import org.springframework.stereotype.Component;

/**
 * Calculates cutoff dates given a retention policy.
 *
 * <p>Note that retention policy is defined in terms of how many *complete* units of time are kept.
 * For example, if the retention policy is 3 months, then the previous 3 months are retained, in
 * addition to the current incomplete month.
 */
@Component
public class RemittanceRetentionPolicy {

  private final ApplicationClock applicationClock;
  private final RemittanceRetentionPolicyProperties config;

  public RemittanceRetentionPolicy(
      ApplicationClock applicationClock, RemittanceRetentionPolicyProperties config) {
    this.applicationClock = applicationClock;
    this.config = config;
  }

  /**
   * Get the cutoff date for {@link
   * org.candlepin.subscriptions.db.model.BillableUsageRemittanceEntity} records to be kept.
   *
   * <p>Any remittance of this granularity older than the cutoff date should be removed.
   *
   * @return cutoff date (i.e. dates less than this are candidates for removal), or null
   */
  public OffsetDateTime getCutoffDate() {
    if (Objects.isNull(config.getDuration())) {
      return null;
    }
    return applicationClock.now().minus(config.getDuration());
  }
}
