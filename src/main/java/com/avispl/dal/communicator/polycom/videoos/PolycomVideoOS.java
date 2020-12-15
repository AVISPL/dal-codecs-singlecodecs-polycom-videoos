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
 */
public class PolycomVideoOS extends RestCommunicator implements CallController, Monitorable, Controller {

    private ClientHttpRequestInterceptor videoOSInterceptor = new PolycomVideoOSInterceptor();

    private static String SESSION = "rest/current/session";
    private static String STATUS = "rest/system/status";
    private static String CONFERENCING_CAPABILITIES = "rest/conferences/capabilities";
    private static String CONFERENCE = "rest/conferences"; // POST for calling a single participant, GET to list all
    private static String CONFERENCES = "rest/conferences/%s"; // DELETE to disconnect
    private static String MEDIASTATS = "rest/conferences/%s/mediastats";
    private static String SHARED_MEDIASTATS = "/rest/mediastats";
    private static String AUDIO = "rest/audio";
    private static String AUDIO_MUTED = "rest/audio/muted";
    private static String VIDEO_MUTE = "rest/video/local/mute";
    private static String CONTENT_STATUS = "rest/cameras/contentstatus";
    private static String VOLUME = "rest/audio/volume";
    private static String SYSTEM = "rest/system";
    private static String REBOOT = "rest/system/reboot";
    private static String COLLABORATION = "rest/collaboration";
    private static String MICROPHONES = "rest/audio/microphones";
    private static String APPS = "rest/system/apps";
    private static String SESSIONS = "rest/current/session/sessions";
    private static String URL_TEMPLATE = "%s://%s/%s";

    private final ReentrantLock controlOperationsLock = new ReentrantLock();
    private long latestControlTimestamp;
    private ExtendedStatistics localStatistics;
    private EndpointStatistics localEndpointStatistics;

    private String sessionId;
    private static final int CALL_RATE = 1920;
    private static final int MAX_STATUS_POLL_ATTEMPT = 5;

    /* Grace period for device reboot action. It takes about 3 minutes for the device to get fully
     * functional after reboot is triggered */
    private static final int REBOOT_GRACE_PERIOD_MS = 200000;

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
    }

    @Override
    public String dial(DialDevice dialDevice) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Dialing using dial string: " + dialDevice.getDialString());
        }
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        Integer callSpeed = dialDevice.getCallSpeed();

        request.put("address", dialDevice.getDialString());
        request.put("rate", (callSpeed != null && callSpeed > 0) ? callSpeed : CALL_RATE);

        Protocol protocol = dialDevice.getProtocol();
        if (protocol != null) {
            request.put("dialType", protocol.name());
        }
        ArrayNode response = doPost(CONFERENCE, request, ArrayNode.class);
        if (response == null) {
            return null;
        }

        for (int i = 0; i < MAX_STATUS_POLL_ATTEMPT; i++) {
            JsonNode meetingInfo = doGet(buildHttpUrl(getJsonProperty(response.get(0), "href", String.class)), JsonNode.class);
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
        return null;
    }

    /**
     * Locking is necessary because statistics is still being collected there and if the device is in the call
     * and collecting some information available while in the call only - it'll end up with a 404 error.
     * If there's a conferenceId available - only one conference is removed, otherwise - the method
     * iterates through all of the available conferences and removes them.
     */
    @Override
    public void hangup(String conferenceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Hangup string received: " + conferenceId);
        }
        controlOperationsLock.lock();
        try {
            if (!StringUtils.isNullOrEmpty(conferenceId)) {
                doDelete(String.format(CONFERENCES, conferenceId));
            } else {
                ArrayNode conferenceCall = listConferenceCalls();
                for (JsonNode node : conferenceCall) {
                    Integer id = getJsonProperty(node, "id", Integer.class);
                    doDelete(String.format(CONFERENCES, id));
                }
            }
        } finally {
            controlOperationsLock.unlock();
        }
    }

    /**
     * /rest/conferences/{id}
     */
    @Override
    public CallStatus retrieveCallStatus(String conferenceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Retrieving call status with string: " + conferenceId);
        }
        controlOperationsLock.lock();
        CallStatus callStatus = new CallStatus();
        callStatus.setCallStatusState(CallStatus.CallStatusState.Disconnected);
        callStatus.setCallId(conferenceId);
        try {
            if(!StringUtils.isNullOrEmpty(conferenceId)){
                JsonNode response = doGet(buildHttpUrl(String.format(CONFERENCES, conferenceId)), JsonNode.class);
                if (response == null) {
                    return callStatus;
                }
                Boolean conferenceIsActive = getJsonProperty(response, "isActive", Boolean.class);
                if (conferenceIsActive != null && conferenceIsActive) {
                    callStatus.setCallId(getJsonProperty(response, "id", String.class));
                    callStatus.setCallStatusState(CallStatus.CallStatusState.Connected);
                    return callStatus;
                }
            } else {
                ArrayNode conferences = listConferenceCalls();
                if(conferences != null && conferences.size() > 0){
                    callStatus.setCallId(getJsonProperty(conferences.get(0), "id", String.class));
                    callStatus.setCallStatusState(CallStatus.CallStatusState.Connected);
                    return callStatus;
                }
            }
        } finally {
            controlOperationsLock.unlock();
        }
        return callStatus;
    }

    @Override
    public MuteStatus retrieveMuteStatus() throws Exception {
        controlOperationsLock.lock();
        try {
            Boolean muted = doGet(buildHttpUrl(AUDIO_MUTED), Boolean.class);
            if (muted) {
                return MuteStatus.Muted;
            } else {
                return MuteStatus.Unmuted;
            }
        } finally {
            controlOperationsLock.unlock();
        }
    }

    @Override
    public void sendMessage(PopupMessage popupMessage) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void mute() throws Exception {
        controlOperationsLock.lock();
        try {
            doPost(AUDIO_MUTED, true);
        } finally {
            controlOperationsLock.unlock();
        }
    }

    @Override
    public void unmute() throws Exception {
        controlOperationsLock.lock();
        try {
            doPost(AUDIO_MUTED, false);
        } finally {
            controlOperationsLock.unlock();
        }
    }

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

            statistics.put("Audio Volume", "");
            controls.add(createSlider("Audio Volume", 0.0f, 100.0f, Float.valueOf(retrieveVolumeLevel())));
            statistics.put("Mute Microphones", "");
            controls.add(createSwitch("Mute Microphones", "On", "Off", retrieveMuteStatus().equals(MuteStatus.Muted)));
            statistics.put("Mute Local Video", "");
            controls.add(createSwitch("Mute Local Video", "On", "Off", retrieveVideoMuteStatus()));
            statistics.put("Reboot", "");
            controls.add(createButton("Reboot", "Reboot", "Rebooting...", REBOOT_GRACE_PERIOD_MS));

            extendedStatistics.setStatistics(statistics);
            extendedStatistics.setControllableProperties(controls);

            Integer conferenceId = retrieveActiveConferenceCallStatistics(statistics);
            boolean validConferenceId = conferenceId != null && conferenceId > -1;

            endpointStatistics.setInCall(validConferenceId);
            if (validConferenceId) {
                CallStats callStats = new CallStats();
                callStats.setCallId(String.valueOf(conferenceId));
                callStats.setProtocol(statistics.get("Active Conference#Protocol"));
                callStats.setRequestedCallRate(CALL_RATE);

                AudioChannelStats audioChannelStats = new AudioChannelStats();
                VideoChannelStats videoChannelStats = new VideoChannelStats();
                ContentChannelStats contentChannelStats = new ContentChannelStats();

                ArrayNode conferenceCallMediaStats = retrieveConferenceCallMediaStats(conferenceId);
                retrieveConferenceCallMediaStats(conferenceCallMediaStats, audioChannelStats, videoChannelStats, callStats);
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
     * Check if outcoming video feed is muted
     *
     * @return boolean outcoming video feed mute status
     * @throws Exception during http communication
     */
    private Boolean retrieveVideoMuteStatus() throws Exception {
        JsonNode response = doGet(buildHttpUrl(VIDEO_MUTE), JsonNode.class);
        return getJsonProperty(response, "result", Boolean.class);
    }

    /**
     * Set local video feed mute status
     *
     * @param status boolean indicating the target outcoming video feed state
     * @return boolean value, indicating the success of the mute status change.
     * @throws Exception during http communication
     */
    private Boolean updateVideoMuteStatus(boolean status) throws Exception {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("mute", status);
        JsonNode response = doPost(VIDEO_MUTE, request, JsonNode.class);
        return getJsonProperty(response, "success", Boolean.class);
    }

    /**
     * Get media statistics related to the current conference call
     *
     * @param conferenceCallMediaStats data retrieved from the VidoOS API
     * @param audioChannelStats        entity containing audio stats
     * @param videoChannelStats        entity containing video stats
     * @param callStats                entity containing call stats
     */
    private void retrieveConferenceCallMediaStats(ArrayNode conferenceCallMediaStats, AudioChannelStats audioChannelStats,
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

        Integer callRateRx = sumPacketData(videoChannelStats.getBitRateRx(), audioChannelStats.getBitRateRx());
        callStats.setCallRateRx((callRateRx == null) ? null : (callRateRx / 2));

        Integer callRateTx = (sumPacketData(videoChannelStats.getBitRateTx(), audioChannelStats.getBitRateTx()));
        callStats.setCallRateTx((callRateTx == null) ? null : (callRateTx / 2));
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
        ArrayNode response = doGet(buildHttpUrl(STATUS), ArrayNode.class);
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
        JsonNode response = doGet(buildHttpUrl(COLLABORATION), JsonNode.class);
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
        return doGet(buildHttpUrl(VOLUME), Integer.class);
    }

    /**
     * Update device's volume with a given value
     *
     * @param value target value for the device volume
     * @return boolean value indicating the success of the operation
     * @throws Exception during http communication
     */
    private boolean updateVolumeLevel(int value) throws Exception {
        return doPost(VOLUME, value).getStatusCode().is2xxSuccessful();
    }

    /**
     * Retrieve stats of currently active conference call
     *
     * @param statistics map to put values to
     * @return active conference id (#0), -1 if there are no active conferences available
     * @throws Exception during http communication
     */
    private Integer retrieveActiveConferenceCallStatistics(Map<String, String> statistics) throws Exception {
        ArrayNode conferenceCalls = listConferenceCalls();
        if (conferenceCalls.size() <= 0) {
            return -1;
        }
        JsonNode activeConference = conferenceCalls.get(0);
        JsonNode terminal = (activeConference.get("terminals")).get(0);
        JsonNode connections = (activeConference.get("connections")).get(0);
        statistics.put("Active Conference#Conference ID", getJsonProperty(activeConference, "id", String.class));

        if (terminal != null) {
            statistics.put("Active Conference#Conference address", getJsonProperty(terminal, "address", String.class));
            if (terminal.get(0) != null) {
                statistics.put("Active Conference#Conference system", getJsonProperty(terminal.get(0), "systemID", String.class));
            }
        }
        if (connections != null) {
            statistics.put("Active Conference#Conference type", getJsonProperty(connections, "callType", String.class));
            statistics.put("Active Conference#Conference info", getJsonProperty(connections, "callInfo", String.class));
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
        return doGet(buildHttpUrl(CONFERENCE), ArrayNode.class);
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
            return doGet(buildHttpUrl(String.format(MEDIASTATS, conferenceId)), ArrayNode.class);
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
        JsonNode response = doGet(buildHttpUrl(SHARED_MEDIASTATS), JsonNode.class);
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
     *
     * @throws Exception during http communication
     */
    private boolean reboot() throws Exception {
        ObjectNode rebootRequest = JsonNodeFactory.instance.objectNode();
        rebootRequest.put("action", "reboot");
        boolean success = doPost(REBOOT, rebootRequest).getStatusCode().is2xxSuccessful();
        if (success) {
            sessionId = null;
            internalDestroy();
        }
        return success;
    }

    /**
     * Retrieve device system information, including hardware info and lan status
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveSystemInfo(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(buildHttpUrl(SYSTEM), JsonNode.class);
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
        JsonNode response = doGet(buildHttpUrl(APPS), JsonNode.class);
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
        JsonNode response = doGet(buildHttpUrl(SESSIONS), JsonNode.class);
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
        ArrayNode response = doGet(buildHttpUrl(MICROPHONES), ArrayNode.class);
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
        String response = doGet(buildHttpUrl(CONTENT_STATUS), String.class);
        statistics.put("Cameras#Content Status", response);
    }

    /**
     * Retrieve device conferencing capabilities
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveConferencingCapabilities(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(buildHttpUrl(CONFERENCING_CAPABILITIES), JsonNode.class);
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
        JsonNode response = doGet(buildHttpUrl(AUDIO), JsonNode.class);
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
     * Return blank json object node otherwise
     *
     * @param json     to fetch property value from
     * @param property name of the property to retrieve
     * @param type     type to resolve property to, to avoid unsafe use of .asType calls for JsonNode instance.
     * @return JsonNode with an expected value or blank node otherwise
     * @throws UnsupportedOperationException if there's no matching case for the type parameter passed
     */
    @SuppressWarnings("unchecked")
    private <T> T getJsonProperty(JsonNode json, String property, Class<T> type) {
        JsonNode value = json.get(property);
        if (value == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(property + " property is not available.");
            }
            return null;
        }
        try {
            if (Class.forName(type.getName()).isAssignableFrom(String.class)) {
                return (T) value.asText();
            } else if (Class.forName(type.getName()).isAssignableFrom(Integer.class)) {
                return (T) Integer.valueOf(value.asInt());
            } else if (Class.forName(type.getName()).isAssignableFrom(Boolean.class)) {
                return (T) Boolean.valueOf(value.asBoolean());
            } else if (Class.forName(type.getName()).isAssignableFrom(Float.class)) {
                return (T) Float.valueOf(value.floatValue());
            } else if (Class.forName(type.getName()).isAssignableFrom(Long.class)) {
                return (T) Long.valueOf(value.longValue());
            } else if (Class.forName(type.getName()).isAssignableFrom(ArrayNode.class)) {
                return (T) value;
            }
        } catch (ClassNotFoundException cnfe) {
            logger.error("Class " + type.getName() + " cannot be found to extract property from the json structure.");
        }
        throw new UnsupportedOperationException();
    }

    /**
     * We need this because login is with https by default but we still have to trust all certificates before init,
     * the rest of requests are http
     *
     * @param url to insert into the URL_TEMPLATE string
     * @return url with http protocol and current host set
     */
    private String buildHttpUrl(String url) {
        return String.format(URL_TEMPLATE, "http", getHost(), url);
    }

    @Override
    protected void authenticate() throws Exception {
        // authenticate and get sessionId like "PSN0HppfZap7wtV9MgTeGKLZL+q8q+65Te6g/r61KLqC26+thY"
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
                case "Mute Microphones":
                    if (value.equals("0")) {
                        unmute();
                    } else {
                        mute();
                    }
                    updateLatestControlTimestamp();
                    break;
                case "Mute Local Video":
                    updateVideoMuteStatus(value.equals("1"));
                    break;
                case "Audio Volume":
                    updateVolumeLevel(Math.round(Float.parseFloat(value)));
                    updateLatestControlTimestamp();
                    break;
                case "Reboot":
                    reboot();
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

    /**
     * Interceptor for RestTemplate that injects
     * Cookie header and reacts to the communicator state - after reboot the certificates on the device get
     * refreshed that results with timeouts on https requests. In order to overcome this - communicator is being internally
     * destroyed on the successful reboot action. Hovewer, we cannot guarantee that any other resource does not issue
     * reboot or certificates do not get refreshed for other reasons, so we check for a specific error on
     * authorization event and reinitialize the communicator here.
     */
    class PolycomVideoOSInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            if (!isInitialized()) {
                try {
                    internalInit();
                } catch (Exception e) {
                    throw new IOException(String.format("Unable to request %s: device is not initialized: %s", request.getURI(), e.getMessage()));
                }
            }

            ClientHttpResponse response = execution.execute(request, body);
            if (response.getRawStatusCode() == 403) {
                try {
                    authenticate();
                    response = execution.execute(request, body);
                } catch (Exception e) {
                    logger.error("Authentication failed during interception: " + e.getMessage());
                    if (e.getMessage().startsWith("Cannot reach resource") && e.getMessage().contains(SESSION)) {
                        // In case it's been rebooted by some other resource - we need to react to that.
                        // Normally we expect that the authorization request timeouts, since previous one failed with
                        // a specific error code, so basically we do the same as before - authenticate + retry previous
                        // request but with the destroy action called first, since init will be done next when
                        // authentication is requested.
                        internalDestroy();
                        try {
                            authenticate();
                            response = execution.execute(request, body);
                        } catch (Exception ex) {
                            throw new IOException("Authorization failed after the communicator destruction: " + ex.getMessage());
                        }
                    }
                }
            }
            return response;
        }
    }

    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();

        if (restTemplate.getInterceptors() == null)
            restTemplate.setInterceptors(new ArrayList<>());

        if (!restTemplate.getInterceptors().contains(videoOSInterceptor))
            restTemplate.getInterceptors().add(videoOSInterceptor);

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
        return (System.currentTimeMillis() - latestControlTimestamp) < 5000;
    }
}
