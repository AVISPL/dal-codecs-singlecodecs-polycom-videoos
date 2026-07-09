/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos;

import com.avispl.dal.communicator.polycom.videoos.data.ApiUri;
import com.avispl.dal.communicator.polycom.videoos.data.ControlKey;
import com.avispl.dal.communicator.polycom.videoos.data.PropertyGroup;
import com.avispl.dal.communicator.polycom.videoos.data.Values;
import com.avispl.dal.communicator.polycom.videoos.error.APIStateReportHandler;
import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.control.call.CallController;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.control.call.PopupMessage;
import com.avispl.symphony.api.dal.dto.monitor.AudioChannelStats;
import com.avispl.symphony.api.dal.dto.monitor.CallStats;
import com.avispl.symphony.api.dal.dto.monitor.ContentChannelStats;
import com.avispl.symphony.api.dal.dto.monitor.EndpointStatistics;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.RegistrationStatus;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.VideoChannelStats;
import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;

/**
 * Poly VideoOS adapter for G7500, Studio X70/X50/X30 devices.
 * Communicates via the Poly VideoOS REST API (v3.7.0+).
 *
 * Authentication uses a session cookie and XSRF token pair. The session
 * endpoint and XSRF token behavior differ from the public API documentation
 * — see {@link PolycomVideoOSInterceptor} and {@link #authenticate()}.
 *
 * @author Maksym.Rossiitsev/Symphony Team
 */
public class PolycomVideoOS extends RestCommunicator implements CallController, Monitorable, Controller {

    @FunctionalInterface
    private interface GroupFetcher {
        /**
         * Fetches one property group's data from the device.
         *
         * @param props destination map for monitored properties
         * @param controls destination list for controllable properties
         * @throws Exception if the underlying device API call fails
         */
        void fetch(Map<String, String> props, List<AdvancedControllableProperty> controls) throws Exception;
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int    CONTROL_COOLDOWN_MS    = 5_000;
    private static final int    REBOOT_GRACE_MS        = 200_000;
    private static final int    GROUP_FETCH_TIMEOUT_S  = 30;
    private static final int    THREAD_POOL_SIZE       = 12;
    private static final int    MAX_DIAL_POLL_ATTEMPTS = 5;
    private static final String CALL_ID_TEMPLATE       = "%s:%s:%s:%s";

    // DateTimeFormatter is immutable and thread-safe; one instance for the adapter lifetime
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // -------------------------------------------------------------------------
    // Auth interceptor
    // -------------------------------------------------------------------------

    private final ClientHttpRequestInterceptor interceptor = new PolycomVideoOSInterceptor();

    /**
     * Intercepts every HTTP response to capture the rotating XSRF token, and to transparently
     * re-authenticate and retry a request when the session has expired (HTTP 403 on a GET).
     */
    private class PolycomVideoOSInterceptor implements ClientHttpRequestInterceptor {

        /**
         * Captures the XSRF token from every response, then re-authenticates and retries the
         * request if it failed with 403 due to an expired session (session creation and
         * non-GET requests are excluded, since a stale session can't affect the login call and
         * retrying a write is unsafe).
         *
         * @param request the outgoing HTTP request
         * @param body the request body bytes
         * @param execution the request execution chain
         * @return the response from the retried request, or the original response if no retry was needed or possible
         * @throws IOException if the underlying request execution fails
         */
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            captureXsrfToken(response);

            boolean isSessionEndpoint = request.getURI().getPath().endsWith(ApiUri.SESSION);
            boolean isReadRequest     = HttpMethod.GET.equals(request.getMethod());
            if (response.getStatusCode().value() != 403 || isSessionEndpoint || !isReadRequest) {
                return response;
            }

            // tryLock: if we win the race, re-auth then retry.
            // If another thread holds the lock, wait for it to finish then retry once.
            if (authLock.tryLock()) {
                try {
                    return reAuthAndRetry(request, body, execution, response);
                } finally {
                    authLock.unlock();
                }
            } else {
                authLock.lock();
                authLock.unlock();
                try {
                    return execution.execute(request, body);
                } catch (IOException e) {
                    logger.error("Request retry after concurrent re-auth failed.", e);
                    return response;
                }
            }
        }

        /**
         * Re-authenticates and retries the given request once. If re-authentication fails or
         * the device is unreachable, the session is invalidated and the original (403) response
         * is returned instead of throwing.
         *
         * @param request the request to retry
         * @param body the request body bytes
         * @param execution the request execution chain
         * @param original the original 403 response, returned as a fallback if re-auth fails
         * @return the retried response, or {@code original} if re-authentication failed
         * @throws IOException if the retried request execution fails
         */
        private ClientHttpResponse reAuthAndRetry(HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution, ClientHttpResponse original) throws IOException {
            try {
                authenticate();
            } catch (ResourceNotReachableException e) {
                logger.error("Device unreachable during re-auth.", e);
                invalidateSession();
                try { disconnect(); } catch (Exception ignored) {}
                return original;
            } catch (Exception e) {
                logger.error("Re-authentication failed.", e);
                invalidateSession();
                return original;
            }
            try {
                return execution.execute(request, body);
            } catch (IOException e) {
                logger.error("Request retry after re-auth failed.", e);
                throw e;
            }
        }

        /**
         * Extracts the rotating {@code XSRF-TOKEN} cookie from a response's {@code Set-Cookie}
         * headers, if present, and stores it for use on subsequent requests.
         *
         * @param response the HTTP response to inspect
         */
        private void captureXsrfToken(ClientHttpResponse response) {
            List<String> cookies = response.getHeaders().get("set-cookie");
            if (cookies == null) {
                return;
            }
            for (String cookie : cookies) {
                if (cookie.startsWith("XSRF-TOKEN")) {
                    int eq   = cookie.indexOf('=');
                    int semi = cookie.indexOf(';');
                    if (eq >= 0 && semi > eq) {
                        xsrfToken = cookie.substring(eq + 1, semi);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private volatile String  sessionId;
    private volatile String  xsrfToken;
    private volatile boolean authFailed;
    private volatile String  selectedApp;   // written by controlProperty, read by fetchApplications

    private long providerSelectionTimestamp;
    private int  appProviderSelectionTimeoutMin = 5;

    private final Map<String, String>                cachedProperties = new ConcurrentHashMap<>();
    private final List<AdvancedControllableProperty> cachedControls   = new ArrayList<>();

    private List<String> displayPropertyGroups = new ArrayList<>(Collections.singletonList("All"));
    private int          defaultCallRate        = 1920;

    private long    lastPollTimestamp;
    private long    lastControlTimestamp;
    private boolean refreshInProgress;
    private int     pollingIntervalMs = 60_000;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock authLock  = new ReentrantLock();

    private ExecutorService executor;
    private Properties      adapterProperties;
    private long            initTimestamp;

    private final APIStateReportHandler stateReporter = new APIStateReportHandler();

    private final EndpointStatistics localEndpointStatistics = new EndpointStatistics();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates the adapter and enables trust-all TLS, since Poly VideoOS devices present a
     * self-signed certificate by default.
     */
    public PolycomVideoOS() {
        setTrustAllCertificates(true);
    }

    /**
     * Initializes adapter state: records the startup timestamp, loads the adapter version
     * metadata, and starts the fixed thread pool used to fetch property groups concurrently.
     *
     * @throws Exception if the superclass initialization or version metadata loading fails
     */
    @Override
    protected void internalInit() throws Exception {
        super.internalInit();
        initTimestamp     = System.currentTimeMillis();
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    /**
     * Tears down the adapter: closes the device session (best-effort) and shuts down the
     * group-fetch thread pool.
     */
    @Override
    protected void internalDestroy() {
        if (sessionId != null) {
            try { doDelete(ApiUri.SESSION); } catch (Exception ignored) {}
            invalidateSession();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.internalDestroy();
    }

    /**
     * Registers {@link #interceptor} on the shared {@link RestTemplate} exactly once, so every
     * request carries session/XSRF headers and every response is inspected for a rotated token.
     *
     * @return the REST template used for all device requests
     * @throws Exception if the superclass fails to build the template
     */
    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate template = super.obtainRestTemplate();
        if (!template.getInterceptors().contains(interceptor)) {
            template.getInterceptors().add(interceptor);
        }
        return template;
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    /**
     * Logs in to the device, replacing any existing session. Posts credentials to the session
     * endpoint and stores the returned session ID; the XSRF token is captured separately by
     * {@link PolycomVideoOSInterceptor#captureXsrfToken}.
     *
     * @throws Exception if the login request fails; specifically a {@link FailedLoginException}
     *                    if the device rejects the credentials
     */
    @Override
    protected void authenticate() throws Exception {
        if (sessionId != null) {
            try { doDelete(ApiUri.SESSION); } catch (Exception ignored) {}
            invalidateSession();
        }
        disconnect();

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("user", getLogin());
        body.put("password", getPassword());

        JsonNode response = doPost(ApiUri.SESSION, body, JsonNode.class);
        if (response == null || !response.path("success").asBoolean(false)) {
            throw new FailedLoginException("Authentication failed — check credentials.");
        }
        sessionId  = response.path("session").path("sessionId").asText(null);
        authFailed = false;
    }

    /**
     * Adds the session and XSRF cookies/headers required by every authenticated request.
     * Skipped for the session-creation call itself, since no session exists yet at that point.
     *
     * @param method the HTTP method of the outgoing request
     * @param uri the request URI
     * @param headers the headers to augment
     * @return the augmented headers
     * @throws Exception if the superclass header logic fails
     */
    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod method, String uri, HttpHeaders headers) throws Exception {
        boolean isSessionCreate = HttpMethod.POST.equals(method) && uri.equals(ApiUri.SESSION);
        if (!isSessionCreate && sessionId != null && xsrfToken != null) {
            headers.set("Cookie", "session_id=" + sessionId + "; XSRF-TOKEN=" + xsrfToken);
            headers.set("x-xsrf-token", xsrfToken);
        }
        return super.putExtraRequestHeaders(method, uri, headers);
    }

    /** Clears the cached session ID and XSRF token and marks authentication as failed, forcing re-login on the next request. */
    private void invalidateSession() {
        sessionId  = null;
        xsrfToken  = null;
        authFailed = true;
    }

    /**
     * Re-authenticates if there is no valid session (no session ID/XSRF token yet, or a
     * previous request marked authentication as failed).
     *
     * @throws Exception if re-authentication fails
     */
    private void ensureAuthenticated() throws Exception {
        if (authFailed || sessionId == null || xsrfToken == null) {
            authenticate();
        }
    }

    // -------------------------------------------------------------------------
    // Configuration setters
    // -------------------------------------------------------------------------

    private static final Map<String, List<String>> PROPERTY_GROUP_PRESETS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("Default",    Collections.singletonList("All"));
        m.put("AppMode",    Arrays.asList("ActiveSessions", "Applications", "Audio", "Collaboration", "Microphone", "System", "SystemStatus"));
        m.put("DeviceMode", Arrays.asList("ActiveSessions", "Applications", "Audio", "Microphone", "Peripherals", "System", "SystemStatus", "Conferences"));
        PROPERTY_GROUP_PRESETS = Collections.unmodifiableMap(m);
    }

    /**
     * Sets which property groups are collected and reported, from a comma-separated list
     * (e.g. {@code "Audio,Camera,System"}, or {@code "All"} for every group).
     *
     * @param value comma-separated group names; surrounding whitespace is trimmed and empty entries are ignored
     */
    public void setDisplayPropertyGroups(String value) {
        this.displayPropertyGroups = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Sets {@link #displayPropertyGroups} to one of the predefined combinations in
     * {@link #PROPERTY_GROUP_PRESETS} ({@code Default}, {@code AppMode}, {@code DeviceMode}).
     * Unknown preset names are logged and ignored, leaving the current configuration unchanged.
     *
     * @param preset the preset name to apply
     */
    public void setDisplayPropertyGroupsPreset(String preset) {
        List<String> groups = PROPERTY_GROUP_PRESETS.get(preset);
        if (groups == null) {
            logger.warn("Unknown displayPropertyGroupsPreset '" + preset + "'. Valid values: " + PROPERTY_GROUP_PRESETS.keySet());
            return;
        }
        this.displayPropertyGroups = groups.stream().sorted().collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Monitorable
    // -------------------------------------------------------------------------

    /**
     * Returns the adapter's current {@link ExtendedStatistics} and {@link EndpointStatistics}.
     * If the cache is still fresh, a control was applied recently, or a refresh is already in
     * progress, the cached snapshot is returned immediately without contacting the device.
     * Otherwise, every enabled property group is fetched concurrently, merged into the cache,
     * and the resulting snapshot is returned.
     *
     * @return a two-element list containing the {@link ExtendedStatistics} and {@link EndpointStatistics}
     * @throws Exception if authentication fails
     */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        updatePollingInterval();

        long controlTimestampSnapshot;
        stateLock.lock();
        try {
            if (shouldReturnCache() || refreshInProgress) {
                return snapshot();
            }
            ensureAuthenticated();
            refreshInProgress        = true;
            controlTimestampSnapshot = lastControlTimestamp;
            resetProviderSelectionIfExpired();
        } finally {
            stateLock.unlock();
        }

        try {
            ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
            List<AdvancedControllableProperty> freshControls = Collections.synchronizedList(new ArrayList<>());
            runAllGroups(fresh, freshControls);

            stateLock.lock();
            try {
                // Flush stale entries from variable-size groups before merging.
                // putAll() is additive and won't remove keys for items the device no longer
                // reports (e.g. uninstalled apps, ended sessions, disconnected peripherals).
//                if (isGroupEnabled("Microphone"))     cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.MICROPHONE.baseName));
//                if (isGroupEnabled("Camera"))         cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.CAMERA.baseName) || k.startsWith(PropertyGroup.CAMERAS.prefix));
//                if (isGroupEnabled("ActiveSessions")) cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.ACTIVE_SESSIONS.prefix));
//                if (isGroupEnabled("Conferences"))    cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.ACTIVE_CONFERENCE.prefix));
                // Only clear the previous Applications entries once fresh data for the group is
                // actually present this cycle — otherwise a failed fetch would wipe the group
                // from the cache instead of leaving the previous (stale but valid) values in place.
                if (isGroupEnabled("Applications") && fresh.keySet().stream().anyMatch(k -> k.startsWith(PropertyGroup.APPLICATIONS.prefix)))
                    cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.APPLICATIONS.prefix));
//                if (isGroupEnabled("Peripherals"))    cachedProperties.keySet().removeIf(k -> k.startsWith(PropertyGroup.PERIPHERALS.baseName));

                cachedProperties.putAll(fresh);
                // Only replace the controls list when no control arrived during this fetch.
                // If a control was sent mid-poll, the optimistic cache update takes precedence
                // and the next full poll cycle will reconcile with device state.
                if (lastControlTimestamp == controlTimestampSnapshot) {
                    // Merge rather than replace: controls from groups that failed to fetch
                    // (e.g. audio endpoints during a device reboot) are preserved until the
                    // next successful poll instead of being wiped from the cache.
                    for (AdvancedControllableProperty c : deduplicateControls(freshControls)) {
                        addOrReplace(cachedControls, c);
                    }
                }
                // The AudioVolume slider is only relevant in native/Device Mode, where the
                // codec's own volume is the only way to adjust it. Once an application
                // provider is active (App Mode), the provider manages its own volume, so the
                // control is hidden — merge/addOrReplace only ever adds or updates, so it must
                // be removed explicitly here rather than simply skipped during the fetch.
                String deviceMode = cachedProperties.get(ControlKey.DEVICE_MODE);
                boolean nativeDeviceMode = "true".equalsIgnoreCase(deviceMode);
                if (!nativeDeviceMode) {
                    cachedControls.removeIf(c -> ControlKey.VOLUME.equals(c.getName()));
                }
                cachedProperties.put(ControlKey.REBOOT, Values.N_A);
                addOrReplace(cachedControls, createButton(ControlKey.REBOOT, "Reboot", "Rebooting...", REBOOT_GRACE_MS));
                populateAdapterMetadata(cachedProperties);
                lastPollTimestamp = System.currentTimeMillis();
            } finally {
                stateLock.unlock();
            }
        } finally {
            refreshInProgress = false;
        }

        stateReporter.verifyAPIState(msg -> logger.warn(msg));
        return snapshot();
    }

    /**
     * Fetches every enabled property group concurrently on {@link #executor}, writing results
     * into the shared {@code props}/{@code controls} accumulators. A group that throws is
     * logged and skipped; all other groups still complete. Waits up to
     * {@link #GROUP_FETCH_TIMEOUT_S} seconds for all groups to finish before returning with
     * whatever completed.
     *
     * @param props accumulator for monitored properties from all enabled groups
     * @param controls accumulator for controllable properties from all enabled groups
     */
    private void runAllGroups(Map<String, String> props, List<AdvancedControllableProperty> controls) {
        // fetchSystem (GET /system + POST /config) and fetchSystemModes (GET /mode/device + GET /mode/signage)
        // are separated so the mode reads run in parallel with the heavier system/config calls.
        LinkedHashMap<String, GroupFetcher> groups = new LinkedHashMap<>();
        if (isGroupEnabled("SystemStatus")) {
            groups.put(PropertyGroup.SYSTEM_STATUS.name(), (p, c) -> fetchSystemStatus(p));
        }
        if (isGroupEnabled("System")) {
            groups.put(PropertyGroup.SYSTEM.name(), (p, c) -> fetchSystem(p));
            groups.put("SystemModes",               (p, c) -> fetchSystemModes(p, c));
        }
        if (isGroupEnabled("Audio")) {
            groups.put(PropertyGroup.AUDIO.name(), (p, c) -> fetchAudio(p, c));
        }
        if (isGroupEnabled("Microphone")) {
            groups.put(PropertyGroup.MICROPHONE.name(), (p, c) -> fetchMicrophones(p));
        }
        if (isGroupEnabled("Camera")) {
            groups.put(PropertyGroup.CAMERAS.name(), (p, c) -> fetchCameras(p));
        }
        if (isGroupEnabled("Calendar")) {
            groups.put(PropertyGroup.CALENDAR.name(), (p, c) -> fetchCalendar(p));
        }
        if (isGroupEnabled("Collaboration")) {
            groups.put(PropertyGroup.COLLABORATION.name(), (p, c) -> fetchCollaboration(p));
        }
        if (isGroupEnabled("ConferencingCapabilities")) {
            groups.put(PropertyGroup.CONFERENCING_CAPABILITIES.name(), (p, c) -> fetchConferencingCapabilities(p));
        }
        if (isGroupEnabled("ActiveSessions")) {
            groups.put(PropertyGroup.ACTIVE_SESSIONS.name(), (p, c) -> fetchActiveSessions(p));
        }
        if (isGroupEnabled("Conferences")) {
            groups.put("Conferences", (p, c) -> fetchConferences(p));
        }
        if (isGroupEnabled("Applications")) {
            groups.put(PropertyGroup.APPLICATIONS.name(), (p, c) -> fetchApplications(p, c));
        }
        if (isGroupEnabled("Peripherals")) {
            groups.put(PropertyGroup.PERIPHERALS.name(), (p, c) -> fetchPeripherals(p));
        }

        List<CompletableFuture<Void>> futures = groups.entrySet().stream()
            .map(e -> CompletableFuture.runAsync(() -> {
                try {
                    e.getValue().fetch(props, controls);
                    stateReporter.resolveError(e.getKey());
                } catch (Exception ex) {
                    stateReporter.pushError(e.getKey(), ex);
                    logger.warn("Group " + e.getKey() + " fetch failed: " + ex.getMessage());
                }
            }, executor))
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(GROUP_FETCH_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futures.forEach(f -> f.cancel(true));
            logger.warn("Statistics collection timed out after " + GROUP_FETCH_TIMEOUT_S + "s — using partial results.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.warn("Unexpected error during group collection: " + e.getMessage());
        }
    }

    /**
     * Builds the {@link ExtendedStatistics} and {@link EndpointStatistics} to return from the
     * current cache, without contacting the device.
     *
     * @return a two-element list containing the {@link ExtendedStatistics} and {@link EndpointStatistics}
     */
    private List<Statistics> snapshot() {
        stateLock.lock();
        try {
            ExtendedStatistics extended = new ExtendedStatistics();
            extended.setStatistics(new HashMap<>(cachedProperties));
            extended.setControllableProperties(new ArrayList<>(deduplicateControls(cachedControls)));
            EndpointStatistics endpoint = new EndpointStatistics();
            endpoint.setInCall(localEndpointStatistics.isInCall());
            endpoint.setCallStats(localEndpointStatistics.getCallStats());
            endpoint.setAudioChannelStats(localEndpointStatistics.getAudioChannelStats());
            endpoint.setVideoChannelStats(localEndpointStatistics.getVideoChannelStats());
            endpoint.setContentChannelStats(localEndpointStatistics.getContentChannelStats());
            endpoint.setRegistrationStatus(localEndpointStatistics.getRegistrationStatus());
            return Arrays.asList(extended, endpoint);
        } finally {
            stateLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Controller
    // -------------------------------------------------------------------------

    /**
     * Applies a single control. The device API call (Phase 1) runs without holding
     * {@link #stateLock}; only once it succeeds is the local cache updated (Phase 2), so a
     * failed call never leaves the cache reflecting a change that didn't actually happen.
     *
     * @param cp the property name/value to apply
     * @throws Exception if the underlying device API call fails
     */
    @Override
    public void controlProperty(ControllableProperty cp) throws Exception {
        String property = cp.getProperty();
        String value    = String.valueOf(cp.getValue());

        // Phase 1 — API call, no lock held.
        // If the call throws, the exception propagates and Phase 2 is never reached,
        // so the cache is never updated on failure.
        switch (property) {
            case ControlKey.MUTE_MICROPHONES:
                doPost(ApiUri.AUDIO_MUTED, !"0".equals(value));
                break;
            case ControlKey.MUTE_VIDEO: {
                ObjectNode req = JsonNodeFactory.instance.objectNode();
                req.put("mute", "1".equals(value));
                assertSuccess(doPost(ApiUri.VIDEO_MUTE, req, JsonNode.class), "Unable to change video mute state.");
                break;
            }
            case ControlKey.VOLUME:
                doPost(ApiUri.AUDIO_VOLUME, Math.round(Float.parseFloat(value)));
                break;
            case ControlKey.DEVICE_MODE:
                if ("1".equals(value)) {
                    assertSuccess(doPost(ApiUri.DEVICE_MODE, null, JsonNode.class), "Unable to switch to Device Mode.");
                } else {
                    doDelete(ApiUri.DEVICE_MODE);
                }
                break;
            case ControlKey.SIGNAGE_MODE:
                if ("1".equals(value)) {
                    assertSuccess(doPost(ApiUri.SIGNAGE_MODE, null, JsonNode.class), "Unable to switch to Signage Mode.");
                } else {
                    doDelete(ApiUri.SIGNAGE_MODE);
                }
                break;
            case ControlKey.REBOOT: {
                ObjectNode req = JsonNodeFactory.instance.objectNode();
                req.put("action", "reboot");
                doPost(ApiUri.REBOOT, req);
                break;
            }
            case ControlKey.APP_PROVIDER:
                // No API call — selection is persisted to cache in Phase 2
                break;
            case ControlKey.APP_SAVE: {
                String appToSave;
                stateLock.lock();
                try { appToSave = selectedApp; } finally { stateLock.unlock(); }
                if (appToSave != null) {
                    Map<String, List<String>> body = new HashMap<>();
                    body.put("enabledapps", Collections.singletonList(appToSave));
                    doPost(ApiUri.SYSTEM_MODE, body, JsonNode.class);
                }
                break;
            }
            default:
                logger.warn("Unrecognized control property: " + property);
                return;
        }

        // Phase 2 — cache update, only reached if Phase 1 succeeded.
        stateLock.lock();
        try {
            switch (property) {
                case ControlKey.MUTE_MICROPHONES:
                case ControlKey.MUTE_VIDEO:
                case ControlKey.VOLUME:
                case ControlKey.DEVICE_MODE:
                case ControlKey.SIGNAGE_MODE:
                    updateCachedControl(property, value);
                    break;
                case ControlKey.REBOOT:
                    invalidateSession();
                    break;
                case ControlKey.APP_PROVIDER:
                    selectedApp = value;
                    providerSelectionTimestamp = System.currentTimeMillis();
                    cachedProperties.put(ControlKey.APP_SAVE, Values.N_A);
                    addOrReplace(cachedControls, createButton(ControlKey.APP_SAVE, "Save", "Saving", 180_000L));
                    updateCachedControl(property, value);
                    break;
                case ControlKey.APP_SAVE:
                    selectedApp = null;
                    providerSelectionTimestamp = 0;
                    cachedProperties.remove(ControlKey.APP_SAVE);
                    cachedControls.removeIf(c -> ControlKey.APP_SAVE.equals(c.getName()));
                    break;
            }
            lastControlTimestamp = System.currentTimeMillis();
        } finally {
            stateLock.unlock();
        }

        if (ControlKey.REBOOT.equals(property)) {
            disconnect();
        }
    }

    /**
     * Applies each control in the list sequentially via {@link #controlProperty}.
     *
     * @param list the controls to apply; a null or empty list is a no-op
     * @throws Exception if any individual control application fails
     */
    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ControllableProperty cp : list) controlProperty(cp);
    }

    // -------------------------------------------------------------------------
    // CallController
    // -------------------------------------------------------------------------

    /**
     * Starts a call to the given destination and polls for the resulting conference to appear
     * (up to {@link #MAX_DIAL_POLL_ATTEMPTS} times, one second apart).
     *
     * @param device the dial destination, protocol, and requested call speed
     * @return the call ID of the newly connected conference
     * @throws Exception if the dial request fails, or the call does not connect within the polling window
     */
    @Override
    public String dial(DialDevice device) throws Exception {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("address", device.getDialString());
        int speed = device.getCallSpeed() != null ? device.getCallSpeed() : 0;
        body.put("rate", speed > 0 ? speed : defaultCallRate);
        Object protocol = device.getProtocol();
        if (protocol != null) {
            body.put("dialType", protocol.toString());
        }

        doPost(ApiUri.CONFERENCES, body, ArrayNode.class);

        String dialString = retrieveDeviceDialString();
        for (int i = 0; i < MAX_DIAL_POLL_ATTEMPTS; i++) {
            ArrayNode conferences = listConferenceCalls();
            if (!conferences.isEmpty()) {
                return generateCallId(conferences.get(0), null, dialString);
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Dial to " + device.getDialString() + " did not connect within timeout.");
    }

    /**
     * Ends a specific call, or every active conference if no call ID is given.
     *
     * @param callId the call ID to hang up, or null/empty to hang up all active conferences
     * @throws Exception if the underlying device API call fails
     */
    @Override
    public void hangup(String callId) throws Exception {
        if (callId != null && !callId.isEmpty()) {
            String confIdStr = callId.split(":")[0];
            doDelete(String.format(ApiUri.CONFERENCE, confIdStr));
        } else {
            for (JsonNode node : listConferenceCalls()) {
                doDelete(String.format(ApiUri.CONFERENCE, node.path("id").asInt()));
            }
        }
    }

    /**
     * Reports whether the given call (or, if none specified, the first active conference) is
     * currently connected.
     *
     * @param callId the call ID to check, or null/empty to check the first active conference
     * @return the current call status
     * @throws Exception if the underlying device API call fails
     */
    @Override
    public CallStatus retrieveCallStatus(String callId) throws Exception {
        String dialString = retrieveDeviceDialString();
        ArrayNode conferences = listConferenceCalls();

        if (conferences.isEmpty()) {
            return generateCallStatus(callId, CallStatus.CallStatusState.Disconnected);
        }

        if (callId != null && !callId.isEmpty()) {
            String[] parts = callId.split(":");
            int targetConfId = -1;
            try { targetConfId = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) {}
            if (targetConfId >= 0) {
                for (JsonNode conf : conferences) {
                    if (conf.path("id").asInt(-1) == targetConfId) {
                        int connId = parts.length > 1 ? parseIntOrZero(parts[1]) : 0;
                        return generateCallStatus(
                            generateCallId(conf, connId > 0 ? connId : null, dialString),
                            CallStatus.CallStatusState.Connected);
                    }
                }
                return generateCallStatus(callId, CallStatus.CallStatusState.Disconnected);
            }
        }

        return generateCallStatus(
            generateCallId(conferences.get(0), null, dialString),
            CallStatus.CallStatusState.Connected);
    }

    /**
     * Retrieves the device's current microphone mute state.
     *
     * @return the device's current microphone mute state
     * @throws Exception if the mute status cannot be retrieved from the device
     */
    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        JsonNode muted = doGet(ApiUri.AUDIO_MUTED, JsonNode.class);
        if (muted == null) {
            throw new RuntimeException("Unable to retrieve mute status.");
        }
        return muted.asBoolean(false) ? MuteStatus.Muted : MuteStatus.Unmuted;
    }

    /**
     * Mutes the microphones and immediately updates the local control cache to reflect the change.
     *
     * @throws Exception if the underlying device API call fails
     */
    @Override
    public void mute() throws Exception {
        doPost(ApiUri.AUDIO_MUTED, true);
        stateLock.lock();
        try {
            updateCachedControl(ControlKey.MUTE_MICROPHONES, "1");
            lastControlTimestamp = System.currentTimeMillis();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Unmutes the microphones and immediately updates the local control cache to reflect the change.
     *
     * @throws Exception if the underlying device API call fails
     */
    @Override
    public void unmute() throws Exception {
        doPost(ApiUri.AUDIO_MUTED, false);
        stateLock.lock();
        try {
            updateCachedControl(ControlKey.MUTE_MICROPHONES, "0");
            lastControlTimestamp = System.currentTimeMillis();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Not implemented — the device's REST API has no popup-message endpoint.
     *
     * @param message unused
     * @throws UnsupportedOperationException always
     */
    @Override
    public void sendMessage(PopupMessage message) throws Exception {
        throw new UnsupportedOperationException("sendMessage");
    }

    /**
     * Retrieves the device's currently active conferences.
     *
     * @return the device's currently active conferences, or an empty array if there are none
     * @throws Exception if the underlying device API call fails
     */
    private ArrayNode listConferenceCalls() throws Exception {
        ArrayNode response = doGet(ApiUri.CONFERENCES, ArrayNode.class);
        return response != null ? response : JsonNodeFactory.instance.arrayNode();
    }

    /**
     * Builds a call ID string encoding everything needed to look up a specific call/connection
     * again later (see {@link #retrieveCallStatus}).
     *
     * @param conferenceId the conference ID
     * @param connectionId the connection ID within the conference
     * @param startTime the connection start time (epoch millis)
     * @param dialString the dialed address, or null
     * @return the composite call ID, in {@code conferenceId:connectionId:startTime:dialString} form
     */
    private String buildCallId(int conferenceId, int connectionId, long startTime, String dialString) {
        return String.format(CALL_ID_TEMPLATE, conferenceId, connectionId, startTime,
            dialString != null ? dialString : "");
    }

    /**
     * Builds a call ID for a conference, using a specific connection if given, otherwise the
     * conference's first connection.
     *
     * @param conf the conference JSON node
     * @param targetConnectionId the connection ID to use, or null to use the first connection
     * @param dialString the dialed address, or null
     * @return the composite call ID for the selected connection
     */
    private String generateCallId(JsonNode conf, Integer targetConnectionId, String dialString) {
        int confId = conf.path("id").asInt(0);
        JsonNode connections = conf.get("connections");
        int connId = 0;
        long startTime = 0;
        if (connections != null && connections.isArray() && connections.size() > 0) {
            JsonNode conn = connections.get(0);
            if (targetConnectionId != null) {
                for (JsonNode c : connections) {
                    if (c.path("id").asInt(-1) == targetConnectionId) { conn = c; break; }
                }
            }
            connId    = conn.path("id").asInt(0);
            startTime = conn.path("startTime").asLong(0);
        }
        return buildCallId(confId, connId, startTime, dialString);
    }

    /**
     * Builds a {@link CallStatus} object from a call ID and state.
     *
     * @param callId the call ID to report
     * @param state the call state to report
     * @return a {@link CallStatus} combining the given ID and state
     */
    private CallStatus generateCallStatus(String callId, CallStatus.CallStatusState state) {
        CallStatus status = new CallStatus();
        status.setCallId(callId);
        status.setCallStatusState(state);
        return status;
    }

    /**
     * Resolves the address to report as this device's own dial string, preferring SIP, then
     * H.323, then falling back to the system name.
     *
     * @return the first non-empty identity in SIP/H.323/system-name order, or an empty string if none are cached
     */
    private String retrieveDeviceDialString() {
        String sip  = cachedProperties.get(PropertyGroup.SYSTEM.key("SIPUsername"));
        String h323 = cachedProperties.get(PropertyGroup.SYSTEM.key("H323Extension"));
        String name = cachedProperties.get(PropertyGroup.SYSTEM.key("SystemName"));
        if (sip != null && !sip.isEmpty()) {
            return sip;
        }
        if (h323 != null && !h323.isEmpty()) {
            return h323;
        }
        return name != null ? name : "";
    }

    /**
     * Clears the pending application-provider selection if it has been sitting unsaved longer
     * than {@link #appProviderSelectionTimeoutMin}. Must be called while holding {@link #stateLock}.
     */
    private void resetProviderSelectionIfExpired() {
        if (selectedApp == null || providerSelectionTimestamp == 0) {
            return;
        }
        long elapsedMs = System.currentTimeMillis() - providerSelectionTimestamp;
        if (elapsedMs >= (long) appProviderSelectionTimeoutMin * 60_000L) {
            selectedApp = null;
            providerSelectionTimestamp = 0;
            cachedProperties.remove(ControlKey.APP_SAVE);
            cachedControls.removeIf(c -> ControlKey.APP_SAVE.equals(c.getName()));
        }
    }

    /**
     * Parses a string as an integer, tolerating malformed input.
     *
     * @param s the string to parse
     * @return the parsed integer, or 0 if {@code s} is not a valid integer
     */
    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Throws if a device response has an explicit {@code success: false} field.
     *
     * @param response the device response to check
     * @param fallbackMessage the exception message to use if the response provides no {@code reason}
     * @throws Exception if the response indicates failure
     */
    private static void assertSuccess(JsonNode response, String fallbackMessage) throws Exception {
        if (response != null && response.has("success") && !response.path("success").asBoolean(true)) {
            String reason = response.path("reason").asText("");
            throw new RuntimeException(reason.isEmpty() ? fallbackMessage : fallbackMessage + " Reason: " + reason);
        }
    }

    // -------------------------------------------------------------------------
    // Group fetchers
    // -------------------------------------------------------------------------

    /**
     * Fetches dynamic health indicators from {@code GET /rest/system/status} and reports one
     * property per status item, keyed by the indicator's PascalCase name.
     *
     * @param props destination map for the fetched {@code SystemStatus#*} properties
     * @throws Exception if the underlying device API call fails
     */
    private void fetchSystemStatus(Map<String, String> props) throws Exception {
        ArrayNode response = doGet(ApiUri.SYSTEM_STATUS, ArrayNode.class);
        if (response == null) return;
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.SYSTEM_STATUS.prefix));
        response.forEach(item -> {
            String langtag   = item.path("langtag").asText(null);
            JsonNode stateList = item.path("stateList");
            if (langtag != null && stateList.isArray() && !stateList.isEmpty()) {
                props.put(
                    PropertyGroup.SYSTEM_STATUS.key(toPascalCase(langtag.replace("_", " "))),
                    stateList.get(0).asText().replace("_", " ").toUpperCase()
                );
            }
        });
    }

    /**
     * Fetches device identity, version, and network info from {@code GET /rest/system}, plus
     * SIP/H.323 identities via {@code POST /rest/config}.
     *
     * @param props destination map for the fetched {@code System#*} and {@code LANStatus#*} properties
     * @throws Exception if either underlying device API call fails
     */
    private void fetchSystem(Map<String, String> props) throws Exception {
        JsonNode sys = doGet(ApiUri.SYSTEM, JsonNode.class);
        if (sys != null) {
            putText(props, PropertyGroup.SYSTEM.key("SystemName"),           sys.get("systemName"));
            putText(props, PropertyGroup.SYSTEM.key("DeviceModel"),          sys.get("model"));
            putText(props, PropertyGroup.SYSTEM.key("SerialNumber"),         sys.get("serialNumber"));
            putText(props, PropertyGroup.SYSTEM.key("SoftwareVersion"),      sys.get("softwareVersion"));
            putText(props, PropertyGroup.SYSTEM.key("DeviceHardwareVersion"), sys.get("hardwareVersion"));
            putText(props, PropertyGroup.SYSTEM.key("SystemBuild"),          sys.get("build"));
            putText(props, PropertyGroup.SYSTEM.key("SystemState"),          sys.get("state"));
            putText(props, PropertyGroup.SYSTEM.key("SystemRebootNeeded"),   sys.get("rebootNeeded"));
            JsonNode uptime = sys.get("uptime");
            if (uptime != null && !uptime.isNull()) {
                props.put(PropertyGroup.SYSTEM.key("SystemUptime"), formatUptime(uptime.asText()));
            }
            JsonNode lan = sys.get("lanStatus");
            if (lan != null) {
                putText(props, PropertyGroup.LAN_STATUS.key("State"),      lan.get("state"));
                putText(props, PropertyGroup.LAN_STATUS.key("Speed(Mbps)"), lan.get("speedMbps"));
                putText(props, PropertyGroup.LAN_STATUS.key("Duplex"),     lan.get("duplex"));
            }
        }

        // SIP/H.323 identities require a POST to the config endpoint with key names
        Map<String, List<String>> configBody = new HashMap<>();
        configBody.put("names", Arrays.asList(ApiUri.CONFIG_KEY_SIP_USERNAME, ApiUri.CONFIG_KEY_H323_NAME, ApiUri.CONFIG_KEY_H323_EXTENSION));
        JsonNode config = doPost(ApiUri.CONFIG, configBody, JsonNode.class);

        if (config != null) {
            JsonNode vars = config.get("vars");
            if (vars != null) {
                HashMap<String, String> vals = new HashMap<>();
                vars.forEach(v -> {
                    if (v.has("name") && v.has("value")) {
                        vals.put(v.get("name").asText(), v.get("value").asText());
                    }
                });
                Optional.ofNullable(vals.get(ApiUri.CONFIG_KEY_SIP_USERNAME))
                    .ifPresent(v -> props.put(PropertyGroup.SYSTEM.key("SIPUsername"),   v));
                Optional.ofNullable(vals.get(ApiUri.CONFIG_KEY_H323_NAME))
                    .ifPresent(v -> props.put(PropertyGroup.SYSTEM.key("H323Name"),      v));
                Optional.ofNullable(vals.get(ApiUri.CONFIG_KEY_H323_EXTENSION))
                    .ifPresent(v -> props.put(PropertyGroup.SYSTEM.key("H323Extension"), v));
            }
        }
    }

    /**
     * Fetches device mode and signage mode state, populating both their monitored properties
     * and their paired switch controls. Split out from {@link #fetchSystem} so these two
     * lightweight reads run concurrently with the heavier system/config calls.
     *
     * @param props destination map for the {@code System#DeviceMode}/{@code System#SignageMode} properties
     * @param controls destination list for the paired switch controls
     * @throws Exception if either underlying device API call fails
     */
    private void fetchSystemModes(Map<String, String> props, List<AdvancedControllableProperty> controls)
            throws Exception {
        JsonNode dm = doGet(ApiUri.DEVICE_MODE, JsonNode.class);
        if (dm != null) {
            boolean on = dm.path("result").asBoolean(false);
            props.put(ControlKey.DEVICE_MODE, String.valueOf(on));
            addOrReplace(controls, createSwitch(ControlKey.DEVICE_MODE, on ? 1 : 0));
        }
        JsonNode sm = doGet(ApiUri.SIGNAGE_MODE, JsonNode.class);
        if (sm != null) {
            boolean on = sm.path("result").asBoolean(false);
            props.put(ControlKey.SIGNAGE_MODE, String.valueOf(on));
            addOrReplace(controls, createSwitch(ControlKey.SIGNAGE_MODE, on ? 1 : 0));
        }
    }

    /**
     * Fetches volume and mute state from {@code GET /rest/audio}, {@code GET /rest/audio/muted},
     * and {@code GET /rest/video/local/mute}, populating both monitored properties and their
     * paired controls.
     *
     * @param props destination map for the fetched {@code Audio#*} properties
     * @param controls destination list for the volume slider and mute switch controls
     * @throws Exception if any underlying device API call fails
     */
    private void fetchAudio(Map<String, String> props, List<AdvancedControllableProperty> controls) throws Exception {
        JsonNode audio = doGet(ApiUri.AUDIO, JsonNode.class);
        if (audio != null) {
            putText(props, PropertyGroup.AUDIO.key("MuteLocked"),           audio.get("muteLocked"));
            putText(props, PropertyGroup.AUDIO.key("MicrophonesConnected"), audio.get("numOfMicsConnected"));
            JsonNode vol = audio.get("volume");
            if (vol != null && !vol.isNull()) {
                float v = vol.floatValue();
                props.put(ControlKey.VOLUME, String.valueOf(Math.round(v)));
                addOrReplace(controls, createSlider(ControlKey.VOLUME, 0.0f, 100.0f, v));
            }
        }

        JsonNode micMuted = doGet(ApiUri.AUDIO_MUTED, JsonNode.class);
        if (micMuted != null) {
            int val = micMuted.asBoolean(false) ? 1 : 0;
            props.put(ControlKey.MUTE_MICROPHONES, String.valueOf(val));
            addOrReplace(controls, createSwitch(ControlKey.MUTE_MICROPHONES, val));
        }

        JsonNode videoMute = doGet(ApiUri.VIDEO_MUTE, JsonNode.class);
        if (videoMute != null) {
            int val = videoMute.path("result").asBoolean(false) ? 1 : 0;
            props.put(ControlKey.MUTE_VIDEO, String.valueOf(val));
            addOrReplace(controls, createSwitch(ControlKey.MUTE_VIDEO, val));
        }
    }

    /**
     * Fetches per-microphone state from {@code GET /rest/audio/microphones}, replacing any
     * previously reported microphones with the current list.
     *
     * @param props destination map for the fetched {@code Microphones#*} properties
     * @throws Exception if the underlying device API call fails
     */
    private void fetchMicrophones(Map<String, String> props) throws Exception {
        ArrayNode mics = doGet(ApiUri.AUDIO_MICROPHONES, ArrayNode.class);
        props.keySet().removeIf(k -> k.startsWith("Microphones#"));
        if (mics == null) {
            return;
        }
        int i = 1;
        for (JsonNode mic : mics) {
            String base = "Microphones#Microphone" + i;
            putText(props, base + "Name",            mic.get("typeInString"));
            putText(props, base + "State",           mic.get("state"));
            putText(props, base + "Type",            mic.get("type"));
            putText(props, base + "HardwareVersion", mic.get("hwVersion"));
            putText(props, base + "SoftwareVersion", mic.get("swVersion"));
            JsonNode muted = mic.get("mute");
            if (muted != null) props.put(base + "Muted", String.valueOf(muted.asBoolean()));
            i++;
        }
    }

    /**
     * Fetches near-end camera state from {@code GET /rest/cameras/near/all} and
     * content-sharing status from {@code GET /rest/cameras/contentstatus}, replacing any
     * previously reported cameras with the current list.
     *
     * @param props destination map for the fetched {@code Camera#*}/{@code Cameras#*} properties
     * @throws Exception if either underlying device API call fails
     */
    private void fetchCameras(Map<String, String> props) throws Exception {
        ArrayNode cameras = doGet(ApiUri.CAMERAS_NEAR_ALL, ArrayNode.class);
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.CAMERA.baseName) || k.startsWith(PropertyGroup.CAMERAS.prefix));
        if (cameras != null) {
            int i = 1;
            for (JsonNode cam : cameras) {
                String base = PropertyGroup.CAMERA.indexedPrefix("[" + i + "]");
                putText(props, base + "Name",       cam.get("name"));
                putText(props, base + "Model",      cam.get("model"));
                putText(props, base + "SourceType", cam.get("sourceType"));
                putText(props, base + "Connected",  cam.get("connected"));
                putText(props, base + "Selected",   cam.get("selected"));
                putText(props, base + "PTZCapable", cam.get("ptzcapable"));
                i++;
            }
        }
        // Device returns content status as a bare quoted JSON string; strip the quotes
        String contentStatus = doGet(ApiUri.CONTENT_STATUS, String.class);
        if (contentStatus != null) {
            props.put(PropertyGroup.CAMERAS.key("ContentStatus"), contentStatus.replace("\"", "").trim());
        }
    }

    /**
     * Fetches calendar service status and the next scheduled meeting. Handles both the
     * {@code status}/{@code Status} field-name variants seen across firmware versions.
     *
     * @param props destination map for the fetched {@code Calendar#*} properties
     * @throws Exception if either underlying device API call fails
     */
    private void fetchCalendar(Map<String, String> props) throws Exception {
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.CALENDAR.prefix));
        JsonNode status = doGet(ApiUri.CALENDAR, JsonNode.class);
        if (status != null) {
            // Field name differs across firmware versions
            String calStatus = status.hasNonNull("status") ? status.get("status").asText()
                             : status.hasNonNull("Status") ? status.get("Status").asText()
                             : null;
            if (calStatus != null) {
                props.put(PropertyGroup.CALENDAR.key("Status"), calStatus);
            }
        }
        JsonNode meetings = doGet(ApiUri.CALENDAR_MEETINGS, JsonNode.class);
        if (meetings != null && meetings.isArray() && !meetings.isEmpty()) {
            JsonNode next = meetings.get(0);
            putText(props, PropertyGroup.CALENDAR.key("NextMeetingSubject"),   next.get("subject"));
            putText(props, PropertyGroup.CALENDAR.key("NextMeetingOrganizer"), next.get("organizer"));
            putText(props, PropertyGroup.CALENDAR.key("NextMeetingLocation"),  next.get("location"));
            JsonNode canDial = next.get("canDial");
            if (canDial != null) {
                props.put(PropertyGroup.CALENDAR.key("NextMeetingCanDial"), String.valueOf(canDial.asBoolean()));
            }
            JsonNode startTime = next.get("startTime");
            if (startTime != null) {
                // startTime is Unix epoch in seconds
                props.put(PropertyGroup.CALENDAR.key("NextMeetingStartTime"), formatEpochSeconds(startTime.asLong()));
            }
        }
    }

    /**
     * Fetches active collaboration session state. Handles both the
     * {@code sessionState}/{@code SessionId} and {@code state}/{@code id} field-name variants
     * seen across firmware versions.
     *
     * @param props destination map for the fetched {@code Collaboration#*} properties
     * @throws Exception if the underlying device API call fails
     */
    private void fetchCollaboration(Map<String, String> props) throws Exception {
        JsonNode collab = doGet(ApiUri.COLLABORATION, JsonNode.class);
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.COLLABORATION.prefix));
        if (collab == null) {
            return;
        }
        // Firmware inconsistency: some versions use "sessionState"/"SessionId", others use "state"/"id"
        String state = collab.hasNonNull("sessionState") ? collab.get("sessionState").asText()
                     : collab.hasNonNull("state")        ? collab.get("state").asText()
                     : null;
        if (state != null) {
            props.put(PropertyGroup.COLLABORATION.key("SessionState"), state);
            if ("ACTIVE".equalsIgnoreCase(state)) {
                String id = collab.hasNonNull("SessionId") ? collab.get("SessionId").asText()
                          : collab.hasNonNull("id")        ? collab.get("id").asText()
                          : null;
                if (id != null) {
                    props.put(PropertyGroup.COLLABORATION.key("SessionID"), id);
                }
            }
        }
    }

    /**
     * Fetches blast-dial, audio-call, and video-call availability flags.
     *
     * @param props destination map for the fetched {@code ConferencingCapabilities#*} properties
     * @throws Exception if the underlying device API call fails
     */
    private void fetchConferencingCapabilities(Map<String, String> props) throws Exception {
        JsonNode caps = doGet(ApiUri.CONFERENCING_CAPS, JsonNode.class);
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.CONFERENCING_CAPABILITIES.prefix));
        if (caps == null) {
            return;
        }
        props.put(PropertyGroup.CONFERENCING_CAPABILITIES.key("BlastDial"), caps.path("canBlastDial").asBoolean()    ? Values.AVAILABLE : Values.UNAVAILABLE);
        props.put(PropertyGroup.CONFERENCING_CAPABILITIES.key("AudioCall"), caps.path("canMakeAudioCall").asBoolean() ? Values.AVAILABLE : Values.UNAVAILABLE);
        props.put(PropertyGroup.CONFERENCING_CAPABILITIES.key("VideoCall"), caps.path("canMakeVideoCall").asBoolean() ? Values.AVAILABLE : Values.UNAVAILABLE);
    }

    /**
     * Fetches currently active management sessions, replacing any previously reported sessions
     * with the current list.
     *
     * @param props destination map for the fetched {@code ActiveSessions#*} properties
     * @throws Exception if the underlying device API call fails
     */
    private void fetchActiveSessions(Map<String, String> props) throws Exception {
        JsonNode response = doGet(ApiUri.SESSIONS_LIST, JsonNode.class);
        props.keySet().removeIf(k -> k.startsWith("ActiveSessions#"));
        if (response == null) {
            return;
        }
        JsonNode list = response.get("sessionList");
        if (list == null || !list.isArray()) {
            return;
        }
        int i = 1;
        for (JsonNode session : list) {
            String base = "ActiveSessions#Session" + i;
            putText(props, base + "UserID",     session.get("userId"));
            putText(props, base + "Role",       session.get("role"));
            putText(props, base + "Location",   session.get("location"));
            putText(props, base + "ClientType", session.get("clientType"));
            boolean connected = session.path("isConnected").asBoolean(false);
            boolean authed    = session.path("isAuthenticated").asBoolean(false);
            props.put(base + "Status",
                (connected ? "CONNECTED" : "NOT CONNECTED") + ", " +
                (authed    ? "AUTHENTICATED" : "NOT AUTHENTICATED"));
            i++;
        }
    }

    /**
     * Fetches the active conference (if any) and its per-call media statistics, and updates
     * {@link #localEndpointStatistics} accordingly — this is the only method that populates
     * {@code EndpointStatistics}, so it is never updated while this group is disabled.
     *
     * @param props destination map for the fetched {@code Conferences#*} properties; left
     *              untouched (aside from the prefix flush) when there is no active conference
     * @throws Exception if any underlying device API call fails
     */
    private void fetchConferences(Map<String, String> props) throws Exception {
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.ACTIVE_CONFERENCE.prefix));

        ArrayNode conferences = doGet(ApiUri.CONFERENCES, ArrayNode.class);
        if (conferences == null || conferences.isEmpty()) {
            localEndpointStatistics.setInCall(false);
            localEndpointStatistics.setCallStats(null);
            localEndpointStatistics.setAudioChannelStats(null);
            localEndpointStatistics.setVideoChannelStats(null);
            localEndpointStatistics.setRegistrationStatus(fetchRegistrationStatus());
            return;
        }

        JsonNode conference = conferences.get(0);
        int conferenceId = conference.path("id").asInt(-1);
        boolean inCall = conferenceId > -1;
        localEndpointStatistics.setInCall(inCall);
        localEndpointStatistics.setRegistrationStatus(fetchRegistrationStatus());

        if (!inCall) {
            return;
        }

        props.put(PropertyGroup.ACTIVE_CONFERENCE.key("ConferenceID"), String.valueOf(conferenceId));
        JsonNode startTime = conference.get("startTime");
        if (startTime != null && !startTime.isNull()) {
            props.put(PropertyGroup.ACTIVE_CONFERENCE.key("ConferenceStartTime"), formatEpochMillis(startTime.asLong()));
        }
        ArrayNode terminals = (ArrayNode) conference.get("terminals");
        if (terminals != null) {
            for (int i = 0; i < terminals.size(); i++) {
                JsonNode t = terminals.get(i);
                String n = String.valueOf(i + 1);
                putText(props, PropertyGroup.ACTIVE_CONFERENCE.key("Terminal" + n + "Address"), t.get("address"));
                putText(props, PropertyGroup.ACTIVE_CONFERENCE.key("Terminal" + n + "System"),  t.get("systemID"));
            }
        }
        ArrayNode connections = (ArrayNode) conference.get("connections");
        if (connections != null) {
            for (int i = 0; i < connections.size(); i++) {
                JsonNode conn = connections.get(i);
                String n = String.valueOf(i + 1);
                putText(props, PropertyGroup.ACTIVE_CONFERENCE.key("Connection" + n + "Type"), conn.get("callType"));
                putText(props, PropertyGroup.ACTIVE_CONFERENCE.key("Connection" + n + "Info"), conn.get("callInfo"));
            }
        }

        CallStats callStats              = new CallStats();
        AudioChannelStats audioStats     = new AudioChannelStats();
        VideoChannelStats videoStats     = new VideoChannelStats();
        ContentChannelStats contentStats = new ContentChannelStats();

        ArrayNode mediaStats = doGet(String.format(ApiUri.CONFERENCE_MEDIASTATS, conferenceId), ArrayNode.class);
        if (mediaStats != null) {
            processMediaStats(mediaStats, audioStats, videoStats, callStats);
        }

        JsonNode sharedResponse = doGet(ApiUri.SHARED_MEDIASTATS, JsonNode.class);
        if (sharedResponse != null) {
            ArrayNode vars = (ArrayNode) sharedResponse.get("vars");
            if (vars != null && vars.size() > 0) {
                JsonNode shared = vars.get(0);
                contentStats.setFrameSizeTxWidth(getJsonProperty(shared, "width", Integer.class));
                contentStats.setFrameSizeTxHeight(getJsonProperty(shared, "height", Integer.class));
                contentStats.setFrameRateTx(getJsonProperty(shared, "framerate", Float.class));
                contentStats.setBitRateTx(getJsonProperty(shared, "bitrate", Integer.class));
            }
        }

        callStats.setRequestedCallRate(defaultCallRate);
        String dialString = retrieveDeviceDialString();
        callStats.setRemoteAddress(dialString);
        if (connections != null && connections.size() > 0) {
            String callType = connections.get(0).path("callType").asText(null);
            if (callType != null) {
                callStats.setProtocol(callType);
            }
            int  connId    = connections.get(0).path("id").asInt(0);
            long connStart = connections.get(0).path("startTime").asLong(0);
            callStats.setCallId(buildCallId(conferenceId, connId, connStart, dialString));
        }

        localEndpointStatistics.setCallStats(callStats);
        localEndpointStatistics.setAudioChannelStats(audioStats);
        localEndpointStatistics.setVideoChannelStats(videoStats);
        localEndpointStatistics.setContentChannelStats(contentStats);
    }

    /**
     * Splits a flat list of RX/AUDIO, RX/VIDEO, TX/AUDIO, TX/VIDEO media-stat entries into the
     * given per-channel stats objects, then derives the combined (audio+video) call-level totals.
     *
     * @param mediaStats the raw media-stats entries from the device
     * @param audio destination for audio channel statistics
     * @param video destination for video channel statistics
     * @param call destination for the combined call-level statistics
     */
    private void processMediaStats(ArrayNode mediaStats, AudioChannelStats audio, VideoChannelStats video, CallStats call) {
        mediaStats.forEach(node -> {
            String direction = getJsonProperty(node, "mediaDirection", String.class);
            String type      = getJsonProperty(node, "mediaType", String.class);
            if (direction == null || type == null) {
                return;
            }
            switch (direction) {
                case "RX":
                    switch (type) {
                        case "AUDIO":
                            audio.setBitRateRx(getJsonProperty(node, "actualBitRate", Integer.class));
                            audio.setJitterRx(getJsonProperty(node, "jitter", Float.class));
                            audio.setPacketLossRx(getJsonProperty(node, "packetLoss", Integer.class));
                            audio.setPercentPacketLossRx(getJsonProperty(node, "percentPacketLoss", Float.class));
                            audio.setCodec(getJsonProperty(node, "mediaAlgorithm", String.class));
                            break;
                        case "VIDEO":
                            video.setBitRateRx(getJsonProperty(node, "actualBitRate", Integer.class));
                            video.setJitterRx(getJsonProperty(node, "jitter", Float.class));
                            video.setPacketLossRx(getJsonProperty(node, "packetLoss", Integer.class));
                            video.setPercentPacketLossRx(getJsonProperty(node, "percentPacketLoss", Float.class));
                            video.setFrameRateRx(getJsonProperty(node, "actualFrameRate", Float.class));
                            video.setCodec(getJsonProperty(node, "mediaAlgorithm", String.class));
                            video.setFrameSizeRx(getJsonProperty(node, "mediaFormat", String.class));
                            break;
                        default: break;
                    }
                    break;
                case "TX":
                    switch (type) {
                        case "AUDIO":
                            audio.setBitRateTx(getJsonProperty(node, "actualBitRate", Integer.class));
                            audio.setJitterTx(getJsonProperty(node, "jitter", Float.class));
                            audio.setPacketLossTx(getJsonProperty(node, "packetLoss", Integer.class));
                            audio.setPercentPacketLossTx(getJsonProperty(node, "percentPacketLoss", Float.class));
                            audio.setCodec(getJsonProperty(node, "mediaAlgorithm", String.class));
                            break;
                        case "VIDEO":
                            video.setBitRateTx(getJsonProperty(node, "actualBitRate", Integer.class));
                            video.setJitterTx(getJsonProperty(node, "jitter", Float.class));
                            video.setPacketLossTx(getJsonProperty(node, "packetLoss", Integer.class));
                            video.setPercentPacketLossTx(getJsonProperty(node, "percentPacketLoss", Float.class));
                            video.setFrameRateTx(getJsonProperty(node, "actualFrameRate", Float.class));
                            video.setCodec(getJsonProperty(node, "mediaAlgorithm", String.class));
                            video.setFrameSizeTx(getJsonProperty(node, "mediaFormat", String.class));
                            break;
                        default: break;
                    }
                    break;
                default: break;
            }
        });
        call.setTotalPacketLossRx(sumIntegers(audio.getPacketLossRx(), video.getPacketLossRx()));
        call.setTotalPacketLossTx(sumIntegers(audio.getPacketLossTx(), video.getPacketLossTx()));
        call.setPercentPacketLossRx(sumFloats(audio.getPercentPacketLossRx(), video.getPercentPacketLossRx()));
        call.setPercentPacketLossTx(sumFloats(audio.getPercentPacketLossTx(), video.getPercentPacketLossTx()));
        call.setCallRateRx(sumIntegers(audio.getBitRateRx(), video.getBitRateRx()));
        call.setCallRateTx(sumIntegers(audio.getBitRateTx(), video.getBitRateTx()));
    }

    /**
     * Resolves SIP/H.323 registration status. In device mode, registration is always reported
     * as true (there is no separate provider/registrar to check); otherwise, the SIP and H.323
     * gatekeeper endpoints are queried directly, and either being unavailable is tolerated
     * (logged and left unset rather than failing the whole fetch).
     *
     * @return the resolved registration status
     */
    private RegistrationStatus fetchRegistrationStatus() {
        RegistrationStatus status = new RegistrationStatus();
        if ("1".equals(cachedProperties.get(ControlKey.DEVICE_MODE))) {
            status.setSipRegistered(true);
            status.setH323Registered(true);
            return status;
        }
        try {
            JsonNode sipServers = doGet(ApiUri.SIP_SERVERS, JsonNode.class);
            if (sipServers != null && sipServers.isArray() && sipServers.size() > 0) {
                JsonNode s = sipServers.get(0);
                status.setSipRegistered("up".equalsIgnoreCase(s.path("state").asText()));
                if (s.has("address")) {
                    status.setSipRegistrar(s.get("address").asText());
                }
            }
        } catch (Exception e) {
            logger.warn("SIP server status unavailable: " + e.getMessage());
        }
        try {
            JsonNode h323Servers = doGet(ApiUri.H323_SERVERS, JsonNode.class);
            if (h323Servers != null && h323Servers.isArray() && h323Servers.size() > 0) {
                JsonNode s = h323Servers.get(0);
                status.setH323Registered("up".equalsIgnoreCase(s.path("state").asText()));
                if (s.has("address")) {
                    status.setH323Gatekeeper(s.get("address").asText());
                }
            }
        } catch (Exception e) {
            logger.warn("H.323 gatekeeper status unavailable: " + e.getMessage());
        }
        return status;
    }

    /**
     * Reads a single field from a JSON node as the requested type, returning null if the field
     * is missing, null, or an unsupported type.
     *
     * @param node the JSON node to read from
     * @param field the field name to read
     * @param type the expected type ({@link String}, {@link Integer}, or {@link Float})
     * @return the field's value as {@code T}, or null if absent or unsupported
     */
    @SuppressWarnings("unchecked")
    private <T> T getJsonProperty(JsonNode node, String field, Class<T> type) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        if (type == String.class) {
            return (T) child.asText();
        }
        if (type == Integer.class) {
            return (T) Integer.valueOf(child.asInt());
        }
        if (type == Float.class) {
            return (T) Float.valueOf((float) child.asDouble());
        }
        return null;
    }

    /**
     * Sums two nullable integers, treating a missing (null) operand as unmeasured rather than zero.
     *
     * @param a first value, may be null
     * @param b second value, may be null
     * @return the sum, treating a null operand as 0; null if both are null
     */
    private Integer sumIntegers(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    /**
     * Sums two nullable floats, treating a missing (null) operand as unmeasured rather than zero.
     *
     * @param a first value, may be null
     * @param b second value, may be null
     * @return the sum, treating a null operand as 0; null if both are null
     */
    private Float sumFloats(Float a, Float b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0f : a) + (b == null ? 0f : b);
    }

    /**
     * Fetches installed application versions/update timestamps and the list of available
     * providers, then resolves and reports the active provider — the pending user selection
     * ({@link #selectedApp}) if one is set, otherwise the most recently updated app.
     *
     * @param props destination map for the fetched {@code Applications#*} properties
     * @param controls destination list for the provider dropdown and, if a selection is pending, the save button
     * @throws Exception if the underlying device API call fails
     */
    private void fetchApplications(Map<String, String> props, List<AdvancedControllableProperty> controls)
            throws Exception {
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.APPLICATIONS.prefix));
        JsonNode apps       = doGet(ApiUri.APPS, JsonNode.class);
        JsonNode systemApps = doGet(ApiUri.SYSTEM_APPS, JsonNode.class);
        if (apps == null) {
            return;
        }

        // Snapshot once to avoid a race with controlProperty (which writes selectedApp under stateLock)
        String currentSelectedApp = selectedApp;

        List<String> dropdownOptions = new ArrayList<>();
        String latestApp    = null;
        long   latestTs     = 0;

        for (JsonNode app : apps) {
            String rawName = app.path("appName").asText(null);
            if (rawName == null) {
                continue;
            }
            String safeName = rawName.replaceAll("\\s+", "");
            putText(props, PropertyGroup.APPLICATIONS.key(safeName + "Version"), app.get("versionInfo"));
            JsonNode ts = app.get("lastUpdatedOn");
            if (ts != null && !ts.isNull()) {
                props.put(PropertyGroup.APPLICATIONS.key(safeName + "LastUpdated"), formatEpochMillis(ts.asLong()));
                if (ts.asLong() > latestTs) {
                    latestTs  = ts.asLong();
                    latestApp = rawName;
                }
            }
        }
        if (systemApps != null) {
            for (JsonNode app : systemApps) {
                String name = app.path("appName").asText(null);
                if (name != null) {
                    dropdownOptions.add(name);
                }
            }
        }

        String provider = currentSelectedApp != null ? currentSelectedApp : latestApp;
        props.put(ControlKey.APP_PROVIDER, provider != null ? provider : Values.N_A);
        addOrReplace(controls, createDropdown(ControlKey.APP_PROVIDER, dropdownOptions, provider));

        if (currentSelectedApp != null) {
            props.put(ControlKey.APP_SAVE, Values.N_A);
            addOrReplace(controls, createButton(ControlKey.APP_SAVE, "Save", "Saving", 120_000L));
        }
    }

    /**
     * Fetches connected peripheral devices (excluding the codec itself) via the undocumented
     * device-management endpoint, replacing any previously reported peripherals with the
     * current list. If the endpoint is unavailable, the fetch is skipped with a warning rather
     * than failing the whole polling cycle.
     *
     * @param props destination map for the fetched {@code Peripherals#*} properties
     */
    private void fetchPeripherals(Map<String, String> props) {
        ArrayNode devices;
        try {
            // Undocumented: this endpoint requires POST with a null body, not GET
            devices = doPost(ApiUri.PERIPHERAL_DEVICES, null, ArrayNode.class);
        } catch (Exception e) {
            logger.warn("Peripheral devices endpoint unavailable: " + e.getMessage());
            return;
        }
        props.keySet().removeIf(k -> k.startsWith(PropertyGroup.PERIPHERALS.baseName));
        if (devices == null) return;

        String ownName = props.get(PropertyGroup.SYSTEM.key("SystemName"));
        devices.forEach(device -> {
            String uid        = device.path("uid").asText(null);
            String deviceName = device.path("systemName").asText(null);
            if (uid == null || uid.isEmpty() || (ownName != null && ownName.equals(deviceName))) return;

            String category = device.path("deviceCategory").asText("UNKNOWN").toUpperCase();
            String type     = device.path("deviceType").asText("UNKNOWN").toUpperCase();
            String conn     = device.path("connectionType").asText("UNKNOWN").toUpperCase();
            String prefix   = PropertyGroup.PERIPHERALS.indexedPrefix(String.format("[%s:%s:%s]", category, type, conn));

            putPeripheral(props, prefix + "ConnectionType",   conn);
            putPeripheral(props, prefix + "DeviceCategory",  category);
            putPeripheral(props, prefix + "DeviceState",     device.path("deviceState").asText(null));
            putPeripheral(props, prefix + "DeviceType",      type);
            putPeripheral(props, prefix + "IPAddress",       device.path("ip").asText(null));
            putPeripheral(props, prefix + "MACAddress",      device.path("macAddress").asText(null));
            putPeripheral(props, prefix + "NetworkInterface", device.path("networkInterface").asText(null));
            putPeripheral(props, prefix + "ProductName",     device.path("productName").asText(null));
            putPeripheral(props, prefix + "SerialNumber",    device.path("serialNumber").asText(null));
            putPeripheral(props, prefix + "SoftwareVersion", device.path("softwareVersion").asText(null));
            putPeripheral(props, prefix + "SystemName",      device.path("systemName").asText(null));
            putPeripheral(props, prefix + "UID",             uid);
        });
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Reports adapter self-monitoring properties: version, build date, uptime, last monitoring
     * cycle timestamp, and the currently active property groups.
     *
     * @param props destination map for the {@code AdapterMetadata#*} properties
     */
    private void populateAdapterMetadata(Map<String, String> props) {
        props.put(PropertyGroup.ADAPTER_METADATA.key("AdapterVersion"),
            adapterProperties.getProperty("adapter.version", Values.N_A));
        props.put(PropertyGroup.ADAPTER_METADATA.key("AdapterBuildDate"),
            adapterProperties.getProperty("adapter.build.date", Values.N_A));
        long uptimeSec = (System.currentTimeMillis() - initTimestamp) / 1000;
        props.put(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime"),       formatUptimeSeconds(uptimeSec));
        props.put(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime(min)"),  String.valueOf(uptimeSec / 60));
        props.put(PropertyGroup.ADAPTER_METADATA.key("LastMonitoringCycleTimestamp"), LocalDateTime.now().format(DATE_FMT));
        props.put(PropertyGroup.ADAPTER_METADATA.key("ActivePropertyGroups"), String.join(", ", displayPropertyGroups));
    }

    /**
     * Checks whether a property group is currently enabled for collection.
     *
     * @param groupName the property group name to check
     * @return true if {@code groupName} (or {@code "All"}) is in {@link #displayPropertyGroups}
     */
    private boolean isGroupEnabled(String groupName) {
        return displayPropertyGroups.contains("All") || displayPropertyGroups.contains(groupName);
    }

    /**
     * Determines whether the cached snapshot is still fresh enough to return without polling the device.
     *
     * @return true if the last poll is still within the polling interval, or a control was
     *         applied within the last {@link #CONTROL_COOLDOWN_MS} — in either case the cache
     *         is returned as-is instead of triggering a new device fetch
     */
    private boolean shouldReturnCache() {
        long now = System.currentTimeMillis();
        return (now - lastPollTimestamp) < pollingIntervalMs
            || (now - lastControlTimestamp) < CONTROL_COOLDOWN_MS;
    }

    /**
     * Refreshes {@link #pollingIntervalMs} from the platform-configured monitoring rate.
     * Silently keeps the previous interval if the platform framework in use predates
     * {@code getMonitoringRate()}.
     */
    private void updatePollingInterval() {
        try {
            pollingIntervalMs = getMonitoringRate() * 60_000;
        } catch (NoSuchMethodError ignored) {}
    }

    /**
     * Updates a cached property's value and, if a matching control exists, its value too —
     * used to optimistically reflect a control change without waiting for the next poll.
     *
     * @param name the property/control name to update
     * @param value the new value
     */
    private void updateCachedControl(String name, String value) {
        cachedProperties.put(name, value);
        cachedControls.stream()
            .filter(c -> c.getName().equals(name))
            .findFirst()
            .ifPresent(c -> c.setValue(value));
    }

    /**
     * Adds a control to the list, replacing any existing control with the same name.
     *
     * @param controls the list to update
     * @param control the control to add; a no-op if null
     */
    private void addOrReplace(List<AdvancedControllableProperty> controls, AdvancedControllableProperty control) {
        if (control == null) {
            return;
        }
        synchronized (controls) {
            controls.removeIf(c -> c.getName().equals(control.getName()));
            controls.add(control);
        }
    }

    /**
     * Collapses a list of controls down to one per name, keeping whichever has the newer
     * timestamp (or the existing one if the incoming entry has no timestamp).
     *
     * @param controls the controls to deduplicate
     * @return one control per distinct name, in first-seen order
     */
    private List<AdvancedControllableProperty> deduplicateControls(List<AdvancedControllableProperty> controls) {
        LinkedHashMap<String, AdvancedControllableProperty> latest = new LinkedHashMap<>();
        for (AdvancedControllableProperty c : controls) {
            latest.merge(c.getName(), c, (existing, incoming) -> {
                if (incoming.getTimestamp() == null) {
                    return existing;
                }
                if (existing.getTimestamp() == null) {
                    return incoming;
                }
                return incoming.getTimestamp().after(existing.getTimestamp()) ? incoming : existing;
            });
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * Puts a JSON node's text value into the map, skipping null, empty, and literal-"null" values.
     *
     * @param props destination map
     * @param key the property key
     * @param node the JSON node to read the value from
     */
    private void putText(Map<String, String> props, String key, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        String val = node.asText();
        if (!val.isEmpty() && !"null".equals(val)) {
            props.put(key, val);
        }
    }

    /**
     * Inserts a peripheral property. When the same key already exists (two devices share
     * category/type/connection), ordinal suffixes (:1, :2, …) are appended to the group
     * segment so all values remain visible.
     *
     * @param props destination map
     * @param key the property key, in {@code Peripherals#[CATEGORY:TYPE:CONNECTION]Field} form
     * @param value the value to insert; a no-op if null or empty
     */
    private void putPeripheral(Map<String, String> props, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!props.containsKey(key)) {
            props.put(key, value);
            return;
        }
        String[] parts     = key.split("#", 2);
        String   groupPart = parts[0];
        String   namePart  = parts.length > 1 ? parts[1] : "";
        String   baseGroup = groupPart.replaceAll(":\\d+$", "");

        if (!groupPart.matches(".*:\\d+$")) {
            String existing = props.remove(key);
            props.put(baseGroup + ":1#" + namePart, existing);
        }
        int ordinal = 2;
        while (props.containsKey(baseGroup + ":" + ordinal + "#" + namePart)) ordinal++;
        props.put(baseGroup + ":" + ordinal + "#" + namePart, value);
    }

    /**
     * Converts a lowercase, space/underscore-separated string to PascalCase, uppercasing the
     * {@code sip}, {@code h323}, and {@code p2p} acronyms instead of just capitalizing them.
     *
     * @param input the string to convert
     * @return the PascalCase result, or the input unchanged if null or empty
     */
    private String toPascalCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : input.toLowerCase().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.equals("sip") || word.equals("h323") || word.equals("p2p")) {
                sb.append(word.toUpperCase());
            } else {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Formats a device-reported uptime string (given as a floating-point number of seconds)
     * into a human-readable duration.
     *
     * @param uptimeStr the raw uptime value as reported by the device
     * @return the formatted duration, or {@code uptimeStr} unchanged if it isn't a valid number
     */
    private String formatUptime(String uptimeStr) {
        try {
            return formatUptimeSeconds(Math.round(Float.parseFloat(uptimeStr)));
        } catch (NumberFormatException e) {
            return uptimeStr;
        }
    }

    /**
     * Formats a duration in seconds as {@code "<d> d <h> hr <m> min <s> sec"}, omitting any
     * unit that is zero.
     *
     * @param s the duration in seconds
     * @return the formatted duration
     */
    private String formatUptimeSeconds(long s) {
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append(" d ");
        }
        if (h > 0) {
            sb.append(h).append(" hr ");
        }
        if (m > 0) {
            sb.append(m).append(" min ");
        }
        if (sec > 0) {
            sb.append(sec).append(" sec");
        }
        return sb.toString().trim();
    }

    /**
     * Formats a Unix timestamp given in seconds as a human-readable date/time.
     *
     * @param epochSeconds a Unix timestamp in seconds
     * @return the timestamp formatted as {@code yyyy-MM-dd HH:mm} in the local system time zone
     */
    private String formatEpochSeconds(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DATE_FMT);
    }

    /**
     * Formats a Unix timestamp given in milliseconds as a human-readable date/time.
     *
     * @param epochMillis a Unix timestamp in milliseconds
     * @return the timestamp formatted as {@code yyyy-MM-dd HH:mm} in the local system time zone
     */
    private String formatEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_FMT);
    }

    // -------------------------------------------------------------------------
    // Configurable properties
    // -------------------------------------------------------------------------

    /**
     * Returns the interval between device polling cycles.
     *
     * @return the current polling interval in milliseconds
     */
    public int  getApiPollingInterval()       { return pollingIntervalMs; }
    /**
     * Sets the interval between device polling cycles.
     *
     * @param ms the polling interval in milliseconds
     */
    public void setApiPollingInterval(int ms) { this.pollingIntervalMs = ms; }

    /**
     * Returns the default call bandwidth used when dialing without an explicit rate.
     *
     * @return the default call bandwidth in kbps used when dialing without an explicit rate
     */
    public int  getDefaultCallRate()          { return defaultCallRate; }
    /**
     * Sets the default call bandwidth used when dialing without an explicit rate.
     *
     * @param kbps the default call bandwidth in kbps
     */
    public void setDefaultCallRate(int kbps)  { this.defaultCallRate = kbps; }

    /**
     * Returns how long an unsaved application-provider selection is kept before it reverts
     * (see {@link #resetProviderSelectionIfExpired}).
     *
     * @return minutes an unsaved application-provider selection is kept before reverting
     */
    public int  getAppProviderSelectionTimeoutMin()        { return appProviderSelectionTimeoutMin; }
    /**
     * Sets how long an unsaved application-provider selection is kept before it reverts
     * (see {@link #resetProviderSelectionIfExpired}).
     *
     * @param min minutes an unsaved application-provider selection is kept before reverting
     */
    public void setAppProviderSelectionTimeoutMin(int min) { this.appProviderSelectionTimeoutMin = min; }
}
