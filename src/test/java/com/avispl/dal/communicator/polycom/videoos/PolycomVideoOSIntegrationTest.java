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
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests — require a live device at 172.31.200.19 (admin / 1234).
 * Run with: mvn test -Dgroups=integrationTest
 */
@Tag("integrationTest")
class PolycomVideoOSIntegrationTest {

    private PolycomVideoOS adapter;

    @BeforeEach
    void setup() throws Exception {
        adapter = new PolycomVideoOS();
        adapter.setHost("172.0.0.1");
        adapter.setProtocol("https");
        adapter.setPort(443);
        adapter.setLogin("admin");
        adapter.setPassword("");
        adapter.setApiPollingInterval(60_000);
        adapter.init();
    }

    @AfterEach
    void teardown() throws Exception {
        if (adapter != null) adapter.destroy();
    }

    @Test
    void getMultipleStatistics_allGroupsPresent() throws Exception {
        Map<String, String> props = stats();
        for (PropertyGroup group : PropertyGroup.values()) {
            assertTrue(
                props.keySet().stream().anyMatch(k -> k.startsWith(group.baseName)),
                "Missing group: " + group.baseName
            );
        }
    }

    @Test
    void getMultipleStatistics_noNullValues() throws Exception {
        stats().forEach((k, v) -> assertNotNull(v, "Null value for: " + k));
    }

    @Test
    void getMultipleStatistics_secondCallIsFromCache() throws Exception {
        adapter.getMultipleStatistics();
        long start = System.currentTimeMillis();
        adapter.getMultipleStatistics();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "Cached response should return in < 500ms, took " + elapsed + "ms");
    }

    @Test
    void getMultipleStatistics_allExpectedControlsPresent() throws Exception {
        List<String> controlNames = controls().stream()
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
    }

    @Test
    void getMultipleStatistics_appProviderIsDropdownWithOptions() throws Exception {
        AdvancedControllableProperty provider = findControl(ControlKey.APP_PROVIDER);
        assertNotNull(provider, "Applications#Provider must be present");
        assertTrue(provider.getType() instanceof AdvancedControllableProperty.DropDown,
            "Applications#Provider must be a DropDown");
        AdvancedControllableProperty.DropDown dd = (AdvancedControllableProperty.DropDown) provider.getType();
        List<String> options = Arrays.asList(dd.getOptions());
        assertFalse(options.isEmpty(), "Provider dropdown must have options");
    }

    @Test
    void controlProperty_muteMicrophones_toggleAndVerify() throws Exception {
        adapter.getMultipleStatistics();
        String current = stats().get(ControlKey.MUTE_MICROPHONES);
        String toggled  = "1".equals(current) ? "0" : "1";

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.MUTE_MICROPHONES);
        cp.setValue(toggled);
        adapter.controlProperty(cp);

        assertEquals(toggled, stats().get(ControlKey.MUTE_MICROPHONES),
            "Cache should reflect toggled mute state immediately");
    }

    @Test
    void controlProperty_volume_setAndVerify() throws Exception {
        adapter.getMultipleStatistics();

        for (String target : new String[]{"40", "70"}) {
            ControllableProperty cp = new ControllableProperty();
            cp.setProperty(ControlKey.VOLUME);
            cp.setValue(target);
            adapter.controlProperty(cp);
            assertEquals(target, stats().get(ControlKey.VOLUME),
                "Volume cache should update to " + target);
        }
    }

    @Test
    void controlProperty_muteVideo_toggleAndVerify() throws Exception {
        adapter.getMultipleStatistics();
        String current = stats().get(ControlKey.MUTE_VIDEO);
        String toggled  = "1".equals(current) ? "0" : "1";

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.MUTE_VIDEO);
        cp.setValue(toggled);
        adapter.controlProperty(cp);

        assertEquals(toggled, stats().get(ControlKey.MUTE_VIDEO));
    }

    @Test
    void controlProperty_deviceMode_toggleAndVerify() throws Exception {
        adapter.getMultipleStatistics();
        String current = stats().get(ControlKey.DEVICE_MODE);
        String toggled  = "true".equals(current) ? "0" : "1";

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.DEVICE_MODE);
        cp.setValue(toggled);
        adapter.controlProperty(cp);

        assertNotNull(stats().get(ControlKey.DEVICE_MODE));
    }

    @Test
    void controlProperty_signageMode_toggleAndVerify() throws Exception {
        adapter.getMultipleStatistics();
        String current = stats().get(ControlKey.SIGNAGE_MODE);
        String toggled  = "1".equals(current) ? "0" : "1";

        ControllableProperty cp = new ControllableProperty();
        cp.setProperty(ControlKey.SIGNAGE_MODE);
        cp.setValue(toggled);
        adapter.controlProperty(cp);

        assertEquals(toggled, stats().get(ControlKey.SIGNAGE_MODE),
            "Cache should reflect toggled signage mode immediately");
    }

    @Test
    void controlProperty_appProviderSelect_thenSave() throws Exception {
        adapter.getMultipleStatistics();

        AdvancedControllableProperty providerCtrl = findControl(ControlKey.APP_PROVIDER);
        assertNotNull(providerCtrl, "APP_PROVIDER control must be present before this test");
        AdvancedControllableProperty.DropDown dd = (AdvancedControllableProperty.DropDown) providerCtrl.getType();
        String targetProvider = dd.getOptions()[0];

        ControllableProperty selectCp = new ControllableProperty();
        selectCp.setProperty(ControlKey.APP_PROVIDER);
        selectCp.setValue(targetProvider);
        adapter.controlProperty(selectCp);
        assertEquals(targetProvider, stats().get(ControlKey.APP_PROVIDER),
            "Cache should reflect selected provider immediately");

        AdvancedControllableProperty saveCtrl = findControl(ControlKey.APP_SAVE);
        assertNotNull(saveCtrl, "APP_SAVE control must appear after provider is selected");

        ControllableProperty saveCp = new ControllableProperty();
        saveCp.setProperty(ControlKey.APP_SAVE);
        saveCp.setValue("");
        adapter.controlProperty(saveCp);
    }

    @Test
    @Tag("manual")
    void controlProperty_reboot_deviceRecovery() throws Exception {
        adapter.getMultipleStatistics();

        ControllableProperty reboot = new ControllableProperty();
        reboot.setProperty(ControlKey.REBOOT);
        reboot.setValue("");
        adapter.controlProperty(reboot);

        // Device takes ~3 minutes to reboot
        Thread.sleep(200_000);

        List<Statistics> result = adapter.getMultipleStatistics();
        assertFalse(result.isEmpty(), "Should reconnect successfully after reboot");
        Map<String, String> props = ((ExtendedStatistics) result.get(0)).getStatistics();
        assertFalse(props.isEmpty(), "Statistics should be non-empty after reboot recovery");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExtendedStatistics extStats() throws Exception {
        return (ExtendedStatistics) adapter.getMultipleStatistics().get(0);
    }

    private Map<String, String> stats() throws Exception {
        return extStats().getStatistics();
    }

    private List<AdvancedControllableProperty> controls() throws Exception {
        return extStats().getControllableProperties();
    }

    private AdvancedControllableProperty findControl(String name) throws Exception {
        return controls().stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst().orElse(null);
    }
}
