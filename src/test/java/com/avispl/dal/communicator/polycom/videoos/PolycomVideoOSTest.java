package com.avispl.dal.communicator.polycom.videoos;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.control.Protocol;
import com.avispl.symphony.api.dal.dto.control.call.CallStatus;
import com.avispl.symphony.api.dal.dto.control.call.DialDevice;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.Assert;

import java.util.List;

@Tag("integrationTest")
public class PolycomVideoOSTest {

    PolycomVideoOS polycomVideoOS;

    @BeforeEach
    public void setUp() throws Exception {
        polycomVideoOS = new PolycomVideoOS();
        polycomVideoOS.setHost("172.31.254.150");
        polycomVideoOS.setProtocol("https");
        polycomVideoOS.setPort(443);
        polycomVideoOS.setPassword("1234");
        polycomVideoOS.setLogin("admin");
        polycomVideoOS.init();
    }

    @Test
    public void testGetStatistics() throws Exception{
        List<Statistics> statisticsList = polycomVideoOS.getMultipleStatistics();
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
        dialDevice.setDialString("2754909175.013196@zoomcrc.com");
        String conferenceId = polycomVideoOS.dial(dialDevice);
        Assert.notNull(conferenceId, "ConferenceId must not be nul");
    }

    @Test
    public void testCallStatus() throws Exception {
        CallStatus status = polycomVideoOS.retrieveCallStatus("");
        Assert.notNull(status, "Status should be present");
    }
}
