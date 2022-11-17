package com.avispl.dal.communicator.polycom.videoos;

import com.atlassian.ta.wiremockpactgenerator.WireMockPactGenerator;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.dal.communicator.HttpCommunicator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@Tag("test")
public class PolycomVideoOSWiremockTest {
    static PolycomVideoOS polycomVideoOS;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort().dynamicHttpsPort()
            .basicAdminAuthenticator("admin", "1234").bindAddress("127.0.0.1"));

    {
//        wireMockRule.addMockServiceRequestListener(WireMockPactGenerator
//                .builder("polycom-videoos-adapter", "polycom-videoos")
//                .withRequestHeaderWhitelist("authorization", "content-type").build());
        wireMockRule.start();
    }
    @BeforeEach
    public void init() throws Exception {
        polycomVideoOS = new PolycomVideoOS();
        polycomVideoOS.setTrustAllCertificates(true);
        polycomVideoOS.setProtocol("https");
        polycomVideoOS.setContentType("application/json");
        polycomVideoOS.setPort(wireMockRule.httpsPort());
        polycomVideoOS.setHost("127.0.0.1");
        polycomVideoOS.setAuthenticationScheme(HttpCommunicator.AuthenticationScheme.Basic);
        polycomVideoOS.setLogin("admin");
        polycomVideoOS.setPassword("1234");
        polycomVideoOS.init();
    }

    @Test
    public void testGetMultipleStatistics() throws Exception {
        List<Statistics> statisticsList = polycomVideoOS.getMultipleStatistics();
        Assert.assertNotNull(statisticsList);
    }
}
