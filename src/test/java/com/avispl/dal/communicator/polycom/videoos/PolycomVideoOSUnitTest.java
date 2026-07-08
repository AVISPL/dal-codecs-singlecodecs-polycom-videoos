/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.dal.communicator.polycom.videoos;

import com.avispl.dal.communicator.polycom.videoos.data.ControlKey;
import com.avispl.dal.communicator.polycom.videoos.data.PropertyGroup;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unitTest")
class PolycomVideoOSUnitTest {

    private WireMockServer wireMock;
    private PolycomVideoOS adapter;

    @BeforeEach
    void setup() throws Exception {
        wireMock = new WireMockServer(options()
            .dynamicPort()
            .dynamicHttpsPort()
            .usingFilesUnderDirectory("src/test/resources")
            .bindAddress("127.0.0.1"));
        wireMock.start();

        adapter = new PolycomVideoOS();
        adapter.setTrustAllCertificates(true);
        adapter.setProtocol("https");
        adapter.setPort(wireMock.httpsPort());
        adapter.setHost("127.0.0.1");
        adapter.setLogin("admin");
        adapter.setPassword("1234");
        adapter.setApiPollingInterval(60_000);
        adapter.init();
    }

    @AfterEach
    void teardown() throws Exception {
        if (adapter != null) adapter.destroy();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void getMultipleStatistics_returnsNonEmptyList() throws Exception {
        List<Statistics> result = adapter.getMultipleStatistics();
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void getMultipleStatistics_allGroupPrefixesPresent() throws Exception {
        Map<String, String> props = stats();
        for (PropertyGroup group : PropertyGroup.values()) {
            assertTrue(
                props.keySet().stream().anyMatch(k -> k.startsWith(group.baseName)),
                "Missing group prefix: " + group.baseName
            );
        }
    }

    @Test
    void getMultipleStatistics_noNullValues() throws Exception {
        Map<String, String> props = stats();
        props.forEach((key, value) ->
            assertNotNull(value, "Null value for property: " + key)
        );
    }

    @Test
    void getMultipleStatistics_secondCallReturnsCacheWithinCooldown() throws Exception {
        adapter.getMultipleStatistics();
        int requestCountAfterFirst = wireMock.getAllServeEvents().size();

        adapter.getMultipleStatistics();
        int requestCountAfterSecond = wireMock.getAllServeEvents().size();

        assertEquals(requestCountAfterFirst, requestCountAfterSecond,
            "Second call within polling interval should not issue new HTTP requests");
    }

    @Test
    void getMultipleStatistics_adapterMetadataPresent() throws Exception {
        Map<String, String> props = stats();
        assertNotNull(props.get(PropertyGroup.ADAPTER_METADATA.key("AdapterVersion")));
        assertNotNull(props.get(PropertyGroup.ADAPTER_METADATA.key("AdapterBuildDate")));
        assertNotNull(props.get(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime")));
        assertNotNull(props.get(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime(min)")));
        assertNotNull(props.get(PropertyGroup.ADAPTER_METADATA.key("LastMonitoringCycleTimestamp")));
    }

    @Test
    void getMultipleStatistics_allExpectedControlsPresent() throws Exception {
        List<String> controlNames = extStats().getControllableProperties().stream()
            .map(AdvancedControllableProperty::getName)
            .collect(java.util.stream.Collectors.toList());
        for (String expected : Arrays.asList(
                ControlKey.MUTE_MICROPHONES, ControlKey.MUTE_VIDEO, ControlKey.VOLUME,
                ControlKey.DEVICE_MODE, ControlKey.SIGNAGE_MODE,
                ControlKey.APP_PROVIDER,
                ControlKey.REBOOT)) {
            assertTrue(controlNames.contains(expected), "Missing expected control: " + expected);
        }
    }

    @Test
    void getMultipleStatistics_rebootButtonPresent() throws Exception {
        AdvancedControllableProperty reboot = findControl(ControlKey.REBOOT);
        assertNotNull(reboot, "Reboot control must always be present");
        assertTrue(reboot.getType() instanceof AdvancedControllableProperty.Button,
            "Reboot must be a Button");
    }

    @Test
    void getMultipleStatistics_allControlsHaveMonitoredCounterpart() throws Exception {
        ExtendedStatistics es = extStats();
        Map<String, String> props = es.getStatistics();
        for (AdvancedControllableProperty ctrl : es.getControllableProperties()) {
            assertTrue(props.containsKey(ctrl.getName()),
                "Controllable property has no monitored counterpart: " + ctrl.getName());
        }
    }

    @Test
    void getMultipleStatistics_switchControlsAreCorrectType() throws Exception {
        for (String name : Arrays.asList(
                ControlKey.MUTE_MICROPHONES, ControlKey.MUTE_VIDEO,
                ControlKey.DEVICE_MODE, ControlKey.SIGNAGE_MODE)) {
            AdvancedControllableProperty ctrl = findControl(name);
            assertNotNull(ctrl, "Missing switch: " + name);
            assertTrue(ctrl.getType() instanceof AdvancedControllableProperty.Switch,
                name + " must be a Switch");
        }
    }

    @Test
    void getMultipleStatistics_volumeIsSliderWithCorrectRange() throws Exception {
        AdvancedControllableProperty volume = findControl(ControlKey.VOLUME);
        assertNotNull(volume, "Volume control must be present");
        assertTrue(volume.getType() instanceof AdvancedControllableProperty.Slider,
            "Volume must be a Slider");
        AdvancedControllableProperty.Slider slider = (AdvancedControllableProperty.Slider) volume.getType();
        assertEquals(0.0f,   slider.getRangeStart(), 0.01f);
        assertEquals(100.0f, slider.getRangeEnd(),   0.01f);
        assertEquals(50.0f, ((Number) volume.getValue()).floatValue(), 0.01f);
    }

    @Test
    void getMultipleStatistics_appProviderIsDropdownWithOptions() throws Exception {
        AdvancedControllableProperty provider = findControl(ControlKey.APP_PROVIDER);
        assertNotNull(provider, "Applications#Provider must be present");
        assertTrue(provider.getType() instanceof AdvancedControllableProperty.DropDown,
            "Applications#Provider must be a DropDown");
        AdvancedControllableProperty.DropDown dd = (AdvancedControllableProperty.DropDown) provider.getType();
        List<String> options = Arrays.asList(dd.getOptions());
        assertTrue(options.contains("Microsoft Teams"), "Dropdown must include Microsoft Teams");
        assertTrue(options.contains("Zoom"),            "Dropdown must include Zoom");
        assertTrue(options.contains("BlueJeans"),       "Dropdown must include BlueJeans");
    }

    @Test
    void controlProperty_muteMicrophones_updatesCacheImmediately() throws Exception {
        adapter.getMultipleStatistics();

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.MUTE_MICROPHONES);
        cp.setValue("1");
        adapter.controlProperty(cp);

        // Next call is within cooldown — must return updated cache, not re-poll
        Map<String, String> props = stats();
        assertEquals("1", props.get(ControlKey.MUTE_MICROPHONES),
            "Mute microphones cache should reflect control value immediately");
    }

    @Test
    void controlProperty_volume_updatesCacheImmediately() throws Exception {
        adapter.getMultipleStatistics();

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.VOLUME);
        cp.setValue("75");
        adapter.controlProperty(cp);

        assertEquals("75", stats().get(ControlKey.VOLUME));
    }

    @Test
    void controlProperty_muteVideo_updatesCacheImmediately() throws Exception {
        adapter.getMultipleStatistics();

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.MUTE_VIDEO);
        cp.setValue("1");
        adapter.controlProperty(cp);

        assertEquals("1", stats().get(ControlKey.MUTE_VIDEO));
    }

    @Test
    void controlProperty_deviceMode_updatesCacheImmediately() throws Exception {
        adapter.getMultipleStatistics();

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.DEVICE_MODE);
        cp.setValue("1");
        adapter.controlProperty(cp);

        assertEquals("1", stats().get(ControlKey.DEVICE_MODE));
    }

    @Test
    void controlProperty_appProviderSelection_resetsAfterTimeout() throws Exception {
        adapter.getMultipleStatistics();

        // Select a provider — APP_SAVE appears immediately via optimistic cache update
        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.APP_PROVIDER);
        cp.setValue("Zoom");
        adapter.controlProperty(cp);

        assertNotNull(findControl(ControlKey.APP_SAVE),
            "APP_SAVE button must appear after provider selection");
        assertEquals("Zoom", stats().get(ControlKey.APP_PROVIDER));

        // Timeout = 0 means any elapsed time qualifies as expired
        adapter.setAppProviderSelectionTimeoutMin(0);

        // Force a full refresh: zero both timestamps so shouldReturnCache() returns false.
        // lastControlTimestamp must also be zeroed because setApiPollingInterval() is overridden
        // by updatePollingInterval() at the top of getMultipleStatistics(), so we bypass the
        // polling-interval check via lastPollTimestamp instead.
        Field lastControlTs = PolycomVideoOS.class.getDeclaredField("lastControlTimestamp");
        lastControlTs.setAccessible(true);
        lastControlTs.set(adapter, 0L);

        Field lastPollTs = PolycomVideoOS.class.getDeclaredField("lastPollTimestamp");
        lastPollTs.setAccessible(true);
        lastPollTs.set(adapter, 0L);

        // Full refresh — resetProviderSelectionIfExpired() fires, then fetchApplications re-polls device
        ExtendedStatistics es = (ExtendedStatistics) adapter.getMultipleStatistics().get(0);
        Map<String, String> props    = es.getStatistics();
        List<AdvancedControllableProperty> controls = es.getControllableProperties();

        assertFalse(props.containsKey(ControlKey.APP_SAVE),
            "APP_SAVE must be absent from statistics after expiry");
        assertTrue(controls.stream().noneMatch(c -> ControlKey.APP_SAVE.equals(c.getName())),
            "APP_SAVE control must be removed after expiry");
        // Microsoft Teams has the highest lastUpdatedOn in stubs — it becomes latestApp
        assertEquals("Microsoft Teams", props.get(ControlKey.APP_PROVIDER),
            "APP_PROVIDER must revert to device-reported value after expiry");
    }

    @Test
    void formatUptimeSeconds_allUnits_spaceBetweenNumberAndUnit() throws Exception {
        setInitTimestamp((1 * 86400L + 2 * 3600L + 30 * 60L + 5) * 1000);
        assertEquals("1 d 2 hr 30 min 5 sec", stats().get(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime")));
    }

    @Test
    void formatUptimeSeconds_minutesAndSeconds_spaceBetweenNumberAndUnit() throws Exception {
        setInitTimestamp((45 * 60L + 3) * 1000);
        assertEquals("45 min 3 sec", stats().get(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime")));
    }

    @Test
    void formatUptimeSeconds_secondsOnly_spaceBetweenNumberAndUnit() throws Exception {
        setInitTimestamp(42_000);
        assertEquals("42 sec", stats().get(PropertyGroup.ADAPTER_METADATA.key("AdapterUptime")));
    }

    @Test
    void setDisplayPropertyGroups_unsortedInput_activePropertyGroupsIsAlphabetical() throws Exception {
        adapter.setDisplayPropertyGroups("System, Audio, Cameras");
        Map<String, String> props = stats();
        assertEquals("Audio, Cameras, System",
            props.get(PropertyGroup.ADAPTER_METADATA.key("ActivePropertyGroups")));
    }

    @Test
    void setDisplayPropertyGroupsPreset_multipleGroups_activePropertyGroupsIsAlphabetical() throws Exception {
        adapter.setDisplayPropertyGroupsPreset("DeviceMode");
        Map<String, String> props = stats();
        assertEquals("ActiveSessions, Applications, Audio, Conferences, Microphone, Peripherals, System, SystemStatus",
            props.get(PropertyGroup.ADAPTER_METADATA.key("ActivePropertyGroups")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Statistics> rawStats() throws Exception {
        return adapter.getMultipleStatistics();
    }

    private ExtendedStatistics extStats() throws Exception {
        return (ExtendedStatistics) rawStats().get(0);
    }

    private Map<String, String> stats() throws Exception {
        return extStats().getStatistics();
    }

    private AdvancedControllableProperty findControl(String name) throws Exception {
        return extStats().getControllableProperties().stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst().orElse(null);
    }

    private void setInitTimestamp(long elapsedMs) throws Exception {
        Field f = PolycomVideoOS.class.getDeclaredField("initTimestamp");
        f.setAccessible(true);
        f.set(adapter, System.currentTimeMillis() - elapsedMs);
    }
}
