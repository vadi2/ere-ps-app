package health.ere.ps.service.cardlink;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import health.ere.ps.service.health.check.CardlinkWebsocketCheck;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.xml.bind.DatatypeConverter;

@SuppressWarnings("unused")
@ClientEndpoint(configurator = AddJWTConfigurator.class)
public class CardlinkWebsocketClient {

    private static final Logger log = Logger.getLogger(CardlinkWebsocketClient.class.getName());
    
    URI endpointURI;
    Session userSession;

    private CardlinkWebsocketCheck cardlinkWebsocketCheck;

    public CardlinkWebsocketClient() {
    }

    public CardlinkWebsocketClient(URI endpointURI, CardlinkWebsocketCheck cardlinkWebsocketCheck) {
        try {
            this.cardlinkWebsocketCheck = cardlinkWebsocketCheck;
            this.cardlinkWebsocketCheck.setConnected(false);
            this.endpointURI = endpointURI;
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, endpointURI);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Callback hook for Connection open events.
     *
     * @param userSession the userSession which is opened.
     */
    @OnOpen
    public void onOpen(Session userSession) {
        log.info("opening websocket to "+endpointURI);
        this.userSession = userSession;
        cardlinkWebsocketCheck.setConnected(true);

        sendJson("registerSMCB", Map.of());
    }

    /**
     * Callback hook for Connection close events.
     *
     * @param userSession the userSession which is getting closed.
     * @param reason the reason for connection close
     */
    @OnClose
    public void onClose(Session userSession, CloseReason reason) {
        log.info("closing websocket "+endpointURI);
        cardlinkWebsocketCheck.setConnected(false);
    }

    /**
     * Callback hook for Message Events. This method will be invoked when a client send a message.
     *
     * @param message The text message
     */
    @OnMessage
    public void onMessage(String message) {
        log.info(message);
    }

    public void sendJson(String type, Map<String, Object> payloadMap) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (Map.Entry<String, ?> entry : payloadMap.entrySet()) {
            if (entry.getValue() instanceof Integer) {
                builder.add(entry.getKey(), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof Long) {
                builder.add(entry.getKey(), (Long) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                builder.add(entry.getKey(), (String) entry.getValue());
            } else if (entry.getValue() instanceof JsonArrayBuilder) {
                builder.add(entry.getKey(), (JsonArrayBuilder) entry.getValue());
            }
        }
        String payload = builder.build().toString();
        JsonObject jsonObject = Json.createObjectBuilder()
            .add("type", type)
            .add("payload", DatatypeConverter.printBase64Binary(payload.getBytes()))
            .build();
        JsonArray jsonArray = Json.createArrayBuilder().add(jsonObject).build();
        sendMessage(jsonArray.toString());
    }

    /**
     * Send a message.
     *
     * @param message String
     */
    public void sendMessage(String message) {
        log.info(message);
        try {
            this.userSession.getBasicRemote().sendText(message);
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not send websocket message to cardlink", e);
        }
    }
}
