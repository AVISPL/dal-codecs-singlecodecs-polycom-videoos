/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.data;

/**
 * Adapter constants, storage for URIs, property mapping model names, property names and predefined values
 *
 * @author Maksym.Rossiitsev/Symphony Team
 * @since 1.0.6
 * */
public interface Constant {
    /**
     * Adapter extended properties names
     * */
    interface Property {
        String SYSTEM_STATUS_GROUP_LABEL = "SystemStatus#";
        String COLLABORATION_SESSION_STATE_LABEL = "Collaboration#SessionState";
        String COLLABORATION_SESSION_ID_LABEL = "Collaboration#SessionId";
        String ACTIVE_CONFERENCE_ID_LABEL = "ActiveConference#ConferenceId";
        String ACTIVE_CONFERENCE_START_TIME_LABEL = "ActiveConference#ConferenceStartTime";
        String ACTIVE_CONFERENCE_TERMINAL_ADDRESS_LABEL = "ActiveConference#Terminal%sAddress";
        String ACTIVE_CONFERENCE_TERMINAL_SYSTEM_LABEL = "ActiveConference#Terminal%sSystem";
        String ACTIVE_CONFERENCE_CONNECTION_TYPE_LABEL = "ActiveConference#Connection%sType";
        String ACTIVE_CONFERENCE_CONNECTION_INFO_LABEL = "ActiveConference#Connection%sInfo";
        String APPLICATIONS_VERSION_LABEL = "Applications#%sVersion";
        String APPLICATIONS_LAST_UPDATED_LABEL = "Applications#%sLastUpdated";
        String APPLICATIONS_PROVIDER = "Applications#Provider";
        String APPLICATIONS_SAVE_PROVIDER = "Applications#SaveProvider";
        String MICROPHONES_NAME_LABEL = "Microphones#Microphone%sName";
        String MICROPHONES_STATUS_LABEL = "Microphones#Microphone%sState";
        String MICROPHONES_TYPE_LABEL = "Microphones#Microphone%sType";
        String MICROPHONES_HARDWARE_LABEL = "Microphones#Microphone%sHardwareVersion";
        String MICROPHONES_SOFTWARE_LABEL = "Microphones#Microphone%sSoftwareVersion";
        String MICROPHONES_MUTE_LABEL = "Microphones#Microphone%sMuted";
        String CAMERAS_CONTENT_STATUS = "Cameras#ContentStatus";
        String CONFERENCING_CAPABILITIES_BLAST_DIAL = "ConferencingCapabilities#BlastDial";
        String CONFERENCING_CAPABILITIES_AUDIO_CALL = "ConferencingCapabilities#AudioCall";
        String CONFERENCING_CAPABILITIES_VIDEO_CALL = "ConferencingCapabilities#VideoCall";
        String AUDIO_MUTE_LOCKED_LABEL = "Audio#MuteLocked";
        String AUDIO_MICROPHONES_CONNECTED = "Audio#MicrophonesConnected";
        String ACTIVE_SESSIONS_USER_ID_LABEL = "ActiveSessions#Session%sUserId";
        String ACTIVE_SESSIONS_ROLE_LABEL = "ActiveSessions#Session%sRole";
        String ACTIVE_SESSIONS_LOCATION_LABEL = "ActiveSessions#Session%sLocation";
        String ACTIVE_SESSIONS_CLIENT_TYPE_LABEL = "ActiveSessions#Session%sClientType";
        String ACTIVE_SESSIONS_STATUS_LABEL = "ActiveSessions#Session%sStatus";
        String SYSTEM_NAME_LABEL = "System#System Name";
        String SYSTEM_SIP_USERNAME_LABEL = "System#SIPUsername";
        String SYSTEM_H323_EXTENSION_LABEL = "System#H323Extension";
        String SYSTEM_H323_NAME_LABEL = "System#H323Name";

        String SYSTEM_SERIAL_NUMBER_LABEL = "System#Serial Number";
        String SYSTEM_SOFTWARE_VERSION_LABEL = "System#Software Version";
        String SYSTEM_STATE_LABEL = "System#System State";
        String SYSTEM_BUILD_LABEL = "System#System Build";
        String SYSTEM_REBOOT_NEEDED_LABEL = "System#System Reboot Needed";
        String SYSTEM_DEVICE_MODEL_LABEL = "System#Device Model";
        String SYSTEM_HARDWARE_VERSION_LABEL = "System#Device Hardware Version";
        String SYSTEM_UPTIME_LABEL = "System#System Uptime";
        String LAN_STATUS_DUPLEX_LABEL = "Lan Status#Duplex";
        String LAN_STATUS_SPEED_LABEL = "Lan Status#Speed Mbps";
        String LAN_STATUS_STATE_LABEL = "Lan Status#State";

        String DEVICE_MODE_LABEL = "System#DeviceMode";
        String SIGNAGE_MODE_LABEL = "System#SignageMode";

        String CONTROL_MUTE_VIDEO = "MuteLocalVideo";
        String CONTROL_MUTE_MICROPHONES = "MuteMicrophones";
        String CONTROL_AUDIO_VOLUME = "AudioVolume";
        String CONTROL_REBOOT = "Reboot";
        String ADAPTER_UPTIME = "AdapterUptime";
        String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
        String ADAPTER_BUILD_DATE = "AdapterBuildDate";
        String ADAPTER_VERSION = "AdapterVersion";

        String REST_KEY_SIP_USERNAME = "comm.nics.sipnic.sipusername";
        String REST_KEY_H323_NAME = "comm.nics.h323nic.h323name";
        String REST_KEY_H323_EXTENSION = "comm.nics.h323nic.h323extension";

        String CALL_ID_TEMPLATE = "%s:%s:%s:%s";
        String PERIPHERALS_TEMPLATE = "Peripherals[%s:%s:%s]#";
    }

    /**
     * URI endpoints used by the adapter
     * */
    interface URI {
        String SESSION = "rest/current/session";
        String STATUS = "rest/system/status";
        String CONFERENCING_CAPABILITIES = "rest/conferences/capabilities";
        String CONFERENCES = "rest/conferences"; // POST for calling a single participant, GET to list all
        String CONFERENCE = "rest/conferences/%s"; // DELETE to disconnect
        String MEDIASTATS = "rest/conferences/%s/mediastats";
        String SHARED_MEDIASTATS = "/rest/mediastats";
        String AUDIO = "rest/audio";
        String AUDIO_MUTED = "rest/audio/muted";
        String VIDEO_MUTE = "rest/video/local/mute";
        String CONTENT_STATUS = "rest/cameras/contentstatus";
        String VOLUME = "rest/audio/volume";
        String SYSTEM = "rest/system";
        String CONFIG = "rest/config";
        String REBOOT = "rest/system/reboot";
        String COLLABORATION = "rest/collaboration";
        String MICROPHONES = "rest/audio/microphones";
        String APPS = "rest/system/apps";
        String SESSIONS = "rest/current/session/sessions";
        String SIP_SERVERS = "rest/system/sipservers";
        String H323_SERVERS = "rest/system/h323gatekeepers";
        String DEVICE_MODE = "rest/system/mode/device";
        String SIGNAGE_MODE = "rest/system/mode/signage";
        String PERIPHERAL_DEVICES = "rest/current/devicemanagement/devices";
        String SYSTEM_MODE = "rest/current/system/mode";
        String SYSTEM_APPS = "rest/current/system/apps/all";
    }
}
