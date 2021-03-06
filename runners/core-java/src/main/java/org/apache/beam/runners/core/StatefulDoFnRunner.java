/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.core;

import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.NonMergingWindowFn;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.TimeDomain;
import org.apache.beam.sdk.util.WindowTracing;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.joda.time.Instant;

/**
 * A customized {@link DoFnRunner} that handles late data dropping and garbage collection for
 * stateful {@link DoFn DoFns}. It registers a GC timer in {@link #processElement(WindowedValue)}
 * and does cleanup in {@link #onTimer(String, BoundedWindow, Instant, TimeDomain)}
 *
 * @param <InputT> the type of the {@link DoFn} (main) input elements
 * @param <OutputT> the type of the {@link DoFn} (main) output elements
 */
public class StatefulDoFnRunner<InputT, OutputT, W extends BoundedWindow>
    implements DoFnRunner<InputT, OutputT> {

  public static final String DROPPED_DUE_TO_LATENESS_COUNTER = "StatefulParDoDropped";

  private final DoFnRunner<InputT, OutputT> doFnRunner;
  private final WindowingStrategy<?, ?> windowingStrategy;
  private final Aggregator<Long, Long> droppedDueToLateness;
  private final CleanupTimer cleanupTimer;
  private final StateCleaner stateCleaner;

  public StatefulDoFnRunner(
      DoFnRunner<InputT, OutputT> doFnRunner,
      WindowingStrategy<?, ?> windowingStrategy,
      CleanupTimer cleanupTimer,
      StateCleaner<W> stateCleaner,
      Aggregator<Long, Long> droppedDueToLateness) {
    this.doFnRunner = doFnRunner;
    this.windowingStrategy = windowingStrategy;
    this.cleanupTimer = cleanupTimer;
    this.stateCleaner = stateCleaner;
    WindowFn<?, ?> windowFn = windowingStrategy.getWindowFn();
    rejectMergingWindowFn(windowFn);
    this.droppedDueToLateness = droppedDueToLateness;
  }

  private void rejectMergingWindowFn(WindowFn<?, ?> windowFn) {
    if (!(windowFn instanceof NonMergingWindowFn)) {
      throw new UnsupportedOperationException(
          "MergingWindowFn is not supported for stateful DoFns, WindowFn is: "
              + windowFn);
    }
  }

  @Override
  public void startBundle() {
    doFnRunner.startBundle();
  }

  @Override
  public void processElement(WindowedValue<InputT> input) {

    // StatefulDoFnRunner always observes windows, so we need to explode
    for (WindowedValue<InputT> value : input.explodeWindows()) {

      BoundedWindow window = value.getWindows().iterator().next();

      if (isLate(window)) {
        // The element is too late for this window.
        droppedDueToLateness.addValue(1L);
        WindowTracing.debug(
            "StatefulDoFnRunner.processElement: Dropping element at {}; window:{} "
                + "since too far behind inputWatermark:{}",
            input.getTimestamp(), window, cleanupTimer.currentInputWatermarkTime());
      } else {
        cleanupTimer.setForWindow(window);
        doFnRunner.processElement(value);
      }
    }
  }

  private boolean isLate(BoundedWindow window) {
    Instant gcTime = window.maxTimestamp().plus(windowingStrategy.getAllowedLateness());
    Instant inputWM = cleanupTimer.currentInputWatermarkTime();
    return gcTime.isBefore(inputWM);
  }

  @Override
  public void onTimer(
      String timerId, BoundedWindow window, Instant timestamp, TimeDomain timeDomain) {
    if (cleanupTimer.isForWindow(timerId, window, timestamp, timeDomain)) {
      stateCleaner.clearForWindow(window);
      // There should invoke the onWindowExpiration of DoFn
    } else {
      // An event-time timer can never be late because we don't allow setting timers after GC time.
      // Ot can happen that a processing-time time fires for a late window, we need to ignore
      // this.
      if (!timeDomain.equals(TimeDomain.EVENT_TIME) && isLate(window)) {
        // don't increment the dropped counter, only do that for elements
        WindowTracing.debug(
            "StatefulDoFnRunner.onTimer: Ignoring processing-time timer at {}; window:{} "
                + "since window is too far behind inputWatermark:{}",
            timestamp, window, cleanupTimer.currentInputWatermarkTime());
      } else {
        doFnRunner.onTimer(timerId, window, timestamp, timeDomain);
      }
    }
  }

  @Override
  public void finishBundle() {
    doFnRunner.finishBundle();
  }

  /**
   * A cleaner for deciding when to clean state of window.
   *
   * <p>A runner might either (a) already know that it always has a timer set
   * for the expiration time or (b) not need a timer at all because it is
   * a batch runner that discards state when it is done.
   */
  public interface CleanupTimer {

    /**
     * Return the current, local input watermark timestamp for this computation
     * in the {@link TimeDomain#EVENT_TIME} time domain.
     */
    Instant currentInputWatermarkTime();

    /**
     * Set the garbage collect time of the window to timer.
     */
    void setForWindow(BoundedWindow window);

    /**
     * Checks whether the given timer is a cleanup timer for the window.
     */
    boolean isForWindow(
        String timerId,
        BoundedWindow window,
        Instant timestamp,
        TimeDomain timeDomain);

  }

  /**
   * A cleaner to clean all states of the window.
   */
  public interface StateCleaner<W extends BoundedWindow> {

    void clearForWindow(W window);
  }
}
