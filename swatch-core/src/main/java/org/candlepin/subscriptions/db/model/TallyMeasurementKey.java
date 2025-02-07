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
package org.candlepin.subscriptions.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.candlepin.subscriptions.json.Measurement;

/** Model object to the key for a given tally measurement */
@Embeddable
@Getter
@Setter
public class TallyMeasurementKey implements Serializable {

  @Enumerated(EnumType.STRING)
  @Column(name = "measurement_type")
  private HardwareMeasurementType measurementType;

  @Enumerated(EnumType.STRING)
  private Measurement.Uom uom;

  public TallyMeasurementKey() {
    /* intentionally left empty */
  }

  public TallyMeasurementKey(HardwareMeasurementType hardwareMeasurementType, Measurement.Uom uom) {
    this.measurementType = hardwareMeasurementType;
    this.uom = uom;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TallyMeasurementKey)) {
      return false;
    }
    TallyMeasurementKey that = (TallyMeasurementKey) o;
    return measurementType == that.measurementType && uom == that.uom;
  }

  @Override
  public int hashCode() {
    return Objects.hash(measurementType, uom);
  }

  @Override
  public String toString() {
    return "TallyMeasurementKey{" + "measurementType=" + measurementType + ", uom=" + uom + '}';
  }
}
