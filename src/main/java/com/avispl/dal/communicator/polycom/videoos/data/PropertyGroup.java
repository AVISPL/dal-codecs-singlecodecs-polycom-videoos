/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos.data;

/**
 * All monitored property groups. Each entry owns its prefix and can compose
 * full property keys via {@link #key(String)}.
 *
 * Usage:
 *   stats.put(PropertyGroup.SYSTEM.key("SerialNumber"), value);
 *   stats.keySet().removeIf(k -> k.startsWith(PropertyGroup.SYSTEM.prefix));
 *
 * @author Maksym.Rossiitsev/Symphony Team
 */
public enum PropertyGroup {

    ADAPTER_METADATA          ("AdapterMetadata"),
    SYSTEM_STATUS             ("SystemStatus"),
    SYSTEM                    ("System"),
    LAN_STATUS                ("LANStatus"),
    AUDIO                     ("Audio"),
    MICROPHONE                ("Microphone"),
    CAMERA                    ("Camera"),
    CAMERAS                   ("Cameras"),
    CALENDAR                  ("Calendar"),
    COLLABORATION             ("Collaboration"),
    CONFERENCING_CAPABILITIES ("ConferencingCapabilities"),
    ACTIVE_SESSIONS           ("ActiveSessions"),
    ACTIVE_CONFERENCE         ("ActiveConference"),
    APPLICATIONS              ("Applications"),
    PERIPHERALS               ("Peripherals");

    /** The bare group name without any suffix, e.g. {@code "System"}. */
    public final String baseName;

    /** The full group prefix including the trailing {@code #}, e.g. {@code "System#"}. */
    public final String prefix;

    PropertyGroup(String name) {
        this.baseName = name;
        this.prefix   = name + "#";
    }

    /** Returns {@code prefix + name}, e.g. {@code "System#SerialNumber"}. */
    public String key(String name) {
        return prefix + name;
    }

    /** Returns {@code baseName + identifier + "#"}, e.g. {@code "Microphone[1]#"}. */
    public String indexedPrefix(String identifier) {
        return baseName + identifier + "#";
    }
}
