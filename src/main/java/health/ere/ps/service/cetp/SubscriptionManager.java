package health.ere.ps.service.cetp;

import de.gematik.ws.conn.connectorcommon.v5.Status;
import de.gematik.ws.conn.eventservice.v7.SubscriptionType;
import de.gematik.ws.conn.eventservice.wsdl.v7.FaultMessage;
import de.gematik.ws.tel.error.v2.Error;
import health.ere.ps.config.AppConfig;
import health.ere.ps.config.RuntimeConfig;
import health.ere.ps.config.UserConfig;
import health.ere.ps.jmx.PsMXBeanManager;
import health.ere.ps.jmx.SubscriptionsMXBean;
import health.ere.ps.jmx.SubscriptionsMXBeanImpl;
import health.ere.ps.retry.Retrier;
import health.ere.ps.service.cetp.config.KonnektorConfig;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.xml.ws.Holder;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static health.ere.ps.service.cetp.config.KonnektorConfig.FAILED;
import static health.ere.ps.service.cetp.config.KonnektorConfig.saveFile;
import static health.ere.ps.utils.Utils.printException;

@ApplicationScoped
public class SubscriptionManager {

    private final static Logger log = Logger.getLogger(SubscriptionManager.class.getName());

    private final Map<String, KonnektorConfig> hostToKonnektorConfig = new ConcurrentHashMap<>();

    @Inject
    AppConfig appConfig;

    @Inject
    UserConfig userConfig;

    @Inject
    KonnektorClient konnektorClient;

    @ConfigProperty(name = "ere.per.konnektor.config.folder")
    String configFolder;

    private volatile boolean configsLoaded = false;

    private ExecutorService threadPool;

    void onStart(@Observes StartupEvent ev) {
        loadKonnektorConfigs();
        threadPool = Executors.newFixedThreadPool(hostToKonnektorConfig.size());
        SubscriptionsMXBeanImpl subscriptionsMXBean = new SubscriptionsMXBeanImpl(hostToKonnektorConfig.size());
        PsMXBeanManager.registerMXBean(SubscriptionsMXBean.OBJECT_NAME, subscriptionsMXBean);
    }

    @Scheduled(
        every = "${quarkus.scheduler.cetp.subscription.renewal.interval}",
        delay = 5,
        delayUnit = TimeUnit.SECONDS,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void subscriptionsMaintenance() {
        String defaultSender = appConfig.getEventToHost().orElse(null);
        if (defaultSender == null) {
            log.log(Level.WARNING, "You did not set 'cetp.subscriptions.event-to-host' property. Will have no fallback if Konnektor is not found to be in the same subnet");
        }
        List<Integer> retryMillis = List.of(200);
        int intervalMs = appConfig.getSubscriptionsMaintenanceRetryIntervalMs();
        List<Future<Boolean>> futures = getKonnektorConfigs(null).stream().map(kc -> threadPool.submit(() -> {
            Inet4Address meInSameSubnet = LocalAddressInSameSubnetFinder.findLocalIPinSameSubnet(konnektorToIp4(kc.getHost()));
            String eventToHost = (meInSameSubnet != null) ? meInSameSubnet.getHostAddress() : defaultSender;
            if (eventToHost == null) {
                log.log(Level.INFO, "Can't maintain subscription. Don't know my own address to tell konnektor about it");
                return null;
            }
            Boolean result = Retrier.callAndRetry(
                retryMillis,
                intervalMs,
                () -> renewSubscriptions(eventToHost, kc),
                bool -> bool
            );
            if (!result) {
                String msg = String.format(
                    "[%s] Subscriptions maintenance is failed within %d ms retry", kc.getHost(), intervalMs);
                log.warning(msg);
            }
            return result;
        })).toList();
        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (Throwable e) {
                log.log(Level.SEVERE, "Subscriptions maintenance error", e);
            }
        }
    }

    public boolean renewSubscriptions(String eventToHost, KonnektorConfig kc) {
        Semaphore semaphore = kc.getSemaphore();
        if (semaphore.tryAcquire()) {
            try {
                RuntimeConfig runtimeConfig = modifyRuntimeConfig(null, kc);
                String cetpHost = "cetp://" + eventToHost + ":" + kc.getPort();
                List<SubscriptionType> subscriptions = konnektorClient.getSubscriptions(runtimeConfig)
                    .stream().filter(st -> st.getEventTo().contains(eventToHost)).toList();

                Holder<String> resultHolder = new Holder<>();
                if (subscriptions.isEmpty()) {
                    return subscribe(kc, runtimeConfig, cetpHost, resultHolder);
                } else {
                    Optional<SubscriptionType> newestOpt = subscriptions.stream().max(
                        Comparator.comparing(o -> o.getTerminationTime().toGregorianCalendar().getTime())
                    );
                    SubscriptionType newest = newestOpt.get();
                    Date expireDate = newest.getTerminationTime().toGregorianCalendar().getTime();
                    Date now = new Date();
                    boolean expired = now.getTime() >= expireDate.getTime();

                    int periodSeconds = appConfig.getForceResubscribePeriodSeconds();
                    boolean forceSubscribe = kc.getSubscriptionTime().plusSeconds(periodSeconds).isBefore(OffsetDateTime.now());
                    if (expired || forceSubscribe) {
                        boolean subscribed = subscribe(kc, runtimeConfig, cetpHost, resultHolder);
                        if (forceSubscribe && subscribed) {
                            log.info(String.format("Force subscribed to %s: %s", kc.getHost(), resultHolder.value));
                        }
                        // all are expired, drop them
                        boolean dropped = drop(runtimeConfig, subscriptions).isEmpty();
                        return subscribed && dropped;
                    } else {
                        boolean renewed = renew(runtimeConfig, kc, newest, now, expireDate);
                        List<SubscriptionType> olderSubscriptions = subscriptions.stream()
                            .filter(st -> !st.getSubscriptionID().equals(newest.getSubscriptionID()))
                            .toList();
                        boolean dropped = drop(runtimeConfig, olderSubscriptions).isEmpty();
                        return renewed && dropped;
                    }
                }
            } catch (FaultMessage fm) {
                log.log(Level.SEVERE, String.format("[%s] Subscriptions maintenance error", kc.getHost()), fm);
                return false;
            } finally {
                semaphore.release();
            }
        } else {
            log.warning(String.format("[%s] Subscription maintenance is in progress, try later", kc.getHost()));
            return true;
        }
    }

    public boolean renew(
        RuntimeConfig runtimeConfig,
        KonnektorConfig konnektorConfig,
        SubscriptionType type,
        Date now,
        Date expireDate
    ) throws FaultMessage {
        String newestSubscriptionId = type.getSubscriptionID();
        boolean sameSubscription = newestSubscriptionId.equals(konnektorConfig.getSubscriptionId());
        if (!sameSubscription) {
            String msg = String.format(
                "Found subscriptions discrepancy: CONFIG=%s, REAL=%s, REAL_EXPIRATION=%s, updating config",
                konnektorConfig.getSubscriptionId(),
                newestSubscriptionId,
                expireDate
            );
            log.warning(msg);
            saveFile(konnektorConfig, newestSubscriptionId, null);
        }
        int safePeriod = appConfig.getCetpSubscriptionsRenewalSafePeriodMs();
        if (now.getTime() + safePeriod >= expireDate.getTime()) {
            String msg = String.format(
                "Subscription %s is about to expire after %d seconds, renew", newestSubscriptionId, safePeriod / 1000
            );
            log.info(msg);
            Pair<Status, String> pair = konnektorClient.renewSubscription(runtimeConfig, newestSubscriptionId);
            Error error = pair.getKey().getError();
            String renewedSubscriptionId = pair.getValue();
            if (error == null && !renewedSubscriptionId.equals(newestSubscriptionId)) {
                msg = String.format(
                    "Subscription ID has changed after renew: OLD=%s, NEW=%s, updating config",
                    newestSubscriptionId,
                    renewedSubscriptionId
                );
                log.fine(msg);
                saveFile(konnektorConfig, renewedSubscriptionId, null);
            }
            return error == null;
        } else {
            return true;
        }
    }

    private String printError(Error error) {
        return String.format(
            "[%s] Gematik ERROR at %s: %s ",
            error.getMessageID(),
            error.getTimestamp(),
            error.getTrace().stream().map(t ->
                String.format("Code=%s ErrorText=%s Detail=%s", t.getCode(), t.getErrorText(), t.getDetail().getValue())
            ).collect(Collectors.joining(", "))
        );
    }

    public List<String> drop(
        RuntimeConfig runtimeConfig,
        List<SubscriptionType> subscriptions
    ) throws FaultMessage {
        return subscriptions.stream().map(s -> {
            try {
                Status status = konnektorClient.unsubscribeFromKonnektor(runtimeConfig, s.getSubscriptionID(), null, false);
                Error error = status.getError();
                if (error == null) {
                    return Pair.of(true, s.getSubscriptionID());
                } else {
                    String msg = String.format("Failed to unsubscribe %s", s.getSubscriptionID());
                    log.log(Level.SEVERE, msg, printError(error));
                    return Pair.of(false, s.getSubscriptionID());
                }
            } catch (FaultMessage f) {
                String msg = String.format("Failed to unsubscribe %s", s.getSubscriptionID());
                log.log(Level.SEVERE, msg, f);
                return Pair.of(false, s.getSubscriptionID());
            }
        }).filter(p -> !p.getKey()).map(Pair::getValue).toList();
    }

    public void setConfigFolder(String configFolder) {
        this.configFolder = configFolder;
    }

    public Collection<KonnektorConfig> getKonnektorConfigs(String host) {
        if (!configsLoaded) {
            loadKonnektorConfigs();
        }
        return host == null
            ? hostToKonnektorConfig.values()
            : hostToKonnektorConfig.entrySet().stream().filter(entry -> entry.getKey().contains(host)).map(Map.Entry::getValue).toList();
    }

    private RuntimeConfig modifyRuntimeConfig(RuntimeConfig runtimeConfig, KonnektorConfig konnektorConfig) {
        if (runtimeConfig == null) {
            return new RuntimeConfig(konnektorConfig.getUserConfigurations());
        } else {
            runtimeConfig.updateProperties(konnektorConfig.getUserConfigurations());
            return runtimeConfig;
        }
    }

    public List<String> manage(
        RuntimeConfig runtimeConfig,
        String host,
        String eventToHost,
        boolean forceCetp,
        boolean subscribe
    ) {
        Collection<KonnektorConfig> konnektorConfigs = getKonnektorConfigs(host);
        List<String> statuses = konnektorConfigs.stream().map(kc -> {
                Semaphore semaphore = kc.getSemaphore();
                if (semaphore.tryAcquire()) {
                    try {
                        String cetpHost = "cetp://" + eventToHost + ":" + kc.getPort();
                        return process(kc, modifyRuntimeConfig(runtimeConfig, kc), cetpHost, forceCetp, subscribe);
                    } finally {
                        semaphore.release();
                    }
                } else {
                    try {
                        String h = host == null ? kc.getHost() : host;
                        String s = subscribe ? "subscription" : "unsubscription";
                        return String.format("[%s] Host %s is in progress, try later", h, s);
                    } catch (Exception e) {
                        return e.getMessage();
                    }
                }
            })
            .filter(Objects::nonNull)
            .toList();

        if (statuses.isEmpty()) {
            return List.of(String.format("No configuration is found for the given host: %s", host));
        }
        return statuses;
    }

    private boolean subscribe(
        KonnektorConfig konnektorConfig,
        RuntimeConfig runtimeConfig,
        String cetpHost,
        Holder<String> resultHolder
    ) throws FaultMessage {
        Triple<Status, String, String> triple = konnektorClient.subscribeToKonnektor(runtimeConfig, cetpHost);
        Status status = triple.getLeft();
        Error error = status.getError();
        if (error == null) {
            String newSubscriptionId = triple.getMiddle();
            resultHolder.value = status.getResult() + " " + newSubscriptionId + " " + triple.getRight();
            saveFile(konnektorConfig, newSubscriptionId, null);
            log.info(String.format("Subscribe status for subscriptionId=%s: %s", newSubscriptionId, status.getResult()));
            return true;
        } else {
            String statusResult = printError(error);
            resultHolder.value = statusResult;
            String subscriptionId = konnektorConfig.getSubscriptionId();
            String fileName = subscriptionId != null && subscriptionId.startsWith(FAILED)
                ? subscriptionId
                : String.format("%s-%s", FAILED, subscriptionId);

            saveFile(konnektorConfig, fileName, statusResult);
            log.log(Level.WARNING, String.format("Could not subscribe -> %s", statusResult));
            return false;
        }
    }

    private String process(
        KonnektorConfig konnektorConfig,
        RuntimeConfig runtimeConfig,
        String cetpHost,
        boolean forceCetp,
        boolean subscribe
    ) {
        String subscriptionId = konnektorConfig.getSubscriptionId();
        String failedUnsubscriptionFileName = subscriptionId != null && subscriptionId.startsWith(FAILED)
            ? subscriptionId
            : String.format("%s-unsubscription-%s", FAILED, subscriptionId);

        String failedSubscriptionFileName = String.format("%s-subscription", FAILED);

        String statusResult;
        boolean unsubscribed = false;
        try {
            Status status = konnektorClient.unsubscribeFromKonnektor(runtimeConfig, subscriptionId, cetpHost, forceCetp);
            Error error = status.getError();
            if (error == null) {
                unsubscribed = true;
                statusResult = status.getResult();
                log.info(String.format("Unsubscribe status for subscriptionId=%s: %s", subscriptionId, statusResult));
                if (subscribe) {
                    Holder<String> resultHolder = new Holder<>();
                    subscribe(konnektorConfig, runtimeConfig, cetpHost, resultHolder);
                    statusResult = resultHolder.value;
                } else {
                    KonnektorConfig.cleanUp(konnektorConfig.getFolder(), null);
                }
            } else {
                statusResult = printError(error);
                saveFile(konnektorConfig, failedUnsubscriptionFileName, statusResult);
                String msg = String.format("Could not unsubscribe from %s -> %s", subscriptionId, printError(error));
                log.log(Level.WARNING, msg);
            }
        } catch (Exception e) {
            String fileName = unsubscribed ? failedSubscriptionFileName : failedUnsubscriptionFileName;
            saveFile(konnektorConfig, fileName, printException(e));
            log.log(Level.WARNING, "Error: " + fileName, e);
            statusResult = e.getMessage();
        }
        return statusResult;
    }

    private String getKonnectorHost(KonnektorConfig config) {
        String konnectorHost = config.getHost();
        return konnectorHost == null ? appConfig.getKonnectorHost() : konnectorHost;
    }

    private void loadKonnektorConfigs() {
        List<KonnektorConfig> configs = new ArrayList<>();
        var konnektorConfigFolder = new File(configFolder);
        if (konnektorConfigFolder.exists()) {
            configs = KonnektorConfig.readFromFolder(konnektorConfigFolder.getAbsolutePath());
        }
        if (configs.isEmpty()) {
            configs.add(
                new KonnektorConfig(
                    konnektorConfigFolder,
                    CETPServer.PORT,
                    userConfig.getConfigurations(),
                    appConfig.getCardLinkURI()
                )
            );
        }
        configs.forEach(config -> hostToKonnektorConfig.put(getKonnectorHost(config), config));
        configsLoaded = true;
    }

    private Inet4Address konnektorToIp4(String host) {
        try {
            InetAddress inetAddress = InetAddress.getByName(host);
            if (inetAddress instanceof Inet4Address addr) {
                return addr;
            } else {
                return null;
            }
        } catch (UnknownHostException e) {
            return null;
        }
    }
}