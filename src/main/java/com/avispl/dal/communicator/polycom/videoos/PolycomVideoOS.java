/*
 * Copyright (c) 2015-2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.control.call.CallController;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.control.Protocol;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.control.call.PopupMessage;
import com.avispl.symphony.api.dal.dto.monitor.*;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.error.ResourceNotReachableException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PolycomVideoOS is a communicator class for X30/X50/G7500 devices.
 * The communicator is based on RestCommunicator class and is using VideoOS REST API to communicate with devices.
 * In order to have codec specific features available, CallController interface is used with dial(), hangup()
 * and callStatus() methods implemented.
 * sendMessage() is not implemented since there's no such functionality available in VideoOS REST API for now.
 *
 * Polycom VideoOS REST API Reference Guide:
 * https://support.polycom.com/content/dam/polycom-support/products/telepresence-and-video/poly-studio-x/user/en/poly-video-restapi.pdf
 */
public class PolycomVideoOS extends RestCommunicator implements CallController, Monitorable, Controller {

    private ClientHttpRequestInterceptor videoOSInterceptor = new PolycomVideoOSInterceptor();

    /**
     * Interceptor for RestTemplate that injects
     *
     * Currently, a reboot action changes previously accepted certificates, which would lead to
     * {@link ResourceNotReachableException} without a proper way to recover. Current workaround is to call
     * {@link #disconnect()} on reboot and on {@link ResourceNotReachableException} in the
     * {@link PolycomVideoOSInterceptor} in case if the device has been rebooted externally.
     */
    class PolycomVideoOSInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

            ClientHttpResponse response = execution.execute(request, body);
            if (response.getRawStatusCode() == 403) {
                try {
                    authenticate();
                    response = execution.execute(request, body);
                } catch (ResourceNotReachableException e) {
                    if (e.getMessage().contains(SESSION)) {
                        // In case it's been rebooted by some other resource - we need to react to that.
                        // Normally we expect that the authorization request timeouts, since previous one failed with
                        // a specific error code, so basically we do the same as before - authenticate + retry previous
                        // request but with the destroy action called first, since init will be done next when
                        // authentication is requested.
                        try {
                            disconnect();
                            response = execution.execute(request, body);
                        } catch (Exception ex) {
                            throw new IOException("Unable to recover the http connection during the request interception: " + ex.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Authentication failed during interception: " + e.getMessage());
                }
            }
            return response;
        }
    }

    private static final String SESSION = "rest/current/session";
    private static final String STATUS = "rest/system/status";
    private static final String CONFERENCING_CAPABILITIES = "rest/conferences/capabilities";
    private static final String CONFERENCES = "rest/conferences"; // POST for calling a single participant, GET to list all
    private static final String CONFERENCE = "rest/conferences/%s"; // DELETE to disconnect
    private static final String MEDIASTATS = "rest/conferences/%s/mediastats";
    private static final String SHARED_MEDIASTATS = "/rest/mediastats";
    private static final String AUDIO = "rest/audio";
    private static final String AUDIO_MUTED = "rest/audio/muted";
    private static final String VIDEO_MUTE = "rest/video/local/mute";
    private static final String CONTENT_STATUS = "rest/cameras/contentstatus";
    private static final String VOLUME = "rest/audio/volume";
    private static final String SYSTEM = "rest/system";
    private static final String REBOOT = "rest/system/reboot";
    private static final String COLLABORATION = "rest/collaboration";
    private static final String MICROPHONES = "rest/audio/microphones";
    private static final String APPS = "rest/system/apps";
    private static final String SESSIONS = "rest/current/session/sessions";

    private static final String CONTROL_MUTE_VIDEO = "Mute Local Video";
    private static final String CONTROL_MUTE_MICROPHONES = "Mute Microphones";
    private static final String CONTROL_AUDIO_VOLUME = "Audio Volume";
    private static final String CONTROL_REBOOT = "Reboot";

    /**
     * A number of attempts to perform for getting the conference (call) status while performing
     * {@link #dial(DialDevice)} operation
     */
    private static final int MAX_STATUS_POLL_ATTEMPT = 5;
    /**
     * Default call rate to use for {@link #dial(DialDevice)} operations. Retrieved from the adapter.properties file
     */
    private int DEFAULT_CALL_RATE;
    /**
     * Timestamp of the last control operation, used to determine whether we need to wait
     * for {@link #CONTROL_OPERATION_COOLDOWN_MS} before collecting new statistics
     */
    private long latestControlTimestamp;
    /**
     * Session Id used for authorization
     */
    private String sessionId;
    /**
     * Grace period for device reboot action. It takes about 3 minutes for the device to get fully
     * functional after reboot is triggered
     */
    private static final int REBOOT_GRACE_PERIOD_MS = 200000;
    /**
     * Cooldown period for control operation. Most control operations (toggle/slider based in this case) may be
     * requested multiple times in a row. Normally, a control operation would trigger an emergency delivery action,
     * which is not wanted in this case - such control operations will stack multiple statistics retrieval calls,
     * while instead we can define a cooldown period, so multiple controls operations will be stacked within this
     * period and the control states are modified within the {@link #localStatistics} variable.
     */
    private static final int CONTROL_OPERATION_COOLDOWN_MS = 5000;

    private final ReentrantLock controlOperationsLock = new ReentrantLock();
    private ExtendedStatistics localStatistics;
    private EndpointStatistics localEndpointStatistics;

    /**
     * Instantiate and object with trustAllCertificates set to 'true' for being able to
     * communicate with the devices with invalid certificates
     */
    public PolycomVideoOS() {
        setTrustAllCertificates(true);
    }

    @Override
    protected void internalInit() throws Exception {
        super.internalInit();

        Properties properties = new Properties();
        properties.load(getClass().getResourceAsStream("/adapter.properties"));
        DEFAULT_CALL_RATE = Integer.parseInt(properties.getProperty("defaultCallRate"));
    }

    /**
     * {@inheritDoc}
     *
     * If the call is in progress and another participant is addressed with {@link #CONFERENCES} POST call -
     * VideoOS Rest API will add the participant as another connection for the existing conference call, without
     * creating an additional conference call, so it is commonly expected that there's a single conference at most.
     *
     * After sending dial command we fetch for the status of the new conference call using the device connection
     * url that's being returned by the VideoOS API.
     *
     * @throws RuntimeException If we can't verify (via matching of the dialString (of the DialDevice) to the remoteAddress
     * (of the call stats returned from the device)
     */
    @Override
    public String dial(DialDevice dialDevice) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Dialing using dial string: " + dialDevice.getDialString());
        }
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        Integer callSpeed = dialDevice.getCallSpeed();

        request.put("address", dialDevice.getDialString());
        request.put("rate", (callSpeed != null && callSpeed > 0) ? callSpeed : DEFAULT_CALL_RATE);

        Protocol protocol = dialDevice.getProtocol();
        if (protocol != null) {
            request.put("dialType", protocol.name());
        }
        ArrayNode response = doPost(CONFERENCES, request, ArrayNode.class);
        if (response == null) {
            // Valid response was not received, cannot go further to checking the conference id retrieval
            throw new RuntimeException(String.format("Unable to receive response from %s", CONFERENCES));
        }

        String meetingInfoUrl = getJsonProperty(response.get(0), "href", String.class);
        for (int i = 0; i < MAX_STATUS_POLL_ATTEMPT; i++) {
            JsonNode meetingInfo = doGet(meetingInfoUrl, JsonNode.class);
            if (meetingInfo != null) {
                String conferenceId = getJsonProperty(meetingInfo, "parentConfId", String.class);
                if (null != conferenceId) {
                    String remoteAddress = getJsonProperty(meetingInfo, "address", String.class);
                    if (!StringUtils.isNullOrEmpty(remoteAddress) && remoteAddress.trim().equals(dialDevice.getDialString().trim())) {
                        return conferenceId;
                    }
                }
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException(String.format("An error occurred during dialing out to %s with protocol %s.",
                dialDevice.getDialString(), dialDevice.getProtocol()));
    }

    /**
     * {@inheritDoc}
     *
     * Locking is necessary because statistics is still being collected there and if the device is in the call
     * and collecting some information available while in the call only - it'll end up with a 404 error.
     * If there's a conferenceId available - only one conference is removed, otherwise - the method
     * iterates through all of the available conferences and removes them.
     *
     * {@link PolycomVideoOS} according to the VideoOS documentation for DELETE: /conferences/{conferenceId} method:
     *      This API hangs up and disconnects the specified conference call.
     */
    @Override
    public void hangup(String conferenceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Hangup string received: " + conferenceId);
        }
        controlOperationsLock.lock();
        try {
            if (!StringUtils.isNullOrEmpty(conferenceId)) {
                doDelete(String.format(CONFERENCE, conferenceId));
            } else {
                ArrayNode conferenceCalls = listConferenceCalls();
                for (JsonNode node : conferenceCalls) {
                    Integer id = getJsonProperty(node, "id", Integer.class);
                    doDelete(String.format(CONFERENCE, id));
                }
            }
        } finally {
            controlOperationsLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     */
    @Override
    public CallStatus retrieveCallStatus(String conferenceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Retrieving call status with string: " + conferenceId);
        }
        controlOperationsLock.lock();
        try {
            if(!StringUtils.isNullOrEmpty(conferenceId)){
                JsonNode response = doGet(String.format(CONFERENCE, conferenceId), JsonNode.class);
                if (response == null) {
                    return generateCallStatus(conferenceId, CallStatus.CallStatusState.Disconnected);
                }
                Boolean conferenceIsActive = getJsonProperty(response, "isActive", Boolean.class);
                if (conferenceIsActive != null && conferenceIsActive) {
                    return generateCallStatus(getJsonProperty(response, "id", String.class), CallStatus.CallStatusState.Connected);
                }
            } else {
                ArrayNode conferenceCalls = listConferenceCalls();
                if(conferenceCalls != null && conferenceCalls.size() > 0){
                    return generateCallStatus(getJsonProperty(conferenceCalls.get(0), "id", String.class), CallStatus.CallStatusState.Connected);
                }
            }
        } finally {
            controlOperationsLock.unlock();
        }
        return generateCallStatus(conferenceId, CallStatus.CallStatusState.Disconnected);
    }

    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        Boolean muted = doGet(AUDIO_MUTED, Boolean.class);
        if (muted == null) {
            throw new RuntimeException("Unable to retrieve audio mute status.");
        }
        if (muted) {
            return MuteStatus.Muted;
        } else {
            return MuteStatus.Unmuted;
        }
    }

    @Override
    public void sendMessage(PopupMessage popupMessage) throws Exception {
        throw new UnsupportedOperationException("Send message functionality is not supported by VideoOS API");
    }

    /**
     * {@inheritDoc}
     *
     * Since we need to react on the mute status change to have {@link #localStatistics} data synchronized with
     * the actual values that are sent here - we need to set new status for the locally stored control properties.
     *
     * Response code is handled by {@link com.avispl.symphony.dal.communicator.HttpCommunicator} so there's no
     * need for an additional status code check for validation.
     */
    @Override
    public void mute() throws Exception {
        doPost(AUDIO_MUTED, true);
        updateLocalControllablePropertyState(CONTROL_MUTE_MICROPHONES, "1");
    }

    /**
     * {@inheritDoc}
     *
     * Since we need to react to the mute status change to have {@link #localStatistics} data synchronized with
     * the actual values that are sent here - we need to set new status for the locally stored control properties.
     * This is why response code is being checked for the operation.
     *
     * Response code is handled by {@link com.avispl.symphony.dal.communicator.HttpCommunicator} so there's no
     * need for an additional status code check for validation.
     */
    @Override
    public void unmute() throws Exception {
        doPost(AUDIO_MUTED, false);
        updateLocalControllablePropertyState(CONTROL_MUTE_MICROPHONES, "0");
    }

    /**
     * {@inheritDoc}
     *
     * Statistics collection implies calling multiple endpoints on VideoOS Rest API:
     * rest/system/status
     * rest/system
     * rest/system/apps
     * rest/current/session/sessions
     * rest/audio/microphones
     * rest/cameras/contentstatus
     * rest/conferences/capabilities
     * rest/audio
     * rest/collaboration
     *
     * With the current synchronous approach, collecting all the statistics takes 1-2 sec so making this async for
     * now may not pay off, since it's not possible to reduce the number of requests and reach the same effect.
     */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        EndpointStatistics endpointStatistics = new EndpointStatistics();

        controlOperationsLock.lock();
        try {
            if (isValidControlCoolDown() && localStatistics != null && localEndpointStatistics != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Device is occupied. Skipping statistics refresh call.");
                }
                extendedStatistics.setStatistics(localStatistics.getStatistics());
                extendedStatistics.setControllableProperties(localStatistics.getControllableProperties());

                endpointStatistics.setInCall(localEndpointStatistics.isInCall());
                endpointStatistics.setCallStats(localEndpointStatistics.getCallStats());
                endpointStatistics.setVideoChannelStats(localEndpointStatistics.getVideoChannelStats());
                endpointStatistics.setAudioChannelStats(localEndpointStatistics.getAudioChannelStats());

                return Arrays.asList(extendedStatistics, endpointStatistics);
            }

            Map<String, String> statistics = new HashMap<>();
            List<AdvancedControllableProperty> controls = new ArrayList<>();

            retrieveSystemStatus(statistics);
            retrieveSystemInfo(statistics);
            retrieveApplications(statistics);
            retrieveSessions(statistics);
            retrieveMicrophonesStatistics(statistics);
            retrieveContentStatus(statistics);
            retrieveConferencingCapabilities(statistics);
            retrieveAudioStatus(statistics);
            retrieveCollaborationStatus(statistics);

            statistics.put(CONTROL_AUDIO_VOLUME, "");
            controls.add(createSlider(CONTROL_AUDIO_VOLUME, 0.0f, 100.0f, Float.valueOf(retrieveVolumeLevel())));
            statistics.put(CONTROL_MUTE_MICROPHONES, "");
            controls.add(createSwitch(CONTROL_MUTE_MICROPHONES, "On", "Off", retrieveMuteStatus().equals(MuteStatus.Muted)));
            statistics.put(CONTROL_MUTE_VIDEO, "");
            controls.add(createSwitch(CONTROL_MUTE_VIDEO, "On", "Off", retrieveVideoMuteStatus()));
            statistics.put(CONTROL_REBOOT, "");
            controls.add(createButton(CONTROL_REBOOT, CONTROL_REBOOT, "Rebooting...", REBOOT_GRACE_PERIOD_MS));

            extendedStatistics.setStatistics(statistics);
            extendedStatistics.setControllableProperties(controls);

            Integer conferenceId = retrieveActiveConferenceCallStatistics(statistics);
            boolean validConferenceId = conferenceId != null && conferenceId > -1;

            endpointStatistics.setInCall(validConferenceId);
            if (validConferenceId) {
                CallStats callStats = new CallStats();
                callStats.setCallId(String.valueOf(conferenceId));
                callStats.setProtocol(statistics.get("Active Conference#Protocol"));
                callStats.setRequestedCallRate(DEFAULT_CALL_RATE);

                AudioChannelStats audioChannelStats = new AudioChannelStats();
                VideoChannelStats videoChannelStats = new VideoChannelStats();
                ContentChannelStats contentChannelStats = new ContentChannelStats();

                ArrayNode conferenceCallMediaStats = retrieveConferenceCallMediaStats(conferenceId);
                processConferenceCallMediaStats(conferenceCallMediaStats, audioChannelStats, videoChannelStats, callStats);
                retrieveSharedMediaStats(contentChannelStats);

                endpointStatistics.setCallStats(callStats);
                endpointStatistics.setAudioChannelStats(audioChannelStats);
                endpointStatistics.setVideoChannelStats(videoChannelStats);
                endpointStatistics.setContentChannelStats(contentChannelStats);
            }

            localStatistics = extendedStatistics;
            localEndpointStatistics = endpointStatistics;
        } finally {
            controlOperationsLock.unlock();
        }

        return Arrays.asList(extendedStatistics, endpointStatistics);
    }

    /**
     * Updates the local value of a given controllable property.
     *
     * @param controlName name of a controllable property stored in {@link #localStatistics} variable
     * @param value target value of a controllable property
     */
    private void updateLocalControllablePropertyState(String controlName, String value) {
        if(null != localStatistics) {
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty ->
                    advancedControllableProperty.getName().equals(controlName)).findFirst()
                    .ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(value));
        }
    }

    /**
     * Generate CallStatus instance based on callId and callStatusState parameters
     *
     * @param callId id of the conference
     * @param callStatusState state of the call to use
     * @return {@link CallStatus} instance, indicating the requested status of the call
     */
    private CallStatus generateCallStatus(String callId, CallStatus.CallStatusState callStatusState){
        CallStatus callStatus = new CallStatus();
        callStatus.setCallId(callId);
        callStatus.setCallStatusState(callStatusState);
        return callStatus;
    }

    /**
     * Check if outcoming video feed is muted
     *
     * @return boolean outcoming video feed mute status
     * @throws Exception during http communication
     */
    private Boolean retrieveVideoMuteStatus() throws Exception {
        JsonNode response = doGet(VIDEO_MUTE, JsonNode.class);
        return getJsonProperty(response, "result", Boolean.class);
    }

    /**
     * Set local video feed mute status
     * When operation is succeeded - we need to update {@link #localStatistics} with the new state of the
     * controllable property.
     * When operation has failed - RuntimeException is thrown, containing the reason of an unsuccessful operation, if
     * available, according to the {@link #VIDEO_MUTE} response body model:
     *    {
     *    "success": boolean,
     *    "reason": "string"
     *    }
     *
     *
     * @param status boolean indicating the target outcoming video feed state
     * @throws Exception if any error has occurred
     */
    private void updateVideoMuteStatus(boolean status) throws Exception {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("mute", status);
        JsonNode response = doPost(VIDEO_MUTE, request, JsonNode.class);

        Boolean success = getJsonProperty(response, "success", Boolean.class);
        if(Boolean.TRUE.equals(success)) {
            updateLocalControllablePropertyState(CONTROL_MUTE_VIDEO, status ? "1" : "0");
        } else {
            throw new RuntimeException("Unable to update local video mute status: " +
                    getJsonProperty(response, "reason", String.class));
        }
    }

    /**
     * Get media statistics related to the current conference call
     *
     * Since VideoOS API does not provide any specific values for totalPacketLoss/percentPacketLoss/callRate - these
     * parameters are calculated based on audio and video channel rate/packetLoss information. VideoOS audio/video
     * channel rates are reported is in kbps (kilobits per second) by default,
     * which matches Symphony {@link ChannelStats} model
     *
     * If both video and audio channel stats are available and the specific value=!null - the data is summed up.
     * Null values are omitted, having both audio and video channel packet loss data==null will end up with passing
     * null value as the callStats parameter value (as an oppose to 0, which would mean that the values are provided
     * and are equal to zero).
     *
     * @param conferenceCallMediaStats data retrieved from the VidoOS API
     * @param audioChannelStats        entity containing audio stats
     * @param videoChannelStats        entity containing video stats
     * @param callStats                entity containing call stats
     */
    private void processConferenceCallMediaStats(ArrayNode conferenceCallMediaStats, AudioChannelStats audioChannelStats,
                                                  VideoChannelStats videoChannelStats, CallStats callStats) {
        conferenceCallMediaStats.forEach(jsonNode -> {
            switch (Objects.requireNonNull(getJsonProperty(jsonNode, "mediaDirection", String.class))) {
                case "RX":
                    switch (Objects.requireNonNull(getJsonProperty(jsonNode, "mediaType", String.class))) {
                        case "AUDIO":
                            audioChannelStats.setBitRateRx(getJsonProperty(jsonNode, "actualBitRate", Integer.class));
                            audioChannelStats.setJitterRx(getJsonProperty(jsonNode, "jitter", Float.class));
                            audioChannelStats.setPacketLossRx(getJsonProperty(jsonNode, "packetLoss", Integer.class));
                            audioChannelStats.setPercentPacketLossRx(getJsonProperty(jsonNode, "percentPacketLoss", Float.class));
                            audioChannelStats.setCodec(getJsonProperty(jsonNode, "mediaAlgorithm", String.class));
                            break;
                        case "VIDEO":
                            videoChannelStats.setBitRateRx(getJsonProperty(jsonNode, "actualBitRate", Integer.class));
                            videoChannelStats.setJitterRx(getJsonProperty(jsonNode, "jitter", Float.class));
                            videoChannelStats.setPacketLossRx(getJsonProperty(jsonNode, "packetLoss", Integer.class));
                            videoChannelStats.setPercentPacketLossRx(getJsonProperty(jsonNode, "percentPacketLoss", Float.class));
                            videoChannelStats.setFrameRateRx(getJsonProperty(jsonNode, "actualFrameRate", Float.class));
                            videoChannelStats.setCodec(getJsonProperty(jsonNode, "mediaAlgorithm", String.class));
                            videoChannelStats.setFrameSizeRx(getJsonProperty(jsonNode, "mediaFormat", String.class));
                            break;
                        default:
                            if (logger.isDebugEnabled()) {
                                logger.debug("Not implemented: " + getJsonProperty(jsonNode, "mediaType", String.class));
                            }
                            break;
                    }
                    break;
                case "TX":
                    switch (Objects.requireNonNull(getJsonProperty(jsonNode, "mediaType", String.class))) {
                        case "AUDIO":
                            audioChannelStats.setBitRateTx(getJsonProperty(jsonNode, "actualBitRate", Integer.class));
                            audioChannelStats.setJitterTx(getJsonProperty(jsonNode, "jitter", Float.class));
                            audioChannelStats.setPacketLossTx(getJsonProperty(jsonNode, "packetLoss", Integer.class));
                            audioChannelStats.setPercentPacketLossTx(getJsonProperty(jsonNode, "percentPacketLoss", Float.class));
                            audioChannelStats.setCodec(getJsonProperty(jsonNode, "mediaAlgorithm", String.class));
                            break;
                        case "VIDEO":
                            videoChannelStats.setBitRateTx(getJsonProperty(jsonNode, "actualBitRate", Integer.class));
                            videoChannelStats.setJitterTx((getJsonProperty(jsonNode, "jitter", Float.class)));
                            videoChannelStats.setPacketLossTx(getJsonProperty(jsonNode, "packetLoss", Integer.class));
                            videoChannelStats.setPercentPacketLossTx(getJsonProperty(jsonNode, "percentPacketLoss", Float.class));
                            videoChannelStats.setFrameRateTx(getJsonProperty(jsonNode, "actualFrameRate", Float.class));
                            videoChannelStats.setCodec(getJsonProperty(jsonNode, "mediaAlgorithm", String.class));
                            videoChannelStats.setFrameSizeTx(getJsonProperty(jsonNode, "mediaFormat", String.class));
                            break;
                        default:
                            if (logger.isDebugEnabled()) {
                                logger.debug("Not implemented for media type: " + getJsonProperty(jsonNode, "mediaType", String.class));
                            }
                            break;
                    }
                    break;
                default:
                    if (logger.isDebugEnabled()) {
                        logger.debug("Not implemented for media direction: " + getJsonProperty(jsonNode, "mediaDirection", String.class));
                    }
                    break;
            }
        });
        callStats.setTotalPacketLossTx(sumPacketData(audioChannelStats.getPacketLossTx(), videoChannelStats.getPacketLossTx()));
        callStats.setTotalPacketLossRx(sumPacketData(audioChannelStats.getPacketLossRx(), videoChannelStats.getPacketLossRx()));
        callStats.setPercentPacketLossTx(sumPacketData(audioChannelStats.getPercentPacketLossTx(), videoChannelStats.getPercentPacketLossTx()));
        callStats.setPercentPacketLossRx(sumPacketData(audioChannelStats.getPercentPacketLossRx(), videoChannelStats.getPercentPacketLossRx()));

        callStats.setCallRateRx(sumPacketData(videoChannelStats.getBitRateRx(), audioChannelStats.getBitRateRx()));
        callStats.setCallRateTx(sumPacketData(videoChannelStats.getBitRateTx(), audioChannelStats.getBitRateTx()));
    }

    /**
     * Retrieve Integer sum of 2 values of packet data.
     * The main issue is that any specific (or both) values it shouldn't be treated as 0 since this would mean
     * the value is present and is equal to 0, whereas null value means that the value is not available.
     * Thus, this function returns null if a and b integer values are equal to null, and consider either
     * of them as 0 if other value is present, providing the total sum of values with one of the values being
     * ignored.
     *
     * @param a first Integer value to add to a total sum
     * @param b second Integer value to add to a total sum
     * @return Integer sum value of a and b. Null if a == b == null
     */
    private Integer sumPacketData(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        } else if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        }
        return Integer.sum(a, b);
    }

    /**
     * Retrieve Integer sum of 2 values of packet data.
     * The main issue is that any specific (or both) values it shouldn't be treated as 0 since this would mean
     * the value is present and is equal to 0, whereas null value means that the value is not available.
     * Thus, this function returns null if a and b integer values are equal to null, and consider either
     * of them as 0 if other value is present, providing the total sum of values with one of the values being
     * ignored.
     *
     * @param a first Float value to add to a total sum
     * @param b second Float value to add to a total sum
     * @return Float sum value of a and b. Null if a == b == null
     */
    private Float sumPacketData(Float a, Float b) {
        if (a == null && b == null) {
            return null;
        } else if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        }
        return Float.sum(a, b);
    }

    /**
     * Retrieve basic system stats - auto answering feature, cameras/mics basic info etc.
     *
     * @param statistics map to set data to
     * @throws Exception during http communication, except 403
     */
    private void retrieveSystemStatus(Map<String, String> statistics) throws Exception {
        ArrayNode response = doGet(STATUS, ArrayNode.class);
        response.iterator().forEachRemaining(jsonNode -> {
            String langtag = getJsonProperty(jsonNode, "langtag", String.class);
            ArrayNode stateList = getJsonProperty(jsonNode, "stateList", ArrayNode.class);

            if (langtag != null && stateList != null) {
                statistics.put("System Status#" + normalizeStringPropertyName(
                        langtag.replaceAll("_", " ")),
                        stateList.get(0).asText().replaceAll("_", " ").toUpperCase());
            }
        });
    }

    /**
     * Change property name to start with a capital letter
     *
     * @param string parameter name
     * @return {@link String} property name with first capital letter
     */
    private String normalizeStringPropertyName(String string) {
        return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase();
    }

    /**
     * Retrieve the current state of the collaboration session
     *
     * @return Integer value of the current device volume level
     * @throws Exception during http communication
     */
    private void retrieveCollaborationStatus(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(COLLABORATION, JsonNode.class);
        if (!response.isNull()) {
            String sessionState = getJsonProperty(response, "state", String.class);
            statistics.put("Collaboration#Session State", sessionState);
            if ("ACTIVE".equals(sessionState)) {
                statistics.put("Collaboration#Session ID", getJsonProperty(response, "id", String.class));
            }
        }
    }

    /**
     * Retrieve current volume level of the device
     *
     * @return Integer value of the current device volume level
     * @throws Exception during http communication
     */
    private Integer retrieveVolumeLevel() throws Exception {
        return doGet(VOLUME, Integer.class);
    }

    /**
     * Update device's volume with a given value
     * Since we need to react to the volume level change to have {@link #localStatistics} data synchronized with
     * the actual values that are sent here - we need to set new status for the locally stored control properties.
     * This is why response code is being checked for the operation.
     * Response code is handled by {@link com.avispl.symphony.dal.communicator.HttpCommunicator} so there's no
     * need for an additional status code check for validation.
     *
     * @param value target value for the device volume
     * @throws Exception during http communication
     */
    private void updateVolumeLevel(int value) throws Exception {
        doPost(VOLUME, value);
        updateLocalControllablePropertyState(CONTROL_AUDIO_VOLUME, String.valueOf(value));
    }

    /**
     * Retrieve stats of currently active conference call
     * VideoOS Rest API populate a list of conference calls when {@link #CONFERENCE} call is performed.
     * The default behaviour for the {@link PolycomVideoOS} is listed in {@link #dial(DialDevice)} method.
     * It is expected that there is a single active conference at most at any given moment of time, so 0th conference is
     * fetched by default.
     *
     * @param statistics map to put values to
     * @return active conference id (#0), -1 if there are no active conferences available
     * @throws IllegalStateException if more than 1 active conference was found
     * @throws Exception if any other error has occurred
     */
    private Integer retrieveActiveConferenceCallStatistics(Map<String, String> statistics) throws Exception {
        ArrayNode conferenceCalls = listConferenceCalls();
        int conferenceCallsNumber = conferenceCalls.size();

        if (conferenceCallsNumber <= 0) {
            return -1;
        }
        if (conferenceCallsNumber > 1) {
           throw new IllegalStateException(String.format("%s conference calls are in progress, 1 expected. Unable to proceed.",
                   conferenceCallsNumber));
        }

        JsonNode activeConference = conferenceCalls.get(0);
        ArrayNode terminals = (ArrayNode) activeConference.get("terminals");
        ArrayNode connections = (ArrayNode) activeConference.get("connections");

        statistics.put("Active Conference#Conference ID", getJsonProperty(activeConference, "id", String.class));
        Long conferenceStartTimestamp = getJsonProperty(activeConference, "startTime", Long.class);
        if(null != conferenceStartTimestamp) {
            statistics.put("Active Conference#Conference start time", String.valueOf(new Date(conferenceStartTimestamp)));
        }

        // Adding i+1 instead of i so terminals and connections are listed starting with 1, not 0
        if (terminals != null) {
            for(int i = 0; i < terminals.size(); i++) {
                int terminalNumber = i + 1;
                statistics.put(String.format("Active Conference#Terminal %s address", terminalNumber), getJsonProperty(terminals.get(i), "address", String.class));
                statistics.put(String.format("Active Conference#Terminal %s system", terminalNumber), getJsonProperty(terminals.get(i), "systemID", String.class));
            }
        }
        if (connections != null) {
            for(int i = 0; i < connections.size(); i++) {
                int connectionNumber = i + 1;
                statistics.put(String.format("Active Conference#Connection %s type", connectionNumber), getJsonProperty(connections.get(i), "callType", String.class));
                statistics.put(String.format("Active Conference#Connection %s info", connectionNumber), getJsonProperty(connections.get(i), "callInfo", String.class));
            }
        }
        Long activeConferenceStartTime = getJsonProperty(activeConference, "startTime", Long.class);
        if (activeConferenceStartTime != null) {
            statistics.put("Active Conference#Conference start time", new Date(activeConferenceStartTime).toString());
        }
        return getJsonProperty(activeConference, "id", Integer.class);
    }

    /**
     * Retrieve list of all current conference calls
     *
     * @return ArrayNode containing the list of conference calls
     * @throws Exception during http communication
     */
    private ArrayNode listConferenceCalls() throws Exception {
        return doGet(CONFERENCES, ArrayNode.class);
    }

    /**
     * Retrieve device's active call media stats. Device does not immediately release the device when the hangup
     * is requested so it may end up heve while is not in the call anymore, so 404 error would indicate that there's
     * no such conference anymore, but there has been one since the conferenceId was retrieved.
     *
     * @return ArrayNode containing RX/TX stats
     * @throws Exception during http communication
     */
    private ArrayNode retrieveConferenceCallMediaStats(int conferenceId) throws Exception {
        try {
            return doGet(String.format(MEDIASTATS, conferenceId), ArrayNode.class);
        } catch (CommandFailureException cfe) {
            if (cfe.getStatusCode() == 404) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Conference " + conferenceId + " is not available anymore. Skipping media stats retrieval.");
                }
                return JsonNodeFactory.instance.arrayNode();
            } else {
                logger.error("Unable to find conference by id " + conferenceId, cfe);
                throw cfe;
            }
        }
    }

    /**
     * Retrieve information about shared media content.
     * It is considered that 1 source is shared at any given time, that's why it's get(0)
     * Possible content sources are:
     * •  Content App support
     * •  Apple Airplay
     * •  Miracast
     * •  HDMI input
     * •  Whiteboarding
     * @param contentChannelStats to save shared media stats to
     * @throws Exception during http communication (shared content stats retrieval)
     */
    private void retrieveSharedMediaStats(ContentChannelStats contentChannelStats) throws Exception {
        JsonNode response = doGet(SHARED_MEDIASTATS, JsonNode.class);
        if(response != null) {
            ArrayNode vars = (ArrayNode) response.get("vars");
            if(vars != null && vars.size() > 0){
                JsonNode sharedStats = vars.get(0);
                contentChannelStats.setFrameSizeTxWidth(getJsonProperty(sharedStats, "width", Integer.class));
                contentChannelStats.setFrameSizeTxHeight(getJsonProperty(sharedStats, "height", Integer.class));
                contentChannelStats.setFrameRateTx(getJsonProperty(sharedStats, "framerate", Float.class));
                contentChannelStats.setBitRateTx(getJsonProperty(sharedStats, "bitrate", Integer.class));
            }
        }
    }

    /**
     * Request device reboot
     * Currently, a reboot action changes previously accepted certificates, which would lead to
     * {@link ResourceNotReachableException} without a proper way to recover. Current workaround is to call
     * {@link #disconnect()} on reboot and on {@link ResourceNotReachableException} in the
     * {@link PolycomVideoOSInterceptor} in case if the device has been rebooted externally.
     *
     * Response code is handled by {@link com.avispl.symphony.dal.communicator.HttpCommunicator} so there's no
     * need for an additional status code check for validation, so if doPost request has succeeded - we can reset
     * the sessionId and call disconnect()
     *
     * @throws Exception during http communication
     */
    private void reboot() throws Exception {
        ObjectNode rebootRequest = JsonNodeFactory.instance.objectNode();
        rebootRequest.put("action", "reboot");
        doPost(REBOOT, rebootRequest);

        sessionId = null;
        disconnect();
    }

    /**
     * Retrieve device system information, including hardware info and lan status
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveSystemInfo(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(SYSTEM, JsonNode.class);
        if (response == null) {
            return;
        }
        addStatisticsProperty(statistics, "System#Serial Number", response.get("serialNumber"));
        addStatisticsProperty(statistics, "System#Software Version", response.get("softwareVersion"));
        addStatisticsProperty(statistics, "System#System State", response.get("state"));
        addStatisticsProperty(statistics, "System#System Name", response.get("systemName"));
        addStatisticsProperty(statistics, "System#System Uptime", response.get("uptime"));
        addStatisticsProperty(statistics, "System#System Build", response.get("build"));
        addStatisticsProperty(statistics, "System#System Reboot Needed", response.get("rebootNeeded"));
        addStatisticsProperty(statistics, "System#Device Model", response.get("model"));
        addStatisticsProperty(statistics, "System#Device Hardware Version", response.get("hardwareVersion"));

        JsonNode lanStatus = response.get("lanStatus");
        if (lanStatus == null) {
            return;
        }
        addStatisticsProperty(statistics, "Lan Status#Duplex", lanStatus.get("duplex"));
        addStatisticsProperty(statistics, "Lan Status#Speed Mbps", lanStatus.get("speedMbps"));
        addStatisticsProperty(statistics, "Lan Status#State", lanStatus.get("state"));
    }

    /**
     * Retrieve list of device's applications
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveApplications(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(APPS, JsonNode.class);
        JsonNode applications = response.get("apps");
        if (applications == null) {
            return;
        }
        applications.forEach(jsonNode -> {
            String appName = getJsonProperty(jsonNode, "appName", String.class);
            statistics.put("Applications#" + appName + " Version",
                    getJsonProperty(jsonNode, "versionInfo", String.class));

            Long lastUpdatedOn = getJsonProperty(jsonNode, "lastUpdatedOn", Long.class);
            if (lastUpdatedOn != null) {
                statistics.put("Applications#" + appName + " Last Updated",
                        new Date(lastUpdatedOn).toString());
            }
        });
    }

    /**
     * Retrieve list of active sessions on the device
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveSessions(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(SESSIONS, JsonNode.class);
        JsonNode sessionList = response.get("sessionList");
        if (sessionList == null) {
            return;
        }
        sessionList.forEach(jsonNode -> {
            Boolean isConnected = getJsonProperty(jsonNode, "isConnected", Boolean.class);
            Boolean isAuthenticated = getJsonProperty(jsonNode, "isAuthenticated", Boolean.class);

            statistics.put(String.format("Active Sessions#%s : %s : %s(%s)", getJsonProperty(jsonNode, "userId", String.class),
                    getJsonProperty(jsonNode, "role", String.class), getJsonProperty(jsonNode, "location", String.class),
                    getJsonProperty(jsonNode, "clientType", String.class)),
                    String.format("%s, %s", ((isConnected == null ? false : isConnected) ? "CONNECTED" : "NOT CONNECTED"),
                            ((isAuthenticated == null ? false : isAuthenticated) ? "AUTHENTICATED" : "NOT AUTHENTICATED")));
        });
    }

    /**
     * Retrieve list of device's microphones and it's information/state
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveMicrophonesStatistics(Map<String, String> statistics) throws Exception {
        ArrayNode response = doGet(MICROPHONES, ArrayNode.class);
        if (response == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve Microphones data.");
            }
            return;
        }
        response.forEach(jsonNode -> {
            String microphoneOrderingNumber = getJsonProperty(jsonNode, "number", String.class);
            statistics.put(String.format("Microphones#Microphone %s Name", microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "typeInString", String.class));
            statistics.put(String.format("Microphones#Microphone %s State", microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "state", String.class));
            statistics.put(String.format("Microphones#Microphone %s Type", microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "type", String.class));
            statistics.put(String.format("Microphones#Microphone %s HW Version", microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "hwVersion", String.class));
            statistics.put(String.format("Microphones#Microphone %s SW Version", microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "swVersion", String.class));
            statistics.put(String.format("Microphones#Microphone %s Mute", microphoneOrderingNumber),
                    String.valueOf("0".equals(getJsonProperty(jsonNode, "mute", String.class))));
        });
    }

    /**
     * Retrieve device content sharing status
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveContentStatus(Map<String, String> statistics) throws Exception {
        String response = doGet(CONTENT_STATUS, String.class);
        statistics.put("Cameras#Content Status", response);
    }

    /**
     * Retrieve device conferencing capabilities
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveConferencingCapabilities(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(CONFERENCING_CAPABILITIES, JsonNode.class);
        statistics.put("Conferencing Capabilities#Blast Dial", response.get("canBlastDial").asBoolean() ? "Available" : "Not Available");
        statistics.put("Conferencing Capabilities#Audio Call", response.get("canMakeAudioCall").asBoolean() ? "Available" : "Not Available");
        statistics.put("Conferencing Capabilities#Video Call", response.get("canMakeVideoCall").asBoolean() ? "Available" : "Not Available");
    }

    /**
     * Retrieve current audio status
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveAudioStatus(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(AUDIO, JsonNode.class);
        statistics.put("Audio#Mute Locked", getJsonProperty(response, "muteLocked", String.class));
        statistics.put("Audio#Microphones Connected", getJsonProperty(response, "numOfMicsConnected", String.class));
    }

    /**
     * Add statistics property if the property exists
     * Otherwise - skip
     *
     * @param statistics map to save property to
     * @param name       name of the property
     * @param node       to extract textual data from
     */
    private void addStatisticsProperty(Map<String, String> statistics, String name, JsonNode node) {
        if (node != null && !node.isNull()) {
            String value = node.asText();
            if (!StringUtils.isNullOrEmpty(value, true)) {
                statistics.put(name, value);
            }
        }
    }

    /**
     * Retrieve the json property and return it if it exists.
     * Return null otherwise
     *
     * @param json     to fetch property value from
     * @param property name of the property to retrieve
     * @param type     type to resolve property to, to avoid unsafe use of .asType calls for JsonNode instance.
     * @return JsonNode with an expected value or blank node otherwise
     * @throws IllegalArgumentException if the passed JSON argument is null or
     *                                  if there's no matching case for the type parameter passed
     */
    @SuppressWarnings("unchecked")
    private <T> T getJsonProperty(JsonNode json, String property, Class<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON argument cannot be null.");
        }
        JsonNode value = json.get(property);
        if (value == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(property + " property is not available.");
            }
            return null;
        }
        if (type.equals(String.class)) {
            return (T) value.asText();
        } else if (type.equals(Integer.class)) {
            return (T) Integer.valueOf(value.asInt());
        } else if (type.equals(Boolean.class)) {
            return (T) Boolean.valueOf(value.asBoolean());
        } else if (type.equals(Float.class)) {
            return (T) Float.valueOf(value.floatValue());
        } else if (type.equals(Long.class)) {
            return (T) Long.valueOf(value.longValue());
        } else if (type.equals(ArrayNode.class)) {
            return (T) value;
        }
        throw new IllegalArgumentException(String.format("Unable to retrieve value for property %s and type %s", property, type.getName()));
    }

    @Override
    protected void authenticate() throws Exception {
        // authenticate and get sessionId like "PSN0HppfZap7wtV9MgTeGKLZL+q8q+65Te6g/r61KLqC26+thY"
        sessionId = null;
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("user", getLogin());
        request.put("password", getPassword());

        JsonNode json = doPost(SESSION, request, JsonNode.class);

        Boolean success = getJsonProperty(json, "success", Boolean.class);
        if (success == null ? false : success) {
            sessionId = getJsonProperty(json, "sessionId", String.class);
        } else {
            throw new FailedLoginException("Unable to login.");
        }
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        if (sessionId != null && !uri.equals(SESSION)) {
            headers.add("Cookie", String.format("session_id=%s; Path=/; Domain=%s; Secure; HttpOnly;", sessionId, getHost()));
        }
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String property = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        controlOperationsLock.lock();
        try {
            switch (property) {
                case CONTROL_MUTE_MICROPHONES:
                    if (value.equals("0")) {
                        unmute();
                    } else {
                        mute();
                    }
                    updateLatestControlTimestamp();
                    break;
                case CONTROL_MUTE_VIDEO:
                    updateVideoMuteStatus(value.equals("1"));
                    updateLatestControlTimestamp();
                    break;
                case CONTROL_AUDIO_VOLUME:
                    updateVolumeLevel(Math.round(Float.parseFloat(value)));
                    updateLatestControlTimestamp();
                    break;
                case CONTROL_REBOOT:
                    reboot();
                    updateLatestControlTimestamp();
                    break;
                default:
                    break;
            }
        } finally {
            controlOperationsLock.unlock();
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (CollectionUtils.isEmpty(list)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for (ControllableProperty controllableProperty : list) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();
        List<ClientHttpRequestInterceptor> restTemplateInterceptors = restTemplate.getInterceptors();

        if (!restTemplateInterceptors.contains(videoOSInterceptor))
            restTemplateInterceptors.add(videoOSInterceptor);

        return restTemplate;
    }

    /**
     * Instantiate Text controllable property
     *
     * @param name         name of the property
     * @param labelOn      "On" label value
     * @param labelOff     "Off" label value
     * @param initialValue initial value of the switch control (1|0)
     * @return instance of AdvancedControllableProperty with AdvancedControllableProperty.Switch as type
     */
    private AdvancedControllableProperty createSwitch(String name, String labelOn, String labelOff, Boolean initialValue) {
        AdvancedControllableProperty.Switch controlSwitch = new AdvancedControllableProperty.Switch();
        controlSwitch.setLabelOn(labelOn);
        controlSwitch.setLabelOff(labelOff);
        return new AdvancedControllableProperty(name, new Date(), controlSwitch, initialValue);
    }

    /***
     * Create AdvancedControllableProperty slider instance
     *
     * @param name name of the control
     * @param initialValue initial value of the control
     * @param rangeStart start value for the slider
     * @param rangeEnd end value for the slider
     *
     * @return AdvancedControllableProperty slider instance
     */
    private AdvancedControllableProperty createSlider(String name, Float rangeStart, Float rangeEnd, Float initialValue) {
        AdvancedControllableProperty.Slider slider = new AdvancedControllableProperty.Slider();
        slider.setLabelStart(String.valueOf(rangeStart));
        slider.setLabelEnd(String.valueOf(rangeEnd));
        slider.setRangeStart(rangeStart);
        slider.setRangeEnd(rangeEnd);

        return new AdvancedControllableProperty(name, new Date(), slider, initialValue);
    }

    /**
     * Instantiate Text controllable property
     *
     * @param name         name of the property
     * @param label        default button label
     * @param labelPressed button label when is pressed
     * @param gracePeriod  period to pause monitoring statistics for
     * @return instance of AdvancedControllableProperty with AdvancedControllableProperty.Button as type
     */
    private AdvancedControllableProperty createButton(String name, String label, String labelPressed, long gracePeriod) {
        AdvancedControllableProperty.Button button = new AdvancedControllableProperty.Button();
        button.setLabel(label);
        button.setLabelPressed(labelPressed);
        button.setGracePeriod(gracePeriod);

        return new AdvancedControllableProperty(name, new Date(), button, "");
    }

    /**
     * Update timestamp of the latest control operation
     */
    private void updateLatestControlTimestamp() {
        latestControlTimestamp = System.currentTimeMillis();
    }

    /***
     * Check whether the control operations cooldown has ended
     *
     * @return boolean value indicating whether the cooldown has ended or not
     */
    private boolean isValidControlCoolDown() {
        return (System.currentTimeMillis() - latestControlTimestamp) < CONTROL_OPERATION_COOLDOWN_MS;
    }
}
