/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.error;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Handler is responsible for storing and processing api errors reported by the aggregator.
 * Whenever an important part of the API fails, aggregator should call {@link #pushError(String, Throwable)},
 * when the error is resolved - {@link #resolveError(String)}
 *
 * Then, {@link #verifyAPIState()} is called after the data processing, and if there are errors - the RuntimeException is thrown
 * with the details about the failed API sections and top error cause.
 *
 * @since 1.1.1
 * @author Maksym.Rossiitsev/Symphony Team
 * */
public class APIStateReportHandler {
    /**
     * Map of api sections and corresponding instances of {@link Throwable}
     * */
    private final Map<String, Throwable> apiErrors = new ConcurrentHashMap<>();

    /**
     * Add an error to the {@link #apiErrors}
     *
     * @param apiSection api section identifier (property group)
     * @param error instance of Throwable thrown
     * */
    public void pushError(String apiSection, Throwable error) {
        apiErrors.put(apiSection, error);
    }

    /**
     * Remove an error from {@link #apiErrors}
     *
     * @param apiSection API section name to remove from {@link #apiErrors}
     * */
    public void resolveError(String apiSection) {
        apiErrors.remove(apiSection);
    }

    /**
     * Process {@link #apiErrors} contents and throw an error if any errors remain.
     *
     * @throws RuntimeException if {@link #apiErrors} is not empty
     * */
    public void verifyAPIState() {
        if (apiErrors.isEmpty()) {
            return;
        }
        String apiSections = String.join(",", apiErrors.keySet());
        Throwable error = apiErrors.values().iterator().next();
        String errorText = "N/A";
        if (error != null) {
            errorText = error.getMessage();
        }
        throw new RuntimeException(String.format("Unable to process requested API sections: [%s], error reported: [%s]", apiSections, errorText));
    }
}
