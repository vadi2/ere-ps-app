package health.ere.ps.service.cetp.config;

import health.ere.ps.model.config.UserConfigurations;
import health.ere.ps.service.cetp.SubscriptionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class KonnektorConfigTest {

    @Test
    void testGenerateKonnektorConfig() {
        FSConfigService configService = new FSConfigService();
        configService.configFolder = "src/test/resources/config/konnektoren/";

        var configs = configService.loadConfigs();
        assertEquals(3, configs.size());
        Optional<KonnektorConfig> kc42 = configs.values().stream().filter(kc -> kc.getHost().contains("192.168.178.42")).findFirst();
        Optional<KonnektorConfig> kc113 = configs.values().stream().filter(kc -> kc.getHost().contains("192.168.178.113")).findFirst();
        Optional<KonnektorConfig> kc108 = configs.values().stream().filter(kc -> kc.getHost().contains("192.168.178.108")).findFirst();
        assertTrue(kc42.isPresent());
        assertTrue(kc113.isPresent());
        assertTrue(kc108.isPresent());
        
        assertEquals(kc42.get().getCetpPort(), 8585);
        assertEquals(kc113.get().getCetpPort(), 8586);
        assertEquals(kc108.get().getCetpPort(), 8587);

        assertEquals(kc42.get().getCardlinkEndpoint().toString(), "wss://cardlink.service-health.de:8444/websocket/80276883662000004801-20220128");
        assertEquals(kc108.get().getCardlinkEndpoint().toString(), "wss://cardlink.service-health.de:8444/websocket/80276883580000040142-20201102");
    }

    @Test
    public void twoConfigsWithSameKonnectorAreLoaded() {
        FSConfigService configService = spy(new FSConfigService());
        configService.configFolder = "config/konnektoren";

        List<KonnektorConfig> sameKonnektorConfigs = new ArrayList<>();
        Properties sameKonnektorProperties = new Properties();
        String konnektorHost = "192.168.178.42";
        sameKonnektorProperties.put("connectorBaseURL", "https\\://" + konnektorHost);
        sameKonnektorConfigs.add(new KonnektorConfig(null, 8585, new UserConfigurations(sameKonnektorProperties), null));
        sameKonnektorConfigs.add(new KonnektorConfig(null, 8586, new UserConfigurations(sameKonnektorProperties), null));

        doReturn(sameKonnektorConfigs).when(configService).readFromPath(any());

        SubscriptionManager subscriptionManager = new SubscriptionManager(null, null, configService);
        subscriptionManager.onStart(null);
        Collection<KonnektorConfig> konnektorConfigs = subscriptionManager.getKonnektorConfigs(konnektorHost);
        assertEquals(sameKonnektorConfigs.size(), konnektorConfigs.size());
    }
}
