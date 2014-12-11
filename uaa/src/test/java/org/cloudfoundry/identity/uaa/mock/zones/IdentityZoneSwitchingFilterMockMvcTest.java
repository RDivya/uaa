package org.cloudfoundry.identity.uaa.mock.zones;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.RandomStringUtils;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupMember;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.cloudfoundry.identity.uaa.test.YamlServletProfileInitializerContextInitializer;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneCreationRequest;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneSwitchingFilter;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.XmlWebApplicationContext;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class IdentityZoneSwitchingFilterMockMvcTest {

    private static XmlWebApplicationContext webApplicationContext;
    private static MockMvc mockMvc;
    private static TestClient testClient;
    private static String identityToken;

    @BeforeClass
    public static void setUp() throws Exception {
        webApplicationContext = new XmlWebApplicationContext();
        webApplicationContext.setServletContext(new MockServletContext());
        new YamlServletProfileInitializerContextInitializer().initializeContext(webApplicationContext, "uaa.yml,login.yml");
        webApplicationContext.setConfigLocation("file:./src/main/webapp/WEB-INF/spring-servlet.xml");
        webApplicationContext.refresh();
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);

        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilter(springSecurityFilterChain)
                .build();

        testClient = new TestClient(mockMvc);
        identityToken = testClient.getClientCredentialsOAuthAccessToken(
                "identity",
                "identitysecret",
                "zones.create,zones.admin,clients.write");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        webApplicationContext.close();
    }

    @Test
    public void testSwitchingZones() throws Exception {

        final String zoneId = createZone(identityToken);

        // Using Identity Client, authenticate in originating Zone
        // - Create Client using X-Identity-Zone-Id header in new Zone
        final String clientId = UUID.randomUUID().toString();
        BaseClientDetails client = new BaseClientDetails(clientId, null, null, "client_credentials", null);
        client.setClientSecret("secret");
        mockMvc.perform(post("/oauth/clients")
                .header(IdentityZoneSwitchingFilter.HEADER, zoneId)
                .header("Authorization", "Bearer " + identityToken)
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(client)))
            .andExpect(status().isCreated());

        // Authenticate with new Client in new Zone
        mockMvc.perform(get("/oauth/token?grant_type=client_credentials")
                .header("Authorization", "Basic "
                        + new String(Base64.encodeBase64((client.getClientId() + ":" + client.getClientSecret()).getBytes())))
                .with(new RequestPostProcessor() {
                    @Override
                    public MockHttpServletRequest postProcessRequest(
                            MockHttpServletRequest request) {
                        request.setServerName(zoneId+".localhost");
                        return request;
                    }
                }))
                .andExpect(status().isOk());
    }

    @Test
    public void testSwitchingToNonExistentZone() throws Exception {
        createClientInOtherZone(identityToken, "i-do-not-exist");
    }

    @Test
    public void testSwitchingZonesWithoutAuthority() throws Exception {
        String identityTokenWithoutZonesAdmin = testClient.getClientCredentialsOAuthAccessToken(
                "identity",
                "identitysecret",
                "zones.create,clients.write");

        final String zoneId = createZone(identityTokenWithoutZonesAdmin);

        createClientInOtherZone(identityTokenWithoutZonesAdmin, zoneId);
    }

    @Test
    public void testSwitchingZonesWithAUser() throws Exception {
        final String zoneId = createZone(identityToken);

        String adminToken = testClient.getClientCredentialsOAuthAccessToken(
                "admin",
                "adminsecret",
                "scim.write");

        // Create a User
        String username = RandomStringUtils.randomAlphabetic(8) + "@example.com";
        ScimUser user = new ScimUser();
        user.setUserName(username);
        user.addEmail(username);
        user.setPassword("secret");
        user.setVerified(true);

        MvcResult userResult = mockMvc.perform(post("/Users")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(user)))
                .andExpect(status().isCreated()).andReturn();

        // Create the zones.<zone_id>.admin Group
        // Add User to the zones.<zone_id>.admin Group
        ScimGroup group = new ScimGroup("zones." + zoneId + ".admin");
        ScimUser createdUser = new ObjectMapper().readValue(userResult.getResponse().getContentAsString(), ScimUser.class);
        group.setMembers(Arrays.asList(new ScimGroupMember(createdUser.getId())));
        mockMvc.perform(post("/Groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(group)))
                .andExpect(status().isCreated());

        // Add User to the clients.create Group

        String userToken = testClient.getUserOAuthAccessToken("identity", "identitysecret", createdUser.getUserName(), "secret", "zones." + zoneId + ".admin");

        createClientInOtherZone(userToken, zoneId);
    }

    private String createZone(String accessToken) throws Exception {
        final String zoneId = UUID.randomUUID().toString();
        IdentityZone identityZone = MultitenancyFixture.identityZone(zoneId, zoneId);
        IdentityZoneCreationRequest creationRequest = new IdentityZoneCreationRequest();
        creationRequest.setIdentityZone(identityZone);

        mockMvc.perform(put("/identity-zones/" + zoneId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(creationRequest)))
                .andExpect(status().isCreated());
        return zoneId;
    }

    private void createClientInOtherZone(String accessToken, String zoneId) throws Exception {
        final String clientId = UUID.randomUUID().toString();
        BaseClientDetails client = new BaseClientDetails(clientId, null, null, "client_credentials", null);
        client.setClientSecret("secret");
        mockMvc.perform(post("/oauth/clients")
                .header(IdentityZoneSwitchingFilter.HEADER, zoneId)
                .header("Authorization", "Bearer " + accessToken)
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(client)))
                .andExpect(status().isNotFound());
    }
}
