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
}
