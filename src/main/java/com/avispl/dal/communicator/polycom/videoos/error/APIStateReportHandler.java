/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.error;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks per-group API failures to allow graceful degradation when individual
 * device endpoints are unavailable.
 *
 * @author Maksym.Rossiitsev/Symphony Team
 */
public class APIStateReportHandler {

    private final Map<String, Throwable> apiErrors = new ConcurrentHashMap<>();

    public void pushError(String group, Throwable error) {
        apiErrors.put(group, error);
    }

    public void resolveError(String group) {
        apiErrors.remove(group);
    }

    /**
     * Emits a warning via the supplied consumer for each currently degraded group.
     * Called at the end of each monitoring cycle.
     *
     * <pre>stateReporter.verifyAPIState(msg -> logger.warn(msg));</pre>
     */
    public void verifyAPIState(Consumer<String> warn) {
        apiErrors.forEach((group, error) ->
            warn.accept("Degraded group [" + group + "]: " + error.getMessage())
        );
    }
}
