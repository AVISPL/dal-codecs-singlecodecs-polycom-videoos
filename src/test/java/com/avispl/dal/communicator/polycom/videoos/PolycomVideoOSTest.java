package com.avispl.dal.communicator.polycom.videoos;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.control.Protocol;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.control.call.MuteStatus;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.Assert;

import java.util.List;

import static org.junit.Assert.fail;

@Tag("integrationTest")
public class PolycomVideoOSTest {
    PolycomVideoOS polycomVideoOS;

    @BeforeEach
    public void setUp() throws Exception {
        polycomVideoOS = new PolycomVideoOS();
        polycomVideoOS.setHost("***REMOVED***");
        polycomVideoOS.setProtocol("https");
        polycomVideoOS.setPort(443);
        polycomVideoOS.setPassword("1234");
        polycomVideoOS.setLogin("admin");
        polycomVideoOS.init();
    }

    @Test
    public void testGetStatistics() throws Exception{
        long startTime = System.currentTimeMillis();
        List<Statistics> statisticsList = polycomVideoOS.getMultipleStatistics();
        System.out.println("Statistics retrieved in " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
        Thread.sleep(30000);
        startTime = System.currentTimeMillis();
        statisticsList = polycomVideoOS.getMultipleStatistics();
        System.out.println("Statistics retrieved in " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
        Thread.sleep(30000);
        startTime = System.currentTimeMillis();
        statisticsList = polycomVideoOS.getMultipleStatistics();
        System.out.println("Statistics retrieved in " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
        ExtendedStatistics extendedStatistics = (ExtendedStatistics) statisticsList.get(0);
        Assert.noNullElements(extendedStatistics.getStatistics().entrySet().toArray(), "Statistics should not contain null elements");
    }

    @Test
    public void testGetStatisticsAfterReboot() throws Exception{
        List<Statistics> statisticsList = polycomVideoOS.getMultipleStatistics();
        ExtendedStatistics extendedStatistics = (ExtendedStatistics) statisticsList.get(0);
        Assert.noNullElements(extendedStatistics.getStatistics().entrySet().toArray(), "Statistics should not contain null elements");

        ControllableProperty reboot = new ControllableProperty();
        reboot.setProperty("Reboot");
        reboot.setValue("");

        polycomVideoOS.controlProperty(reboot);
        Thread.sleep(180000);

        extendedStatistics = (ExtendedStatistics) polycomVideoOS.getMultipleStatistics().get(0);
        Assert.noNullElements(extendedStatistics.getStatistics().entrySet().toArray(), "Statistics should not contain null elements");

        Thread.sleep(30000);
        extendedStatistics = (ExtendedStatistics) polycomVideoOS.getMultipleStatistics().get(0);
        Assert.noNullElements(extendedStatistics.getStatistics().entrySet().toArray(), "Statistics should not contain null elements");
    }

    @Test
    public void testDial() throws Exception {
        DialDevice dialDevice = new DialDevice();
        dialDevice.setProtocol(Protocol.SIP);
        dialDevice.setCallSpeed(320);
        dialDevice.setDialString("1125198839@vtc.avispl.com");
        String conferenceId = polycomVideoOS.dial(dialDevice);
        Assert.notNull(conferenceId, "ConferenceId must not be nul");
    }

    @Test
    public void testCallStatus() throws Exception {
        CallStatus status = polycomVideoOS.retrieveCallStatus("0:3:1642170166000:nh-studiox30@nh.vnoc1.com");
        Assert.notNull(status, "Status should be present");
    }

    @Test
    public void testPropertiesRetrieval() throws Exception {
        // No exception is thrown -> properties are retrieved properly
        polycomVideoOS.internalInit();
    }

    @Test
    public void testMuteUnmuteMicrophones() throws Exception {
        polycomVideoOS.getMultipleStatistics();
        if(polycomVideoOS.retrieveMuteStatus().equals(MuteStatus.Muted)) {
            ControllableProperty controllableProperty = new ControllableProperty();
            controllableProperty.setProperty("MuteMicrophones");
            controllableProperty.setValue("0");
            polycomVideoOS.controlProperty(controllableProperty);
            Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                    .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("MuteMicrophones")).findFirst()
                    .get().getValue().equals("0"), "Mute Microphones control must be 0!");
        } else {
            ControllableProperty controllableProperty = new ControllableProperty();
            controllableProperty.setProperty("MuteMicrophones");
            controllableProperty.setValue("1");
            polycomVideoOS.controlProperty(controllableProperty);
            Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                    .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("MuteMicrophones")).findFirst()
                    .get().getValue().equals("1"), "Mute Microphones control must be 1!");
        }
    }

    @Test
    public void testMuteUnmuteLocalVideo() throws Exception {
        ExtendedStatistics extendedStatistics = (ExtendedStatistics) polycomVideoOS.getMultipleStatistics().get(0);

        if(extendedStatistics.getControllableProperties().stream()
                .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("MuteLocalVideo")).findFirst()
                .get().getValue().equals("1")) {
            ControllableProperty controllableProperty = new ControllableProperty();
            controllableProperty.setProperty("MuteLocalVideo");
            controllableProperty.setValue("0");
            polycomVideoOS.controlProperty(controllableProperty);
            Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                    .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("MuteLocalVideo")).findFirst()
                    .get().getValue().equals("0"), "Mute Local Video control must be 0!");
        } else {
            ControllableProperty controllableProperty = new ControllableProperty();
            controllableProperty.setProperty("MuteLocalVideo");
            controllableProperty.setValue("1");
            polycomVideoOS.controlProperty(controllableProperty);
            Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                    .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("MuteLocalVideo")).findFirst()
                    .get().getValue().equals("1"), "Mute Local Video control must be 1!");
        }
    }

    @Test
    public void testVolumeChange() throws Exception {
        polycomVideoOS.getMultipleStatistics();
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("AudioVolume");
        controllableProperty.setValue("50");
        polycomVideoOS.controlProperty(controllableProperty);
        Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("AudioVolume")).findFirst()
                .get().getValue().equals("50"), "Audio Volume control must be 50!");

        controllableProperty.setValue("70");
        polycomVideoOS.controlProperty(controllableProperty);
        Assert.isTrue(((ExtendedStatistics)polycomVideoOS.getMultipleStatistics().get(0)).getControllableProperties().stream()
                .filter(advancedControllableProperty -> advancedControllableProperty.getName().equals("AudioVolume")).findFirst()
                .get().getValue().equals("70"), "Audio Volume control must be 50!");
    }
}
