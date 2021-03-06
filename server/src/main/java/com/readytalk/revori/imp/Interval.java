/* Copyright (c) 2010-2012, Revori Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies. */

package com.readytalk.revori.imp;

import java.util.Comparator;

class Interval {
  public enum BoundType {
    Inclusive, Exclusive;

    static {
      Inclusive.opposite = Exclusive;
      Exclusive.opposite = Inclusive;
    }

    public BoundType opposite;
  }

  public static final Interval Unbounded = new Interval
    (Compare.Undefined, Compare.Undefined);

  public final Object low;
  public final BoundType lowBoundType;
  public final Object high;
  public final BoundType highBoundType;

  public Interval(Object low,
                  BoundType lowBoundType,
                  Object high,
                  BoundType highBoundType)
  {
    this.low = low;
    this.lowBoundType = lowBoundType;
    this.high = high;
    this.highBoundType = highBoundType;
  }

  public Interval(Object low,
                  Object high)
  {
    this(low, BoundType.Inclusive, high, BoundType.Inclusive);
  }

  public String toString() {
    return "interval[" + low + ":" + lowBoundType
      + " " + high + ":" + highBoundType + "]";
  }

  public static Interval intersection(Interval left,
                                      Interval right,
                                      Comparator comparator)
  {
    Object low;
    BoundType lowBoundType;
    int lowDifference = Compare.compare
      (left.low, false, right.low, false, comparator);

    if (lowDifference > 0) {
      low = left.low;
      lowBoundType = left.lowBoundType;
    } else if (lowDifference < 0) {
      low = right.low;
      lowBoundType = right.lowBoundType;
    } else {
      low = left.low;
      lowBoundType = (left.lowBoundType == BoundType.Exclusive
                      || right.lowBoundType == BoundType.Exclusive
                      ? BoundType.Exclusive : BoundType.Inclusive);
    }

    Object high;
    BoundType highBoundType;
    int highDifference = Compare.compare
      (left.high, true, right.high, true, comparator);

    if (highDifference > 0) {
      high = right.high;
      highBoundType = right.highBoundType;
    } else if (highDifference < 0) {
      high = left.high;
      highBoundType = left.highBoundType;
    } else {
      high = left.high;
      highBoundType = (left.highBoundType == BoundType.Exclusive
                      || right.highBoundType == BoundType.Exclusive
                      ? BoundType.Exclusive : BoundType.Inclusive);
    }

    return new Interval(low, lowBoundType, high, highBoundType);
  }

  public static Interval union(Interval left,
                               Interval right,
                               Comparator comparator)
  {
    Object low;
    BoundType lowBoundType;
    int lowDifference = Compare.compare
      (left.low, false, right.low, false, comparator);

    if (lowDifference > 0) {
      low = right.low;
      lowBoundType = right.lowBoundType;
    } else if (lowDifference < 0) {
      low = left.low;
      lowBoundType = left.lowBoundType;
    } else {
      low = left.low;
      lowBoundType = (left.lowBoundType == BoundType.Inclusive
                      || right.lowBoundType == BoundType.Inclusive
                      ? BoundType.Inclusive : BoundType.Exclusive);
    }

    Object high;
    BoundType highBoundType;
    int highDifference = Compare.compare
      (left.high, true, right.high, true, comparator);

    if (highDifference > 0) {
      high = left.high;
      highBoundType = left.highBoundType;
    } else if (highDifference < 0) {
      high = right.high;
      highBoundType = right.highBoundType;
    } else {
      high = left.high;
      highBoundType = (left.lowBoundType == BoundType.Inclusive
                       || right.lowBoundType == BoundType.Inclusive
                       ? BoundType.Inclusive : BoundType.Exclusive);
    }

    return new Interval(low, lowBoundType, high, highBoundType);
  }
}
