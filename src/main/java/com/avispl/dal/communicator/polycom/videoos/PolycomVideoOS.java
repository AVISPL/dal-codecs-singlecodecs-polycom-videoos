/*
 * Copyright (c) 2015-2022 AVI-SPL Inc. All Rights Reserved.
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;

/**
 * PolycomVideoOS is a communicator class for X30/X50/G7500 devices.
 * The communicator is based on RestCommunicator class and is using VideoOS REST API to communicate with devices.
 * In order to have codec specific features available, CallController interface is used with dial(), hangup()
 * and callStatus() methods implemented.
 * sendMessage() is not implemented since there's no such functionality available in VideoOS REST API for now.
 * <p>
 * Polycom VideoOS REST API Reference Guide:
 * https://support.polycom.com/content/dam/polycom-support/products/telepresence-and-video/poly-studio-x/user/en/poly-video-restapi.pdf
 */
public class PolycomVideoOS extends RestCommunicator implements CallController, Monitorable, Controller {

    private ClientHttpRequestInterceptor videoOSInterceptor = new PolycomVideoOSInterceptor();

    /**
     * Exception type meant for inter-adapter communication.
     * Whenever it is not possible to retrieve a connection by the id provided - this exception indicates that the existing
     * connection should be used instead.
     * @since 1.0.2
     */
    static class UnknownDeviceConnection extends IllegalStateException {}

    /**
     * Data transfer unit for keeping the conference data - conferenceId, callId and startDate,
     * to further use in callId creation process
     * @since 1.0.2
     */
    static class CallConnectionData {
        private Integer conferenceId = -1;
        private Integer callId = -1;
        private Long startDate = -1L;

        /**
         * Retrieves {@code {@link #conferenceId}}
         *
         * @return value of {@link #conferenceId}
         */
        public Integer getConferenceId() {
            return conferenceId;
        }

        /**
         * Sets {@code conferenceId}
         *
         * @param conferenceId the {@code java.lang.Integer} field
         */
        public void setConferenceId(Integer conferenceId) {
            this.conferenceId = conferenceId;
        }

        /**
         * Retrieves {@code {@link #callId}}
         *
         * @return value of {@link #callId}
         */
        public Integer getCallId() {
            return callId;
        }

        /**
         * Sets {@code callId}
         *
         * @param callId the {@code java.lang.Integer} field
         */
        public void setConnectionId(Integer callId) {
            this.callId = callId;
        }

        /**
         * Retrieves {@code {@link #startDate}}
         *
         * @return value of {@link #startDate}
         */
        public Long getStartDate() {
            return startDate;
        }

        /**
         * Sets {@code startDate}
         *
         * @param startDate the {@code java.lang.Long} field
         */
        public void setStartDate(Long startDate) {
            this.startDate = startDate;
        }
    }

    /**
     * Interceptor for RestTemplate that injects
     * <p>
     * Currently, a reboot action changes previously accepted certificates, which would lead to
     * {@link ResourceNotReachableException} without a proper way to recover. Current workaround is to call
     * {@link #disconnect()} on reboot and on {@link ResourceNotReachableException} in the
     * {@link PolycomVideoOSInterceptor} in case if the device has been rebooted externally.
     */
    class PolycomVideoOSInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

            ClientHttpResponse response = execution.execute(request, body);
            if (response.getRawStatusCode() == 403 && !request.getURI().getPath().endsWith(SESSION)) {
                try {
                    authenticate();
                    response = execution.execute(request, body);
                } catch (ResourceNotReachableException e) {
                    // In case it's been rebooted by some other resource - we need to react to that.
                    // Normally we expect that the authorization request timeouts, since previous one failed with
                    // a specific error code, so basically we do the same as before - authenticate + retry previous
                    // request but with the destroy action called first, since init will be done next when
                    // authentication is requested.
                    try {
                        disconnect();
                    } catch (Exception ex) {
                        throw new IOException("Unable to recover the http connection during the request interception", ex);
                    }
                } catch (Exception e) {
                    logger.error("Authentication failed during the request interception", e);
                }
            }
            return response;
        }
    }

    private static final String SYSTEM_STATUS_GROUP_LABEL = "SystemStatus#";
    private static final String COLLABORATION_SESSION_STATE_LABEL = "Collaboration#SessionState";
    private static final String COLLABORATION_SESSION_ID_LABEL = "Collaboration#SessionId";
    private static final String ACTIVE_CONFERENCE_ID_LABEL = "ActiveConference#ConferenceId";
    private static final String ACTIVE_CONFERENCE_START_TIME_LABEL = "ActiveConference#ConferenceStartTime";
    private static final String ACTIVE_CONFERENCE_TERMINAL_ADDRESS_LABEL = "ActiveConference#Terminal%sAddress";
    private static final String ACTIVE_CONFERENCE_TERMINAL_SYSTEM_LABEL = "ActiveConference#Terminal%sSystem";
    private static final String ACTIVE_CONFERENCE_CONNECTION_TYPE_LABEL = "ActiveConference#Connection%sType";
    private static final String ACTIVE_CONFERENCE_CONNECTION_INFO_LABEL = "ActiveConference#Connection%sInfo";
    private static final String APPLICATIONS_VERSION_LABEL = "Applications#%sVersion";
    private static final String APPLICATIONS_LAST_UPDATED_LABEL = "Applications#%SLastUpdated";
    private static final String MICROPHONES_NAME_LABEL = "Microphones#Microphone%sName";
    private static final String MICROPHONES_STATUS_LABEL = "Microphones#Microphone%sState";
    private static final String MICROPHONES_TYPE_LABEL = "Microphones#Microphone%sType";
    private static final String MICROPHONES_HARDWARE_LABEL = "Microphones#Microphone%sHardwareVersion";
    private static final String MICROPHONES_SOFTWARE_LABEL = "Microphones#Microphone%sSoftwareVersion";
    private static final String MICROPHONES_MUTE_LABEL = "Microphones#Microphone%sMuted";
    private static final String CAMERAS_CONTENT_STATUS = "Cameras#ContentStatus";
    private static final String CONFERENCING_CAPABILITIES_BLAST_DIAL = "ConferencingCapabilities#BlastDial";
    private static final String CONFERENCING_CAPABILITIES_AUDIO_CALL = "ConferencingCapabilities#AudioCall";
    private static final String CONFERENCING_CAPABILITIES_VIDEO_CALL = "ConferencingCapabilities#VideoCall";
    private static final String AUDIO_MUTE_LOCKED_LABEL = "Audio#MuteLocked";
    private static final String AUDIO_MICROPHONES_CONNECTED = "Audio#MicrophonesConnected";
    private static final String ACTIVE_SESSIONS_USER_ID_LABEL = "ActiveSessions#Session%sUserId";
    private static final String ACTIVE_SESSIONS_ROLE_LABEL = "ActiveSessions#Session%sRole";
    private static final String ACTIVE_SESSIONS_LOCATION_LABEL = "ActiveSessions#Session%sLocation";
    private static final String ACTIVE_SESSIONS_CLIENT_TYPE_LABEL = "ActiveSessions#Session%sClientType";
    private static final String ACTIVE_SESSIONS_STATUS_LABEL = "ActiveSessions#Session%sStatus";
    private static final String SYSTEM_NAME_LABEL = "System#System Name";
    private static final String SYSTEM_SIP_USERNAME_LABEL = "System#SIPUsername";
    private static final String SYSTEM_H323_EXTENSION_LABEL = "System#H323Extension";
    private static final String SYSTEM_H323_NAME_LABEL = "System#H323Name";

    private static final String SYSTEM_SERIAL_NUMBER_LABEL = "System#Serial Number";
    private static final String SYSTEM_SOFTWARE_VERSION_LABEL = "System#Software Version";
    private static final String SYSTEM_STATE_LABEL = "System#System State";
    private static final String SYSTEM_BUILD_LABEL = "System#System Build";
    private static final String SYSTEM_REBOOT_NEEDED_LABEL = "System#System Reboot Needed";
    private static final String SYSTEM_DEVICE_MODEL_LABEL = "System#Device Model";
    private static final String SYSTEM_HARDWARE_VERSION_LABEL = "System#Device Hardware Version";
    private static final String SYSTEM_UPTIME_LABEL = "System#System Uptime";
    private static final String LAN_STATUS_DUPLEX_LABEL = "Lan Status#Duplex";
    private static final String LAN_STATUS_SPEED_LABEL = "Lan Status#Speed Mbps";
    private static final String LAN_STATUS_STATE_LABEL = "Lan Status#State";

    private static final String DEVICE_MODE_LABEL = "System#DeviceMode";
    private static final String SIGNAGE_MODE_LABEL = "System#SignageMode";

    private static final String CONTROL_MUTE_VIDEO = "MuteLocalVideo";
    private static final String CONTROL_MUTE_MICROPHONES = "MuteMicrophones";
    private static final String CONTROL_AUDIO_VOLUME = "AudioVolume";
    private static final String CONTROL_REBOOT = "Reboot";

    private static final String REST_KEY_SIP_USERNAME = "comm.nics.sipnic.sipusername";
    private static final String REST_KEY_H323_NAME = "comm.nics.h323nic.h323name";
    private static final String REST_KEY_H323_EXTENSION = "comm.nics.h323nic.h323extension";

    private static final String CALL_ID_TEMPLATE = "%s:%s:%s:%s";
    private static final String PERIPHERALS_TEMPLATE = "Peripherals[%s:%s:%s]#";

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
    private static final String CONFIG = "rest/config";
    private static final String REBOOT = "rest/system/reboot";
    private static final String COLLABORATION = "rest/collaboration";
    private static final String MICROPHONES = "rest/audio/microphones";
    private static final String APPS = "rest/system/apps";
    private static final String SESSIONS = "rest/current/session/sessions";
    private static final String SIP_SERVERS = "rest/system/sipservers";
    private static final String H323_SERVERS = "rest/system/h323gatekeepers";
    private static final String DEVICE_MODE = "rest/system/mode/device";
    private static final String SIGNAGE_MODE = "rest/system/mode/signage";
    private static final String PERIPHERAL_DEVICES = "rest/current/devicemanagement/devices";

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

    private Boolean simulatedDeviceMode = false;

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

    /**
     * Retrieves {@link #simulatedDeviceMode}
     *
     * @return value of {@link #simulatedDeviceMode}
     */
    public Boolean getSimulatedDeviceMode() {
        return simulatedDeviceMode;
    }

    /**
     * Sets {@link #simulatedDeviceMode} value
     *
     * @param simulatedDeviceMode new value of {@link #simulatedDeviceMode}
     */
    public void setSimulatedDeviceMode(Boolean simulatedDeviceMode) {
        this.simulatedDeviceMode = simulatedDeviceMode;
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
     * <p>
     * If the call is in progress and another participant is addressed with {@link #CONFERENCES} POST call -
     * VideoOS Rest API will add the participant as another connection for the existing conference call, without
     * creating an additional conference call, so it is commonly expected that there's a single conference at most.
     * <p>
     * After sending dial command we fetch for the status of the new conference call using the device connection
     * url that's being returned by the VideoOS API.
     *
     * @throws RuntimeException If we can't verify (via matching of the dialString (of the DialDevice) to the remoteAddress
     *                          (of the call stats returned from the device)
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
                Integer conferenceId = getJsonProperty(meetingInfo, "parentConfId", Integer.class);
                if (null != conferenceId) {
                    String remoteAddress = getJsonProperty(meetingInfo, "address", String.class);
                    if (!StringUtils.isNullOrEmpty(remoteAddress) && remoteAddress.trim().equals(dialDevice.getDialString().trim())) {
                        return buildCallId (conferenceId, getJsonProperty(meetingInfo, "id", Integer.class),
                                getJsonProperty(meetingInfo, "startTime", Long.class), retrieveDeviceDialString());
                    }
                }
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException(String.format("An error occurred during dialing out to %s with protocol %s.",
                dialDevice.getDialString(), dialDevice.getProtocol()));
    }

    /**
     * Generate call id which includes conferenceId, connectionId, timestamp and device dial string.
     *
     * @param activeConference json retrieved from the device API
     * @param connectionId retrieved from Symphony (previously generated by the device)
     * @param deviceDialString retrieved from the device's settings
     *
     * @return {@link String} of format
     * conferenceId:callId:timestamp:sipURI or
     * conferenceId:callId:timestamp::H323Extension (based on the call protocol), for example
     * <p>
     * 0:2:1642153838000:nh-studiox30@nh.vnoc1.com
     * 0:1:1642153837000:7771991048
     */
    private String generateCallId (JsonNode activeConference, Integer connectionId, String deviceDialString) {
        ArrayNode connections = (ArrayNode) activeConference.get("connections");
        JsonNode deviceConnection = null;
        if (connectionId != null) {
            for (JsonNode node : connections) {
                if (node.has("id") && node.get("id").asInt() == connectionId) {
                    deviceConnection = node;
                }
            }
        } else {
            deviceConnection = connections.get(0);
        }
        if (deviceConnection == null) {
            throw new UnknownDeviceConnection();
        }
        return buildCallId(getJsonProperty(activeConference, "id", Integer.class), getJsonProperty(connections.get(0), "id", Integer.class),
                getJsonProperty(deviceConnection, "startTime", Long.class), deviceDialString);
    }

    /**
     * Build callId string using {@link #CALL_ID_TEMPLATE} and parameters provided
     * Since callId is supposed to be unique and cisco devices provide ids as simple integers (0, 1, 2 etc)
     * we need to provide higher uniqueness to avoid collisions with other devices' ids. Thus, dialString and
     * callStartTime are used to form a callId
     *
     * @param conferenceId id of a conference
     * @param callId id of a connection within conference
     * @param callStartTime start timestamp of a {@code callId} connection
     * @param dialString current device dialString
     * @since 1.0.2
     */
    private String buildCallId (Integer conferenceId, Integer callId, Long callStartTime, String dialString) {
        return String.format(CALL_ID_TEMPLATE,
                conferenceId == null ? 0 : conferenceId, callId == null ? 0 : callId,
                callStartTime == null ? 0 : callStartTime, dialString == null ? "" : dialString);
    }

    /**
     * Retrieve dialString of the device, from the device statistics. Alternatively, use systemName if
     * no address is available (e.g NH-StudioX30-53429a)
     *
     * @return {@link String} value of the device dialString (SIP or H323 extension)
     * @since 1.0.2
     * */
    private String retrieveDeviceDialString() throws Exception {
        Map<String, String> statistics;
        if (localStatistics == null) {
            statistics = new HashMap<>();
            retrieveCommunicationProtocolsInfo(statistics);
        } else {
            statistics = localStatistics.getStatistics();
        }

        String localAddress = statistics.get(SYSTEM_NAME_LABEL);
        if (statistics.containsKey(SYSTEM_SIP_USERNAME_LABEL)) {
            localAddress = statistics.get(SYSTEM_SIP_USERNAME_LABEL);
        } else if (statistics.containsKey(SYSTEM_H323_EXTENSION_LABEL)) {
            localAddress = statistics.get(SYSTEM_H323_EXTENSION_LABEL);
        }

        return localAddress;
    }
    /**
     * {@inheritDoc}
     * <p>
     * Locking is necessary because statistics is still being collected there and if the device is in the call
     * and collecting some information available while in the call only - it'll end up with a 404 error.
     * If there's a conferenceId available - only one conference is removed, otherwise - the method
     * iterates through all of the available conferences and removes them.
     * <p>
     * {@link PolycomVideoOS} according to the VideoOS documentation for DELETE: /conferences/{conferenceId} method:
     * This API hangs up and disconnects the specified conference call.
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
     * <p>
     * ConferenceId of format
     * conferenceId:callId:timestamp:sipURI or
     * conferenceId:callId:timestamp::H323Extension (based on the call protocol), for example
     * <p>
     * 0:2:1642153838000:nh-studiox30@nh.vnoc1.com
     * 0:1:1642153837000:7771991048
     */
    @Override
    public CallStatus retrieveCallStatus(String callId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Retrieving call status with string: " + callId);
        }
        controlOperationsLock.lock();
        try {
            String deviceDialString = retrieveDeviceDialString();
            Pattern pattern = Pattern.compile("(\\d+):(\\d+):.+$", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(callId);
            if (!StringUtils.isNullOrEmpty(callId) && matcher.find()) {
                Integer connectionId = Integer.parseInt(matcher.group(2));
                try {
                    JsonNode response = doGet(String.format(CONFERENCE, matcher.group(1)), JsonNode.class);
                    if (response == null) {
                        return generateCallStatus(callId, CallStatus.CallStatusState.Disconnected);
                    }
                    Boolean conferenceIsActive = getJsonProperty(response, "isActive", Boolean.class);
                    if (conferenceIsActive != null) {
                        // Generating callId by given conference response and target connectionId
                        return generateCallStatus(generateCallId(response, connectionId, deviceDialString),
                                conferenceIsActive ? CallStatus.CallStatusState.Connected : CallStatus.CallStatusState.Disconnected);
                    }
                } catch (CommandFailureException cfe) {
                    if (cfe.getStatusCode() == 404) {
                        return generateCallStatus(callId, CallStatus.CallStatusState.Disconnected);
                    }
                } catch (UnknownDeviceConnection udc) {
                    // if connection id provided was not found - logging a warning and moving forward to retrieve the list of active conference calls
                    logger.warn(String.format("Unable to locate active connection with id %s. Using available connection instead.", connectionId), udc);
                }
            }
            ArrayNode conferenceCalls = listConferenceCalls();
            if (conferenceCalls != null && conferenceCalls.size() > 0) {
                // using null as the connectionId is unknown at this point
                return generateCallStatus(generateCallId(conferenceCalls.get(0), null, deviceDialString), CallStatus.CallStatusState.Connected);
            }
        } finally {
            controlOperationsLock.unlock();
        }
        return generateCallStatus(callId, CallStatus.CallStatusState.Disconnected);
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
     * <p>
     * Since we need to react on the mute status change to have {@link #localStatistics} data synchronized with
     * the actual values that are sent here - we need to set new status for the locally stored control properties.
     * <p>
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
     * <p>
     * Since we need to react to the mute status change to have {@link #localStatistics} data synchronized with
     * the actual values that are sent here - we need to set new status for the locally stored control properties.
     * This is why response code is being checked for the operation.
     * <p>
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
     * <p>
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
     * <p>
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
                endpointStatistics.setRegistrationStatus(localEndpointStatistics.getRegistrationStatus());

                return Arrays.asList(extendedStatistics, endpointStatistics);
            }

            Map<String, String> statistics = new HashMap<>();
            List<AdvancedControllableProperty> controls = new ArrayList<>();

            retrieveSystemStatus(statistics);
            retrieveSystemInfo(statistics);
            retrieveCommunicationProtocolsInfo(statistics);
            retrieveApplications(statistics);
            retrieveSessions(statistics);
            retrieveMicrophonesStatistics(statistics);
            retrieveContentStatus(statistics);
            retrieveConferencingCapabilities(statistics);
            retrieveAudioStatus(statistics);
            retrieveCollaborationStatus(statistics);
            retrieveSoftwareModeStatus(statistics, controls);
            retrievePeripheralsInformation(statistics);

            Integer volumeLevel = retrieveVolumeLevel();
            if (volumeLevel != null) {
                statistics.put(CONTROL_AUDIO_VOLUME, "");
                controls.add(createSlider(CONTROL_AUDIO_VOLUME, 0.0f, 100.0f, Float.valueOf(volumeLevel)));
            }
            Boolean videoMuteStatus = retrieveVideoMuteStatus();
            if (videoMuteStatus != null) {
                statistics.put(CONTROL_MUTE_VIDEO, "");
                controls.add(createSwitch(CONTROL_MUTE_VIDEO, retrieveVideoMuteStatus() ? 1 : 0));
            }
            statistics.put(CONTROL_MUTE_MICROPHONES, "");
            controls.add(createSwitch(CONTROL_MUTE_MICROPHONES, Objects.equals(retrieveMuteStatus(), MuteStatus.Muted) ? 1 : 0));
            statistics.put(CONTROL_REBOOT, "");
            controls.add(createButton(CONTROL_REBOOT, CONTROL_REBOOT, "Rebooting...", REBOOT_GRACE_PERIOD_MS));

            extendedStatistics.setStatistics(statistics);
            extendedStatistics.setControllableProperties(controls);

            CallConnectionData connectionData = retrieveActiveConferenceCallStatistics(statistics);
            Integer conferenceId = connectionData.getConferenceId();
            boolean validConferenceId = conferenceId != null && conferenceId > -1;

            endpointStatistics.setInCall(validConferenceId);
            endpointStatistics.setRegistrationStatus(retrieveRegistrationStatus());
            if (validConferenceId) {
                CallStats callStats = new CallStats();
                callStats.setProtocol(statistics.get("ActiveConference#Connection1Type"));
                callStats.setRequestedCallRate(DEFAULT_CALL_RATE);

                AudioChannelStats audioChannelStats = new AudioChannelStats();
                VideoChannelStats videoChannelStats = new VideoChannelStats();
                ContentChannelStats contentChannelStats = new ContentChannelStats();

                ArrayNode conferenceCallMediaStats = retrieveConferenceCallMediaStats(conferenceId);
                processConferenceCallMediaStats(conferenceCallMediaStats, audioChannelStats, videoChannelStats, callStats);
                retrieveSharedMediaStats(contentChannelStats);

                String dialString = retrieveDeviceDialString();

                callStats.setCallId(buildCallId(conferenceId, connectionData.getCallId(), connectionData.getStartDate(), dialString));
                callStats.setRemoteAddress(dialString);

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

    @Override
    protected <Response> Response doGet(String uri, Class<Response> responseClass) throws Exception {
        try {
            return super.doGet(uri, responseClass);
        } catch (CommandFailureException cfe) {
            logger.error("Exception while executing command: " + uri, cfe);
            return null;
        }
    }

    /**
     * Check whether the device is in the device mode now
     *
     * @return Boolean value, true -> device mode, otherwise -> false, Null if 404 response code
     * @throws Exception if a communication error occurs
     * */
    private Boolean retrieveDeviceMode() throws Exception {
        JsonNode response = doGet(DEVICE_MODE, JsonNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve device mode status.");
            }
            return null;
        }
        return getJsonProperty(response, "result", Boolean.class);
    }

    /**
     * Add peripherals information, the {@link #PERIPHERAL_DEVICES} endpoint is not listed as a part of
     * VideoOS REST API, so future implementation may change. For this reason, the exception is logged but does not
     * stop any further statistics collection.
     *
     * @param statistics map to collect statistics to
     * @since 1.0.4
     * */
    private void retrievePeripheralsInformation(Map<String, String> statistics) {
        ArrayNode response;
        try {
            response = doPost(PERIPHERAL_DEVICES, null, ArrayNode.class);
        } catch (Exception e) {
            logger.error("Unable to retrieve peripheral devices information.", e);
            return;
        }
        if (response == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve peripheral devices information.");
            }
            return;
        }
        response.forEach(device -> {
            String systemName = device.at("/systemName").asText();
            String uid = device.at("/uid").asText();
            if (StringUtils.isNullOrEmpty(uid) || Objects.equals(systemName, statistics.get(SYSTEM_NAME_LABEL))) {
                // Skip if the device is the very same system that the codec is
                return;
            }
            String connectionType = device.at("/connectionType").asText().toUpperCase();
            String deviceCategory = device.at("/deviceCategory").asText().toUpperCase();
            String deviceState = device.at("/deviceState").asText().toUpperCase();
            String deviceType = device.at("/deviceType").asText().toUpperCase();
            String ip = device.at("/ip").asText();
            String macAddress = device.at("/macAddress").asText();
            String networkInterface = device.at("/networkInterface").asText();
            String productName = device.at("/productName").asText();
            String serialNumber = device.at("/serialNumber").asText();
            String softwareVersion = device.at("/softwareVersion").asText();

            String groupName = String.format(PERIPHERALS_TEMPLATE, deviceCategory, deviceType, connectionType);

            processPropertyIfExists(statistics, groupName + "ConnectionType", connectionType);
            processPropertyIfExists(statistics, groupName + "DeviceCategory", deviceCategory);
            processPropertyIfExists(statistics, groupName + "DeviceState", deviceState);
            processPropertyIfExists(statistics, groupName + "DeviceType", deviceType);
            processPropertyIfExists(statistics, groupName + "IPAddress", ip);
            processPropertyIfExists(statistics, groupName + "MACAddress", macAddress);
            processPropertyIfExists(statistics, groupName + "NetworkInterface", networkInterface);
            processPropertyIfExists(statistics, groupName + "ProductName", productName);
            processPropertyIfExists(statistics, groupName + "SerialNumber", serialNumber);
            processPropertyIfExists(statistics, groupName + "SoftwareVersion", softwareVersion);
            processPropertyIfExists(statistics, groupName + "SystemName", systemName);
            processPropertyIfExists(statistics, groupName + "UID", uid);
        });
    }

    /**
     * Adds colon separated ordinal at the end of the property name, if the property already exists.
     * If there is a property duplicate - add :1 in the end of the existing one, and :2 at the end of the new one.
     * If :2+ properties already exist - increase the ordinal and add it to the property.
     * ex:
     * Duplicated properties of
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]#MACAddress -> AA:BB:CC:DD:EE:FF
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]#MACAddress -> FF:FF:FF:FF:FF:FF
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]#MACAddress -> AA:AA:AA:AA:AA:AA
     * will be saved as
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]:1#MACAddress -> AA:BB:CC:DD:EE:FF
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]:2#MACAddress -> FF:FF:FF:FF:FF:FF
     * Peripherals[REMOTE:UNKNOWN:UNKNOWN]:3#MACAddress -> AA:AA:AA:AA:AA:AA
     *
     * @param statistics to add property to
     * @param key property name to add
     * @param value property value
     * @since 1.0.4
     * */
    private void processPropertyIfExists(Map<String, String> statistics, String key, String value) {
        if (statistics.containsKey(key)) {
            String[] groupedPropertyEntries = key.split("#");
            String[] keyEntries = groupedPropertyEntries[0].split("]:");
            if (keyEntries.length > 1) {
                // If there's an ordinal in the property name - increase it by 1 and try to pass it again to this method,
                // just in case there are other properties with the same ordinal already.
                int propertyOrdinal = Integer.parseInt(keyEntries[1]) + 1;
                processPropertyIfExists(statistics, keyEntries[0] + "]:" + propertyOrdinal + "#" + groupedPropertyEntries[1], value);
            } else {
                String currentPropertyName = statistics.get(key);
                // remove property with duplicate, but without ordinal, and replace it with a numbered property
                // duplicate is passed to this method the same way, but with the number +1, so if the numbers are
                // repeated - they end up being ordered correctly.
                statistics.remove(key);
                statistics.put(groupedPropertyEntries[0] + ":1#" + groupedPropertyEntries[1], currentPropertyName);
                statistics.put(groupedPropertyEntries[0] + ":2#" + groupedPropertyEntries[1], value);
            }
            return;
        }
        boolean hasProperty = statistics.keySet().stream().filter(propertyName -> propertyName.startsWith("Peripherals")).anyMatch(propertyName -> {
            // Check if there's an ordinal property duplicate, of the property that doesn't have ordinal yet.
            // This way, an ordinal will be assigned to the property, please see javadoc for details.
            String existingPropertyName = propertyName.replaceAll(":\\d+#", "#");
            return Objects.equals(existingPropertyName, key);
        });
        if (hasProperty) {
            processPropertyIfExists(statistics, key.replaceAll("#", ":1#"), value);
            return;
        }
        statistics.put(key, value);
    }

    /**
     * Check whether the device is in the signage mode now
     *
     * @return Boolean value, true -> signage mode, otherwise -> false. Null if 404 response code
     * @throws Exception if a communication error occurs
     * */
    private Boolean retrieveSignageMode() throws Exception {
        JsonNode response = doGet(SIGNAGE_MODE, JsonNode.class);
        if (response == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve signage mode status.");
            }
            return null;
        }
        return getJsonProperty(response, "result", Boolean.class);
    }

    /**
     * Switch signage mode based on the mode parameter
     *
     * @return Boolean value, true -> signage mode, otherwise -> false
     * @param mode defines, whether the command should switch codec into signage mode on or out of it. True = on, false = off
     * @throws Exception if a communication error occurs
     * */
    private void switchSignageMode(boolean mode) throws Exception {
        if (mode) {
            doPost(SIGNAGE_MODE, null);
        } else {
            doDelete(SIGNAGE_MODE);
        }
    }

    /**
     * Switch device mode based on the mode parameter
     *
     * @return Boolean value, true -> device mode, otherwise -> false
     * @param mode defines, whether the command should switch codec into device mode or out of it. True = on, false = off
     * @throws Exception if a communication error occurs
     * */
    private void switchDeviceMode(boolean mode) throws Exception {
        if (mode) {
            doPost(DEVICE_MODE, null);
        } else {
            doDelete(DEVICE_MODE);
        }
    }

    /**
     * Updates the local value of a given controllable property.
     *
     * @param controlName name of a controllable property stored in {@link #localStatistics} variable
     * @param value       target value of a controllable property
     */
    private void updateLocalControllablePropertyState(String controlName, String value) {
        if (null != localStatistics) {
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty ->
                    advancedControllableProperty.getName().equals(controlName)).findFirst()
                    .ifPresent(advancedControllableProperty -> advancedControllableProperty.setValue(value));
        }
    }

    /**
     * Generate CallStatus instance based on callId and callStatusState parameters
     *
     * @param callId          id of the conference
     * @param callStatusState state of the call to use
     * @return {@link CallStatus} instance, indicating the requested status of the call
     */
    private CallStatus generateCallStatus(String callId, CallStatus.CallStatusState callStatusState) {
        CallStatus callStatus = new CallStatus();
        callStatus.setCallId(callId);
        callStatus.setCallStatusState(callStatusState);
        return callStatus;
    }

    private RegistrationStatus retrieveRegistrationStatus() throws Exception {
        RegistrationStatus registrationStatus = null;
        Boolean deviceMode = retrieveDeviceMode();
        if (deviceMode != null && deviceMode) {
            if (localEndpointStatistics != null) {
                registrationStatus = localEndpointStatistics.getRegistrationStatus();
            }
            if (logger.isDebugEnabled()) {
                logger.debug("The codec is in the device mode. Skipping registration status retrieval, falling back to the cached values");
            }
            if (registrationStatus == null) {
                registrationStatus = new RegistrationStatus();
            }
            registrationStatus.setSipRegistered(true);
            registrationStatus.setH323Registered(true);
            return registrationStatus;
        }
        registrationStatus = new RegistrationStatus();
        JsonNode sipServers = null;
        JsonNode h323Servers = null;

        JsonNode sipServersResponse = doGet(SIP_SERVERS, JsonNode.class);
        if (sipServersResponse != null && !sipServersResponse.isNull()) {
            sipServers = sipServersResponse.get(0);
        }
        JsonNode h323ServersResponse = doGet(H323_SERVERS, JsonNode.class);
        if (h323ServersResponse != null && !h323ServersResponse.isNull()) {
            h323Servers = h323ServersResponse.get(0);
        }
        registrationStatus.setSipRegistered(false);
        registrationStatus.setH323Registered(false);

        if (sipServers != null) {
            JsonNode sipServerState = sipServers.get("state");
            JsonNode sipServerIP = sipServers.get("address");
            if (sipServerState != null) {
                registrationStatus.setSipRegistered(sipServerState.asText().equalsIgnoreCase("up"));
            }
            if (sipServerIP != null) {
                registrationStatus.setSipRegistrar(sipServerIP.asText());
            }
        }
        if (h323Servers != null) {
            JsonNode h323ServerState = h323Servers.get("state");
            JsonNode h323ServerIP = h323Servers.get("address");
            if (h323ServerState != null) {
                registrationStatus.setH323Registered(h323ServerState.asText().equalsIgnoreCase("up"));
            }
            if (h323ServerIP != null) {
                registrationStatus.setH323Gatekeeper(h323ServerIP.asText());
            }
        }

        return registrationStatus;
    }

    /**
     * Check if outcoming video feed is muted
     *
     * @return boolean outcoming video feed mute status
     * @throws Exception during http communication
     */
    private Boolean retrieveVideoMuteStatus() throws Exception {
        JsonNode response = doGet(VIDEO_MUTE, JsonNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve video mute status.");
            }
            return null;
        }
        return getJsonProperty(response, "result", Boolean.class);
    }

    /**
     * Set local video feed mute status
     * When operation is succeeded - we need to update {@link #localStatistics} with the new state of the
     * controllable property.
     * When operation has failed - RuntimeException is thrown, containing the reason of an unsuccessful operation, if
     * available, according to the {@link #VIDEO_MUTE} response body model:
     * {
     * "success": boolean,
     * "reason": "string"
     * }
     *
     * @param status boolean indicating the target outcoming video feed state
     * @throws Exception if any error has occurred
     */
    private void updateVideoMuteStatus(boolean status) throws Exception {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("mute", status);
        JsonNode response = doPost(VIDEO_MUTE, request, JsonNode.class);

        Boolean success = getJsonProperty(response, "success", Boolean.class);
        if (Boolean.TRUE.equals(success)) {
            updateLocalControllablePropertyState(CONTROL_MUTE_VIDEO, status ? "1" : "0");
        } else {
            throw new RuntimeException("Unable to update local video mute status: " +
                    getJsonProperty(response, "reason", String.class));
        }
    }

    /**
     * Get media statistics related to the current conference call
     * <p>
     * Since VideoOS API does not provide any specific values for totalPacketLoss/percentPacketLoss/callRate - these
     * parameters are calculated based on audio and video channel rate/packetLoss information. VideoOS audio/video
     * channel rates are reported is in kbps (kilobits per second) by default,
     * which matches Symphony {@link ChannelStats} model
     * <p>
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
     * Retrieve software mode status details with switch controls
     *
     * @param statistics map to set data to
     * @param controllableProperties list of controls
     * @throws Exception during http communication, except 403
     */
    private void retrieveSoftwareModeStatus(Map<String, String> statistics, List<AdvancedControllableProperty> controllableProperties) throws Exception {
        Boolean deviceMode = retrieveDeviceMode();
        if (deviceMode != null) {
            statistics.put(DEVICE_MODE_LABEL, String.valueOf(deviceMode));
            controllableProperties.add(createSwitch(DEVICE_MODE_LABEL, deviceMode ? 1 : 0));
        }

        Boolean signageMode = retrieveSignageMode();
        if (signageMode != null) {
            statistics.put(SIGNAGE_MODE_LABEL, String.valueOf(signageMode));
            controllableProperties.add(createSwitch(SIGNAGE_MODE_LABEL, signageMode ? 1 : 0));
        }
    }

    /**
     * Retrieve basic system stats - auto answering feature, cameras/mics basic info etc.
     *
     * @param statistics map to set data to
     * @throws Exception during http communication, except 403
     */
    private void retrieveSystemStatus(Map<String, String> statistics) throws Exception {
        ArrayNode response = doGet(STATUS, ArrayNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve device system status.");
            }
            return;
        }
        response.iterator().forEachRemaining(jsonNode -> {
            String langtag = getJsonProperty(jsonNode, "langtag", String.class);
            ArrayNode stateList = getJsonProperty(jsonNode, "stateList", ArrayNode.class);

            if (langtag != null && stateList != null && !stateList.isEmpty()) {
                statistics.put(SYSTEM_STATUS_GROUP_LABEL + normalizePropertyLabel(
                        langtag.replaceAll("_", " ")),
                        stateList.get(0).asText().replaceAll("_", " ").toUpperCase());
            }
        });
    }

    /**
     * We need to normalize labels like "Word word word word" to "WordWordWordWord"
     * i.e pascal case. At some specific situations, properties names are taken from the device,
     * rather than statically defined in a device adapter.
     *
     * @param string given label
     * @return normalized {@link String}
     */
    private String normalizePropertyLabel(String string) {
        String[] arr = string.toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < arr.length; i++) {
            // There's a lot of parameters that use this normalizer, but some parameters may have
            // these substrings, so in order to have them prettified properly on frontend -
            // tech acronyms are all capitals (so SIP won't become Sip, P2P won't become P2p etc.)
            // the set of such substrings is quite limited, given the context and it is only done for
            // readability purposes
            String current = arr[i];
            if (current.equals("sip") || current.equals("h323") || current.equals("p2p")) {
                sb.append(current.toUpperCase());
            } else {
                sb.append(Character.toUpperCase(current.charAt(0)))
                        .append(current.substring(1));
            }
        }
        return sb.toString().trim();
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptime value, with a decimal point
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(String uptime) {
        long uptimeSeconds = Math.round(Float.parseFloat(uptime));
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }

    /**
     * Retrieve the current state of the collaboration session
     *
     * @return Integer value of the current device volume level
     * @throws Exception during http communication
     */
    private void retrieveCollaborationStatus(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(COLLABORATION, JsonNode.class);
        if (response != null && !response.isNull()) {
            String sessionState = getJsonProperty(response, "state", String.class);
            statistics.put(COLLABORATION_SESSION_STATE_LABEL, sessionState);
            if ("ACTIVE".equals(sessionState)) {
                statistics.put(COLLABORATION_SESSION_ID_LABEL, getJsonProperty(response, "id", String.class));
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
     * @throws Exception             if any other error has occurred
     */
    private CallConnectionData retrieveActiveConferenceCallStatistics(Map<String, String> statistics) throws Exception {
        CallConnectionData callConnectionData = new CallConnectionData();
        ArrayNode conferenceCalls = listConferenceCalls();
        int conferenceCallsNumber = conferenceCalls.size();

        if (conferenceCallsNumber <= 0) {
            return callConnectionData;
        }
        if (conferenceCallsNumber > 1) {
            throw new IllegalStateException(String.format("%s conference calls are in progress, 1 expected. Unable to proceed.",
                    conferenceCallsNumber));
        }

        JsonNode activeConference = conferenceCalls.get(0);
        ArrayNode terminals = (ArrayNode) activeConference.get("terminals");
        ArrayNode connections = (ArrayNode) activeConference.get("connections");

        statistics.put(ACTIVE_CONFERENCE_ID_LABEL, getJsonProperty(activeConference, "id", String.class));
        Long conferenceStartTimestamp = getJsonProperty(activeConference, "startTime", Long.class);
        if (null != conferenceStartTimestamp) {
            statistics.put(ACTIVE_CONFERENCE_START_TIME_LABEL, String.valueOf(new Date(conferenceStartTimestamp)));
        }

        // Adding i+1 instead of i so terminals and connections are listed starting with 1, not 0
        if (terminals != null) {
            for (int i = 0; i < terminals.size(); i++) {
                JsonNode terminal = terminals.get(i);
                int terminalNumber = i + 1;
                statistics.put(String.format(ACTIVE_CONFERENCE_TERMINAL_ADDRESS_LABEL, terminalNumber), getJsonProperty(terminal, "address", String.class));
                statistics.put(String.format(ACTIVE_CONFERENCE_TERMINAL_SYSTEM_LABEL, terminalNumber), getJsonProperty(terminal, "systemID", String.class));
            }
        }
        if (connections != null) {
            for (int i = 0; i < connections.size(); i++) {
                JsonNode connection = connections.get(i);
                int connectionNumber = i + 1;
                statistics.put(String.format(ACTIVE_CONFERENCE_CONNECTION_TYPE_LABEL, connectionNumber), getJsonProperty(connection, "callType", String.class));
                statistics.put(String.format(ACTIVE_CONFERENCE_CONNECTION_INFO_LABEL, connectionNumber), getJsonProperty(connection, "callInfo", String.class));
            }
        }

        callConnectionData.setConferenceId(getJsonProperty(activeConference, "id", Integer.class));
        callConnectionData.setConnectionId(getJsonProperty(connections.get(0), "id", Integer.class));
        callConnectionData.setStartDate(getJsonProperty(connections.get(0), "startTime", Long.class));
        return callConnectionData;
    }

    /**
     * Retrieve list of all current conference calls
     *
     * @return ArrayNode containing the list of conference calls
     * @throws Exception during http communication
     */
    private ArrayNode listConferenceCalls() throws Exception {
        ArrayNode response = doGet(CONFERENCES, ArrayNode.class);
        if (response != null && !response.isNull()) {
            return response;
        }
        return JsonNodeFactory.instance.arrayNode();
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
        ArrayNode response = doGet(String.format(MEDIASTATS, conferenceId), ArrayNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Conference " + conferenceId + " is not available anymore. Skipping media stats retrieval.");
            }
            return JsonNodeFactory.instance.arrayNode();
        }
        return response;
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
     *
     * @param contentChannelStats to save shared media stats to
     * @throws Exception during http communication (shared content stats retrieval)
     */
    private void retrieveSharedMediaStats(ContentChannelStats contentChannelStats) throws Exception {
        JsonNode response = doGet(SHARED_MEDIASTATS, JsonNode.class);
        if (response != null && !response.isNull()) {
            ArrayNode vars = (ArrayNode) response.get("vars");
            if (vars != null && vars.size() > 0) {
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
     * <p>
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

        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve system information.");
            }
            return;
        }
        addStatisticsProperty(statistics, SYSTEM_SERIAL_NUMBER_LABEL, response.get("serialNumber"));
        addStatisticsProperty(statistics, SYSTEM_SOFTWARE_VERSION_LABEL, response.get("softwareVersion"));
        addStatisticsProperty(statistics, SYSTEM_STATE_LABEL, response.get("state"));
        addStatisticsProperty(statistics, SYSTEM_NAME_LABEL, response.get("systemName"));
        addStatisticsProperty(statistics, SYSTEM_BUILD_LABEL, response.get("build"));
        addStatisticsProperty(statistics, SYSTEM_REBOOT_NEEDED_LABEL, response.get("rebootNeeded"));
        addStatisticsProperty(statistics, SYSTEM_DEVICE_MODEL_LABEL, response.get("model"));
        addStatisticsProperty(statistics, SYSTEM_HARDWARE_VERSION_LABEL, response.get("hardwareVersion"));
        statistics.put(SYSTEM_UPTIME_LABEL, normalizeUptime(response.get("uptime").asText()));

        JsonNode lanStatus = response.get("lanStatus");
        if (lanStatus == null) {
            return;
        }
        addStatisticsProperty(statistics, LAN_STATUS_DUPLEX_LABEL, lanStatus.get("duplex"));
        addStatisticsProperty(statistics, LAN_STATUS_SPEED_LABEL, lanStatus.get("speedMbps"));
        addStatisticsProperty(statistics, LAN_STATUS_STATE_LABEL, lanStatus.get("state"));
    }

    /**
     * Retrieve device's SIP/H323 communication protocols data
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveCommunicationProtocolsInfo(Map<String, String> statistics) throws Exception {
        JsonNode configResponse = doPost(CONFIG,
                new AbstractMap.SimpleEntry("names", Arrays.asList(REST_KEY_H323_EXTENSION, REST_KEY_H323_NAME, REST_KEY_SIP_USERNAME)), JsonNode.class);

        if (configResponse == null || configResponse.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve configuration data.");
            }
            return;
        }
        Map<String, String> properties = new HashMap<>();
        configResponse.get("vars").forEach(node -> {
            if (node.has("name") && node.has("value")) {
                properties.put(node.get("name").asText(), node.get("value").asText());
            }
        });
        statistics.put(SYSTEM_SIP_USERNAME_LABEL, properties.get(REST_KEY_SIP_USERNAME));
        statistics.put(SYSTEM_H323_NAME_LABEL, properties.get(REST_KEY_H323_NAME));
        statistics.put(SYSTEM_H323_EXTENSION_LABEL, properties.get(REST_KEY_H323_EXTENSION));
    }

    /**
     * Retrieve list of device's applications
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveApplications(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(APPS, JsonNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve application details.");
            }
            return;
        }
        JsonNode applications = response.get("apps");
        if (applications == null) {
            return;
        }
        applications.forEach(jsonNode -> {
            String appName = getJsonProperty(jsonNode, "appName", String.class);
            statistics.put(String.format(APPLICATIONS_VERSION_LABEL, appName),
                    getJsonProperty(jsonNode, "versionInfo", String.class));

            Long lastUpdatedOn = getJsonProperty(jsonNode, "lastUpdatedOn", Long.class);
            if (lastUpdatedOn != null) {
                statistics.put(String.format(APPLICATIONS_LAST_UPDATED_LABEL, appName),
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
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve sessions information.");
            }
            return;
        }
        JsonNode sessionList = response.get("sessionList");
        if (sessionList == null) {
            return;
        }

        IntStream.range(1, sessionList.size()).forEach(index -> {
            JsonNode jsonNode = sessionList.get(index - 1);
            Boolean isConnected = getJsonProperty(jsonNode, "isConnected", Boolean.class);
            Boolean isAuthenticated = getJsonProperty(jsonNode, "isAuthenticated", Boolean.class);

            statistics.put(String.format(ACTIVE_SESSIONS_USER_ID_LABEL, index), getJsonProperty(jsonNode, "userId", String.class));
            statistics.put(String.format(ACTIVE_SESSIONS_ROLE_LABEL, index), getJsonProperty(jsonNode, "role", String.class));
            statistics.put(String.format(ACTIVE_SESSIONS_LOCATION_LABEL, index), getJsonProperty(jsonNode, "location", String.class));
            statistics.put(String.format(ACTIVE_SESSIONS_CLIENT_TYPE_LABEL, index), getJsonProperty(jsonNode, "clientType", String.class));
            statistics.put(String.format(ACTIVE_SESSIONS_STATUS_LABEL, index),
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
            statistics.put(String.format(MICROPHONES_NAME_LABEL, microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "typeInString", String.class));
            statistics.put(String.format(MICROPHONES_STATUS_LABEL, microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "state", String.class));
            statistics.put(String.format(MICROPHONES_TYPE_LABEL, microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "type", String.class));
            statistics.put(String.format(MICROPHONES_HARDWARE_LABEL, microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "hwVersion", String.class));
            statistics.put(String.format(MICROPHONES_SOFTWARE_LABEL, microphoneOrderingNumber),
                    getJsonProperty(jsonNode, "swVersion", String.class));
            statistics.put(String.format(MICROPHONES_MUTE_LABEL, microphoneOrderingNumber),
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
        if (response != null) {
            statistics.put(CAMERAS_CONTENT_STATUS, response);
        }
    }

    /**
     * Retrieve device conferencing capabilities
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveConferencingCapabilities(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(CONFERENCING_CAPABILITIES, JsonNode.class);
        if (response == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve conferencing capabilities.");
            }
            return;
        }
        statistics.put(CONFERENCING_CAPABILITIES_BLAST_DIAL, response.get("canBlastDial").asBoolean() ? "Available" : "Not Available");
        statistics.put(CONFERENCING_CAPABILITIES_AUDIO_CALL, response.get("canMakeAudioCall").asBoolean() ? "Available" : "Not Available");
        statistics.put(CONFERENCING_CAPABILITIES_VIDEO_CALL, response.get("canMakeVideoCall").asBoolean() ? "Available" : "Not Available");
    }

    /**
     * Retrieve current audio status
     *
     * @param statistics map to put values to
     * @throws Exception during http communication
     */
    private void retrieveAudioStatus(Map<String, String> statistics) throws Exception {
        JsonNode response = doGet(AUDIO, JsonNode.class);
        if (response == null || response.isNull()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cannot retrieve audio status.");
            }
            return;
        }
        statistics.put(AUDIO_MUTE_LOCKED_LABEL, getJsonProperty(response, "muteLocked", String.class));
        statistics.put(AUDIO_MICROPHONES_CONNECTED, getJsonProperty(response, "numOfMicsConnected", String.class));
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
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("user", getLogin());
        request.put("password", getPassword());

        JsonNode json = doPost(SESSION, request, JsonNode.class);

        Boolean success = getJsonProperty(json, "success", Boolean.class);
        if (success == null ? false : success) {
            sessionId = getJsonProperty(json.get("session"), "sessionId", String.class);
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
                    if ("0".equals(value)) {
                        unmute();
                    } else {
                        mute();
                    }
                    updateLatestControlTimestamp();
                    break;
                case CONTROL_MUTE_VIDEO:
                    updateVideoMuteStatus("1".equals(value));
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
                case DEVICE_MODE_LABEL:
                    switchDeviceMode("1".equals(value));
                    break;
                case SIGNAGE_MODE_LABEL:
                    switchSignageMode("1".equals(value));
                    break;
                default:
                    if (logger.isWarnEnabled()) {
                        logger.warn("Unexpected control action " + property);
                    }
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
