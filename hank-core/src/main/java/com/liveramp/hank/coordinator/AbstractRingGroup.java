/**
 *  Copyright 2011 LiveRamp
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.liveramp.hank.coordinator;

import java.util.SortedSet;
import java.util.TreeSet;

public abstract class AbstractRingGroup implements RingGroup {

  private final String name;

  protected AbstractRingGroup(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public SortedSet<Ring> getRingsSorted() {
    return new TreeSet<Ring>(getRings());
  }

  @Override
  public int compareTo(RingGroup other) {
    return name.compareTo(other.getName());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AbstractRingGroup)) {
      return false;
    }

    AbstractRingGroup that = (AbstractRingGroup)o;

    if (name != null ? !name.equals(that.name) : that.name != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return name != null ? name.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "AbstractRingGroup [name=" + getName()
        + ", domain group=" + (getDomainGroup() != null ? getDomainGroup().getName() : "null")
        + "]";
  }
}
