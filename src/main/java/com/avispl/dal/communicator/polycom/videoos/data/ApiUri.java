/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.data;

/**
 * Poly VideoOS REST API endpoint paths.
 */
public final class ApiUri {

    private ApiUri() {}

    public static final String SESSION              = "rest/current/session";
    public static final String SESSIONS_LIST        = "rest/current/session/sessions";

    public static final String SYSTEM_STATUS        = "rest/system/status";
    public static final String SYSTEM               = "rest/system";
    public static final String CONFIG               = "rest/config";
    public static final String DEVICE_MODE          = "rest/system/mode/device";
    public static final String SIGNAGE_MODE         = "rest/system/mode/signage";
    public static final String REBOOT               = "rest/system/reboot";

    public static final String AUDIO                = "rest/audio";
    public static final String AUDIO_MUTED          = "rest/audio/muted";
    public static final String AUDIO_MICROPHONES    = "rest/audio/microphones";
    public static final String AUDIO_VOLUME         = "rest/audio/volume";

    public static final String VIDEO_MUTE           = "rest/video/local/mute";

    public static final String CAMERAS_NEAR_ALL     = "rest/cameras/near/all";
    public static final String CONTENT_STATUS       = "rest/cameras/contentstatus";

    public static final String CALENDAR             = "rest/calendar";
    public static final String CALENDAR_MEETINGS    = "rest/calendar/meetings?number=1";

    public static final String COLLABORATION        = "rest/collaboration";
    public static final String CONFERENCING_CAPS    = "rest/conferences/capabilities";

    public static final String APPS                 = "rest/system/apps";
    public static final String SYSTEM_APPS          = "rest/current/system/apps/all";
    public static final String SYSTEM_MODE          = "rest/current/system/mode";

    // POST with null body — undocumented peripheral device enumeration endpoint
    public static final String PERIPHERAL_DEVICES   = "rest/current/devicemanagement/devices";

    public static final String CONFERENCES           = "rest/conferences";
    public static final String CONFERENCE_MEDIASTATS = "rest/conferences/%s/mediastats";
    public static final String SHARED_MEDIASTATS     = "rest/mediastats";
    public static final String SIP_SERVERS           = "rest/system/sipservers";
    public static final String H323_SERVERS          = "rest/system/h323gatekeepers";

    // Keys used in POST /rest/config to retrieve SIP/H.323 identities
    public static final String CONFIG_KEY_SIP_USERNAME   = "comm.nics.sipnic.sipusername";
    public static final String CONFIG_KEY_H323_NAME      = "comm.nics.h323nic.h323name";
    public static final String CONFIG_KEY_H323_EXTENSION = "comm.nics.h323nic.h323extension";
}
