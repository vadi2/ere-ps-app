package health.ere.ps.service.cetp;

import de.gematik.ws.conn.connectorcommon.v5.Status;
import de.gematik.ws.conn.connectorcontext.v2.ContextType;
import de.gematik.ws.conn.eventservice.v7.GetSubscriptionResponse;
import de.gematik.ws.conn.eventservice.v7.RenewSubscriptionsResponse;
import de.gematik.ws.conn.eventservice.v7.SubscriptionRenewal;
import de.gematik.ws.conn.eventservice.v7.SubscriptionType;
import de.gematik.ws.conn.eventservice.wsdl.v7.EventServicePortType;
import de.gematik.ws.tel.error.v2.Error;
import health.ere.ps.profile.RUTestProfile;
import health.ere.ps.service.cetp.config.KonnektorConfig;
import health.ere.ps.service.connector.provider.MultiConnectorServicesProvider;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.xml.ws.Holder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import static health.ere.ps.utils.Utils.getHostFromNetworkInterfaces;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "OptionalGetWithoutIsPresent"})
@QuarkusTest
@TestProfile(RUTestProfile.class)
public class SubscriptionRenewalTest {

    private static String uuid;

    private final String eventToHost = getHostFromNetworkInterfaces().orElse("127.0.0.1");

    @Inject
    SubscriptionManager subscriptionManager;

    @Inject
    MultiConnectorServicesProvider multiConnectorServicesProvider;

    @AfterEach
    public void afterAll() {
        QuarkusMock.installMockForType(multiConnectorServicesProvider, MultiConnectorServicesProvider.class);
    }

    @Test
    public void subscribeWasCalledForAbsentSubscriptions() throws Exception {
        subscriptionManager.setConfigFolder(SubscriptionManager.CONFIG_KONNEKTOREN_FOLDER);
        Status subscribeStatus = prepareStatus("Subscribed", null);
        Status renewalStatus = prepareStatus("Renewed", null);
        Status unsubscribeStatus = prepareStatus("Unsubscribed", null);
        int subscribedCount = 0;
        EventServicePortType eventService = setupRenewal(subscribedCount, 5000, subscribeStatus, renewalStatus, unsubscribeStatus);

        KonnektorConfig kc = subscriptionManager.getKonnektorConfigs("192.168.178.42").stream().findFirst().get();
        boolean result = subscriptionManager.renewSubscriptions(eventToHost, kc);
        assertTrue(result);
        verify(eventService).subscribe(any(), any(), any(), any(), any());
        verify(eventService, never()).renewSubscriptions(any(), any(), any(), any());
        verify(eventService, never()).unsubscribe(any(), any(), any());
    }

    @Test
    public void renewAndUnsubscribesWereCalledForExpiringSubscriptions() throws Exception {
        subscriptionManager.setConfigFolder(SubscriptionManager.CONFIG_KONNEKTOREN_FOLDER);
        Status subscribeStatus = prepareStatus("Subscribed", null);
        Status renewalStatus = prepareStatus("Renewed", null);
        Status unsubscribeStatus = prepareStatus("Unsubscribed", null);
        int subscribedCount = 2;
        EventServicePortType eventService = setupRenewal(subscribedCount, 5000, subscribeStatus, renewalStatus, unsubscribeStatus);

        KonnektorConfig kc = subscriptionManager.getKonnektorConfigs("192.168.178.42").stream().findFirst().get();
        boolean result = subscriptionManager.renewSubscriptions(eventToHost, kc);
        assertTrue(result);
        verify(eventService, never()).subscribe(any(), any(), any(), any(), any());
        verify(eventService).renewSubscriptions(any(), any(), any(), any());
        verify(eventService, times(subscribedCount - 1)).unsubscribe(any(), any(), any());
    }

    @Test
    public void subscribeAndUnsubscribesWereCalledForExpiredSubscriptions() throws Exception {
        subscriptionManager.setConfigFolder(SubscriptionManager.CONFIG_KONNEKTOREN_FOLDER);
        Status subscribeStatus = prepareStatus("Subscribed", null);
        Status renewalStatus = prepareStatus("Renewed", null);
        Status unsubscribeStatus = prepareStatus("Unsubscribed", null);
        int subscribedCount = 2;
        EventServicePortType eventService = setupRenewal(subscribedCount, 0, subscribeStatus, renewalStatus, unsubscribeStatus);

        KonnektorConfig kc = subscriptionManager.getKonnektorConfigs("192.168.178.42").stream().findFirst().get();
        boolean result = subscriptionManager.renewSubscriptions(eventToHost, kc);
        assertTrue(result);
        verify(eventService).subscribe(any(), any(), any(), any(), any());
        verify(eventService, never()).renewSubscriptions(any(), any(), any(), any());
        verify(eventService, times(subscribedCount)).unsubscribe(any(), any(), any());
    }

    @Test
    public void subscribeAndUnsubscribesFailedForExpiredSubscriptions() throws Exception {
        subscriptionManager.setConfigFolder(SubscriptionManager.CONFIG_KONNEKTOREN_FOLDER);
        Error error = new Error();
        error.setMessageID(UUID.randomUUID().toString());
        error.setTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar("2024-06-30T14:30:00"));
        Error.Trace trace = new Error.Trace();
        trace.setCode(BigInteger.valueOf(847529L));
        trace.setErrorText("ErrorText");
        Error.Trace.Detail detail = new Error.Trace.Detail();
        detail.setValue("Detail");
        trace.setDetail(detail);
        error.getTrace().add(trace);
        Status subscribeStatus = prepareStatus("Subscribed", error);
        Status renewalStatus = prepareStatus("Renewed", error);
        Status unsubscribeStatus = prepareStatus("Unsubscribed", error);
        int subscribedCount = 2;
        EventServicePortType eventService = setupRenewal(subscribedCount, 0, subscribeStatus, renewalStatus, unsubscribeStatus);

        KonnektorConfig kc = subscriptionManager.getKonnektorConfigs("192.168.178.42").stream().findFirst().get();
        boolean result = subscriptionManager.renewSubscriptions(eventToHost, kc);
        assertFalse(result);
        verify(eventService).subscribe(any(), any(), any(), any(), any());
        verify(eventService, never()).renewSubscriptions(any(), any(), any(), any());
        verify(eventService, times(subscribedCount)).unsubscribe(any(), any(), any());
    }

    private Status prepareStatus(String result, de.gematik.ws.tel.error.v2.Error error) {
        Status status = new Status();
        status.setResult(result);
        status.setError(error);
        return status;
    }

    private EventServicePortType setupRenewal(
        int subscribedCount,
        int expireInMs,
        Status subscribeStatus,
        Status renewalStatus,
        Status unsubscribeStatus
    ) throws Exception {
        EventServicePortType eventService = mock(EventServicePortType.class);
        when(eventService.unsubscribe(any(), any(), any())).thenReturn(unsubscribeStatus);

        GetSubscriptionResponse subscriptionResponse = new GetSubscriptionResponse();
        GetSubscriptionResponse.Subscriptions subscriptions = new GetSubscriptionResponse.Subscriptions();
        List<SubscriptionType> list = subscriptions.getSubscription();

        for (int i = 0; i < subscribedCount; i++) {
            SubscriptionType type = new SubscriptionType();
            type.setSubscriptionID(UUID.randomUUID().toString());
            type.setEventTo(eventToHost);
            long existingTerminationMillis = new Date().getTime() + expireInMs;
            GregorianCalendar cExisting = new GregorianCalendar();
            cExisting.setTimeInMillis(existingTerminationMillis);
            type.setTerminationTime(DatatypeFactory.newInstance().newXMLGregorianCalendar(cExisting));
            list.add(type);
        }
        subscriptionResponse.setSubscriptions(subscriptions);
        when(eventService.getSubscription(any())).thenReturn(subscriptionResponse);

        Mockito.doAnswer((Answer<Void>) invocation -> {
            final Object[] args = invocation.getArguments();

            Holder<Status> status = (Holder<Status>) args[2];
            status.value = subscribeStatus;

            Holder<String> subscriptionId = (Holder<String>) args[3];
            uuid = UUID.randomUUID().toString();
            subscriptionId.value = uuid;

            Holder<XMLGregorianCalendar> terminationTime = (Holder<XMLGregorianCalendar>) args[4];

            GregorianCalendar c = new GregorianCalendar();
            c.setTime(new Date());
            terminationTime.value = DatatypeFactory.newInstance().newXMLGregorianCalendar(c);

            return null;
        }).when(eventService).subscribe(any(), any(), any(), any(), any());

        Mockito.doAnswer((Answer<Void>) invocation -> {
            final Object[] args = invocation.getArguments();

            Holder<Status> statusHolder = (Holder<Status>) args[2];
            statusHolder.value = renewalStatus;

            Holder<RenewSubscriptionsResponse.SubscribeRenewals> renewalHolder = (Holder<RenewSubscriptionsResponse.SubscribeRenewals>) args[3];
            RenewSubscriptionsResponse.SubscribeRenewals subscribeRenewals = new RenewSubscriptionsResponse.SubscribeRenewals();
            SubscriptionRenewal subscriptionRenewal = new SubscriptionRenewal();
            uuid = UUID.randomUUID().toString();
            subscriptionRenewal.setSubscriptionID(uuid);
            subscribeRenewals.getSubscriptionRenewal().add(subscriptionRenewal);
            renewalHolder.value = subscribeRenewals;

            return null;
        }).when(eventService).renewSubscriptions(any(), any(), any(), any());


        MultiConnectorServicesProvider connectorServicesProvider = mock(MultiConnectorServicesProvider.class);
        when(connectorServicesProvider.getContextType(any())).thenReturn(new ContextType());
        when(connectorServicesProvider.getEventServicePortType(any())).thenReturn(eventService);

        QuarkusMock.installMockForType(connectorServicesProvider, MultiConnectorServicesProvider.class);

        return eventService;
    }
}
