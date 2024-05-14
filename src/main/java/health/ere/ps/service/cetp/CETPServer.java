package health.ere.ps.service.cetp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;

import health.ere.ps.config.AppConfig;
import health.ere.ps.config.UserConfig;
import health.ere.ps.service.cardlink.CardlinkWebsocketClient;
import health.ere.ps.service.cetp.codec.CETPDecoder;
import health.ere.ps.service.cetp.config.KonnektorConfig;
import health.ere.ps.service.common.security.SecretsManagerService;
import health.ere.ps.service.common.security.SecretsManagerService.KeyStoreType;
import health.ere.ps.service.gematik.PharmacyService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CETPServer {
    public static final int PORT = 8585;
    private static Logger log = Logger.getLogger(CETPServer.class.getName());

    List<EventLoopGroup> bossGroups = new ArrayList<>(); // (1)
    List<EventLoopGroup> workerGroups = new ArrayList<>();

    @Inject
    PharmacyService pharmacyService;

    @Inject
    SecretsManagerService secretsManagerService;

    @Inject
    AppConfig appConfig;

    @Inject
    UserConfig userConfig;

    void onStart(@Observes StartupEvent ev) {               
        log.info("Running CETP Server on port "+PORT);
        run();

    }

    void onShutdown(@Observes ShutdownEvent ev) {               
        log.info("Shutdown CETP Server on port "+PORT);
        if(workerGroups != null) {
            workerGroups.stream().filter(Objects::nonNull).forEach(c -> c.shutdownGracefully());
        }
        if(bossGroups != null) {
            bossGroups.stream().filter(Objects::nonNull).forEach(c -> c.shutdownGracefully());
        }
    }

    private URI getCardLinkURI() {
        URI cardLinkURI = null;
        try {
            String cardLinkServer = appConfig
                .getCardLinkServer()
                .orElse("wss://cardlink.service-health.de:8444/websocket/80276003650110006580-20230112");
            
            log.info("Starting websocket connection to: " + cardLinkServer);
            cardLinkURI = new URI(cardLinkServer);
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, "Could not connect to card link", e);
        }
        return cardLinkURI;
    }

    public void run() {
        List<KonnektorConfig> configs = new ArrayList<>();
        var konnektorConfig = new File("config/konnektoren");
        if(konnektorConfig.exists()) {
            configs = KonnektorConfig.readFromFolder(konnektorConfig.getAbsolutePath());
        } else {
            configs.add(new KonnektorConfig(PORT, userConfig.getConfigurations(), getCardLinkURI()));
        }
        
        for(KonnektorConfig config : configs) {
            runServer(config);
        }

    }

    private void runServer(KonnektorConfig config) {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(); // (1)
        bossGroups.add(bossGroup);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        workerGroups.add(workerGroup);
        try {
            ServerBootstrap b = new ServerBootstrap(); // (2)
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class) // (3)
             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new ChannelInitializer<SocketChannel>() { // (4)
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                    try {

                        SslContext sslContext = SslContextBuilder
                            .forServer(getKeyFactoryManager(config))
                            .clientAuth(ClientAuth.NONE)
                            .build();

                        ch.pipeline()
                            .addLast("ssl", sslContext.newHandler(ch.alloc()))
                            .addLast(new CETPDecoder(config.getUserConfigurations()))
                            .addLast(new CETPServerHandler(pharmacyService, new CardlinkWebsocketClient(config.getCardlinkEndpoint())));
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Failed to create SSL context", e);
                    }
                 }

             })
             .option(ChannelOption.SO_BACKLOG, 128)          // (5)
             .childOption(ChannelOption.SO_KEEPALIVE, true); // (6)
    
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(config.getPort()).sync(); // (7)
    
            // Wait until the server socket is closed.
            // In this example, this does not happen, but you can do that to gracefully
            // shut down your server.
            f.channel().closeFuture(); //.sync();
        } catch (InterruptedException e) {
            log.log(Level.WARNING, "CETP Server interrupted", e);
        }
    }


    private KeyManagerFactory getKeyFactoryManager(KonnektorConfig config) {
        if(config.getUserConfigurations().getClientCertificate() == null) {
            return secretsManagerService.getKeyManagerFactory();
        } else {
            String connectorTlsCertAuthStorePwd = config.getUserConfigurations().getClientCertificatePassword();
            byte[] clientCertificateBytes = SecretsManagerService.getClientCertificateBytes(config.getUserConfigurations());
            try (ByteArrayInputStream certificateInputStream = new ByteArrayInputStream(clientCertificateBytes)) {
                KeyStore ks = KeyStore.getInstance(KeyStoreType.PKCS12.getKeyStoreType());
                ks.load(certificateInputStream, connectorTlsCertAuthStorePwd.toCharArray());

                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(ks, connectorTlsCertAuthStorePwd.toCharArray());
                return keyManagerFactory;
            } catch (UnrecoverableKeyException | KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
                log.log(Level.SEVERE, "Could not create keyManagerFactory", e);
            }
        }
        return null;
    }
}
