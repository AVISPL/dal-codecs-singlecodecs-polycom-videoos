/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.data;

/**
 * Property names for controllable properties.
 * Grouped controls use the group prefix so they appear alongside their
 * related monitored data in the UI. The sole ungrouped control is Reboot,
 * which has no natural group.
 */
public final class ControlKey {

    private ControlKey() {}

    public static final String MUTE_MICROPHONES = "MuteMicrophones";
    public static final String MUTE_VIDEO       = "MuteLocalVideo";
    public static final String VOLUME           = "AudioVolume";

    // System group
    public static final String DEVICE_MODE  = "System#DeviceMode";
    public static final String SIGNAGE_MODE = "System#SignageMode";

    // Applications group
    public static final String APP_PROVIDER = "Applications#Provider";
    public static final String APP_SAVE     = "Applications#SaveProvider";

    // Ungrouped
    public static final String REBOOT = "Reboot";
}
