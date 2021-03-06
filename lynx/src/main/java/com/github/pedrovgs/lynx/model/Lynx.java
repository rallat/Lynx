/*
 * Copyright (C) 2015 Pedro Vicente Gomez Sanchez.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pedrovgs.lynx.model;

import com.github.pedrovgs.lynx.LynxConfig;
import com.github.pedrovgs.lynx.exception.IllegalTraceException;
import java.util.LinkedList;
import java.util.List;

/**
 * Main business logic class for this project. Lynx responsibility is related to listen Logcat
 * events and notify it to the Lynx listeners transforming all the information from a plain String
 * to a Trace with all the information needed.
 *
 * Given a LynxConfig object the sample rating used to notify Lynx clients about new traces can be
 * modified on demand. LynxConfig object will be used to filter traces if any filter has been
 * previously configured.
 *
 * @author Pedro Vicente Gomez Sanchez.
 */
public class Lynx {

  private Logcat logcat;
  private final MainThread mainThread;
  private final TimeProvider timeProvider;
  private final List<Trace> tracesToNotify;
  private final List<Listener> listeners;

  private LynxConfig lynxConfig = new LynxConfig();
  private long lastNotificationTime;

  public Lynx(Logcat logcat, MainThread mainThread, TimeProvider timeProvider) {
    this.listeners = new LinkedList<Listener>();
    this.tracesToNotify = new LinkedList<Trace>();
    this.logcat = logcat;
    this.mainThread = mainThread;
    this.timeProvider = timeProvider;
  }

  /**
   * Indicates a custom LynxConfig object.
   */
  public synchronized void setConfig(LynxConfig lynxConfig) {
    this.lynxConfig = lynxConfig;
  }

  /**
   * Returns a copy of the current LynxConfig object.
   */
  public LynxConfig getConfig() {
    return (LynxConfig) lynxConfig.clone();
  }

  /**
   * Configures a Logcat. Listeners and initialize Logcat dependency to read logcat traces.
   */
  public void startReading() {
    logcat.setListener(new Logcat.Listener() {
      @Override public void onTraceRead(String logcatTrace) {
        try {
          addTraceToTheBuffer(logcatTrace);
        } catch (IllegalTraceException e) {
          return;
        }
        notifyNewTraces();
      }
    });
    if (!logcat.isAlive()) {
      logcat.start();
    }
  }

  /**
   * Stops Logcat dependency to stop receiving logcat traces.
   */
  public void stopReading() {
    logcat.stopReading();
    logcat.interrupt();
  }

  /**
   * Stops the configured Logcat dependency and creates a clone to restart using Logcat and
   * LogcatListener configured previously.
   */
  public void restart() {
    Logcat.Listener previousListener = logcat.getListener();
    logcat.stopReading();
    logcat.interrupt();
    logcat = (Logcat) logcat.clone();
    logcat.setListener(previousListener);
    lastNotificationTime = 0;
    logcat.start();
  }

  /**
   * Adds a Listener to the listeners collection to be notified with new Trace objects.
   */
  public void registerListener(Listener lynxPresenter) {
    listeners.add(lynxPresenter);
  }

  /**
   * Removes a Listener to the listeners collection.
   */
  public void unregisterListener(Listener lynxPresenter) {
    listeners.remove(lynxPresenter);
  }

  private synchronized void addTraceToTheBuffer(String logcatTrace) throws IllegalTraceException {
    if (shouldAddTrace(logcatTrace)) {
      Trace trace = Trace.fromString(logcatTrace);
      tracesToNotify.add(trace);
    }
  }

  private boolean shouldAddTrace(String logcatTrace) {
    boolean hasFilterConfigured = lynxConfig.hasFilter();
    return !hasFilterConfigured || traceMatchesFilter(logcatTrace);
  }

  private synchronized boolean traceMatchesFilter(String logcatTrace) {
    TraceLevel levelFilter = lynxConfig.getFilterTraceLevel();
    String filter = lynxConfig.getFilter().toLowerCase();
    String logcatTraceLowercase = logcatTrace.toLowerCase();
    return logcatTraceLowercase.contains(filter) && containsTraceLevel(logcatTrace,
        levelFilter);
  }

  private boolean containsTraceLevel(String logcatTrace, TraceLevel levelFilter) {
    return levelFilter.equals(TraceLevel.VERBOSE) || hasTraceLevelEqualOrHigher(logcatTrace,
        levelFilter);
  }

  private boolean hasTraceLevelEqualOrHigher(String logcatTrace, TraceLevel levelFilter) {
    TraceLevel level =
        TraceLevel.getTraceLevel(logcatTrace.charAt(Trace.TRACE_LEVEL_INDEX));
    return level.ordinal() >= levelFilter.ordinal();
  }

  private void notifyNewTraces() {
    if (shouldNotifyListeners()) {
      final List<Trace> traces = new LinkedList<Trace>(tracesToNotify);
      tracesToNotify.clear();
      notifyListeners(traces);
    }
  }

  private boolean shouldNotifyListeners() {
    long now = timeProvider.getCurrentTimeMillis();
    long timeFromLastNotification = now - lastNotificationTime;
    boolean hasTracesToNotify = tracesToNotify.size() > 0;
    return timeFromLastNotification > lynxConfig.getSamplingRate() && hasTracesToNotify;
  }

  private void notifyListeners(final List<Trace> traces) {
    mainThread.post(new Runnable() {
      @Override public void run() {
        for (Listener listener : listeners) {
          listener.onNewTraces(traces);
        }
      }
    });
    lastNotificationTime = timeProvider.getCurrentTimeMillis();
  }

  public interface Listener {

    void onNewTraces(List<Trace> traces);
  }
}
