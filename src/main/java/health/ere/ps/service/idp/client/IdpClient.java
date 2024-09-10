package health.ere.ps.service.idp.client;

import static health.ere.ps.model.idp.client.brainPoolExtension.BrainpoolAlgorithmSuiteIdentifiers.BRAINPOOL256_USING_SHA256;
import static health.ere.ps.model.idp.client.field.ClaimName.NESTED_JWT;
import static health.ere.ps.model.idp.client.field.IdpScope.EREZEPT;
import static health.ere.ps.model.idp.client.field.IdpScope.EREZEPTDEV;
import static health.ere.ps.model.idp.client.field.IdpScope.OPENID;
import static org.jose4j.jws.AlgorithmIdentifiers.RSA_PSS_USING_SHA256;

import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import com.diffplug.common.base.Errors;
import com.diffplug.common.base.Throwing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import health.ere.ps.config.RuntimeConfig;
import health.ere.ps.exception.idp.IdpClientException;
import health.ere.ps.exception.idp.IdpException;
import health.ere.ps.exception.idp.IdpJoseException;
import health.ere.ps.model.idp.client.AuthenticationRequest;
import health.ere.ps.model.idp.client.AuthenticationResponse;
import health.ere.ps.model.idp.client.AuthorizationRequest;
import health.ere.ps.model.idp.client.AuthorizationResponse;
import health.ere.ps.model.idp.client.DiscoveryDocumentResponse;
import health.ere.ps.model.idp.client.IdpTokenResult;
import health.ere.ps.model.idp.client.TokenRequest;
import health.ere.ps.model.idp.client.field.CodeChallengeMethod;
import health.ere.ps.model.idp.client.field.IdpScope;
import health.ere.ps.model.idp.client.token.IdpJwe;
import health.ere.ps.model.idp.client.token.JsonWebToken;
import health.ere.ps.model.idp.crypto.PkiIdentity;
import health.ere.ps.service.connector.auth.SmcbAuthenticatorService;
import health.ere.ps.service.idp.client.authentication.UriUtils;
import health.ere.ps.service.idp.crypto.KeyAnalysis;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class IdpClient implements IIdpClient {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Set<IdpScope> scopes = Set.of(OPENID, EREZEPT);
    private final Set<IdpScope> scopesRudev = Set.of(OPENID, EREZEPTDEV);


    @ConfigProperty(name = "ere.workflow-service.prescription.server.url")
    String prescriptionServiceURL;

    private final CodeChallengeMethod codeChallengeMethod = CodeChallengeMethod.S256;

    @Inject
    AuthenticatorClient authenticatorClient;

    @Inject
    SmcbAuthenticatorService smcbAuthenticatorService;

    private static final Logger logger = Logger.getLogger(IdpClient.class.getName());

    private String clientId;
    private String redirectUrl;
    private String discoveryDocumentUrl;
    private boolean shouldVerifyState;
    private DiscoveryDocumentResponse discoveryDocumentResponse;

    public IdpClient() {
    }

    public void init(String clientId, String redirectUrl, String discoveryDocumentUrl, boolean shouldVerifyState) {
        this.clientId = clientId;
        this.redirectUrl = redirectUrl;
        this.discoveryDocumentUrl = discoveryDocumentUrl;
        this.shouldVerifyState = shouldVerifyState;
    }

    private String signServerChallenge(
        final String challengeToSign,
        final X509Certificate certificate,
        final Function<Pair<String, String>, String> contentSigner
    ) throws IdpJoseException {
        final JsonWebSignature jsonWebSignature = getJsonWebSignature(challengeToSign, certificate);

        PublicKey idpPublicKey = discoveryDocumentResponse.getIdpEnc();
        JsonWebToken jwt = new JsonWebToken(
            contentSigner.apply(Pair.of(
                jsonWebSignature.getHeaders().getEncodedHeader(),
                jsonWebSignature.getEncodedPayload())
            )
        );

        jwt.getHeaderClaims().remove("alg");
        jwt.getHeaderClaims().put("alg", BRAINPOOL256_USING_SHA256);

        return jwt.encrypt(idpPublicKey).getRawString();
    }

    @NotNull
    private static JsonWebSignature getJsonWebSignature(String challengeToSign, X509Certificate certificate) {
        final JwtClaims claims = new JwtClaims();
        claims.setClaim(NESTED_JWT.getJoseName(), challengeToSign);

        final JsonWebSignature jsonWebSignature = new JsonWebSignature();
        jsonWebSignature.setPayload(claims.toJson());
        jsonWebSignature.setHeader("typ", "JWT");
        jsonWebSignature.setHeader("cty", "NJWT");

        if (KeyAnalysis.isEcKey(certificate.getPublicKey())) {
            jsonWebSignature.setAlgorithmHeaderValue(BRAINPOOL256_USING_SHA256);
        } else {
            jsonWebSignature.setAlgorithmHeaderValue(RSA_PSS_USING_SHA256);
        }
        return jsonWebSignature;
    }

    public IdpTokenResult login(final PkiIdentity idpIdentity) throws IdpException, IdpClientException, IdpJoseException {
        assertThatIdpIdentityIsValid(idpIdentity);

        return login(idpIdentity.getCertificate(),
            Errors.rethrow().wrap((Throwing.Function<Pair<String, String>, String>) jwtPair -> {
                final JsonWebSignature jws = new JsonWebSignature();
                jws.setPayload(new String(Base64.getUrlDecoder().decode(jwtPair.getRight())));
                Optional.ofNullable(jwtPair.getLeft())
                    .map(b64Header -> new String(Base64.getUrlDecoder().decode(b64Header)))
                    .map(JsonParser::parseString)
                    .map(JsonElement::getAsJsonObject)
                    .map(JsonObject::entrySet)
                    .stream()
                    .flatMap(Set::stream)
                    .forEach(entry -> jws.setHeader(entry.getKey(),
                        entry.getValue().getAsString()));

                jws.setCertificateChainHeaderValue(idpIdentity.getCertificate());
                jws.setKey(idpIdentity.getPrivateKey());
                try {
                    return jws.getCompactSerialization();
                } catch (JoseException e) {
                    throw new IdpClientException("Error during encryption", e);
                }
            }));
    }

    public IdpTokenResult login(X509Certificate x509Certificate) throws IdpJoseException, IdpClientException, IdpException {
        return login(x509Certificate, (RuntimeConfig) null);
    }

    public IdpTokenResult login(
        X509Certificate x509Certificate,
        RuntimeConfig runtimeConfig
    ) throws IdpJoseException, IdpClientException, IdpException {
        smcbAuthenticatorService.setX509Certificate(x509Certificate);
        return login(x509Certificate, (pair) -> smcbAuthenticatorService.signIdpChallenge(pair, runtimeConfig));
    }

    private IdpTokenResult login(
        final X509Certificate certificate,
        final Function<Pair<String, String>, String> contentSigner
    ) throws IdpClientException, IdpException, IdpJoseException {
        assertThatClientIsInitialized();

        final String codeVerifier = ClientUtilities.generateCodeVerifier();
        final String nonce = RandomStringUtils.randomAlphanumeric(20);

        // Authorization
        final String state = RandomStringUtils.randomAlphanumeric(20);
        logger.log(Level.FINE, "Performing Authorization with remote-URL: " + discoveryDocumentResponse.getAuthorizationEndpoint());
        String devUrl = "https://erp-dev.zentral.erp.splitdns.ti-dienste.de";
        final AuthorizationResponse authorizationResponse =
            authenticatorClient
                .doAuthorizationRequest(AuthorizationRequest.builder()
                    .clientId(clientId)
                    .link(discoveryDocumentResponse.getAuthorizationEndpoint())
                    .codeChallenge(ClientUtilities.generateCodeChallenge(codeVerifier))
                    .codeChallengeMethod(codeChallengeMethod)
                    .redirectUri(redirectUrl)
                    .state(state)
                    .scopes(prescriptionServiceURL.equals(devUrl) ? scopesRudev : scopes)
                    .nonce(nonce)
                    .build());

        String rawString = authorizationResponse.getAuthenticationChallenge().getChallenge().getRawString();
        IdpJwe idpJwe = new IdpJwe(signServerChallenge(rawString, certificate, contentSigner));

        // Authentication
        logger.log(Level.FINE, "Performing Authentication with remote-URL: " + discoveryDocumentResponse.getAuthorizationEndpoint());
        final AuthenticationResponse authenticationResponse = authenticatorClient.performAuthentication(
            AuthenticationRequest.builder().authenticationEndpointUrl(
                    discoveryDocumentResponse.getAuthorizationEndpoint()
                )
                .signedChallenge(idpJwe)
                .build()
        );
        if (shouldVerifyState) {
            final String stringInTokenUrl = UriUtils.extractParameterValue(authenticationResponse.getLocation(), "state");
            if (!state.equals(stringInTokenUrl)) {
                throw new IdpClientException("state-parameter unexpected changed");
            }
        }

        // get Token
        logger.log(Level.FINE, "Performing getToken with remote-URL: " + discoveryDocumentResponse.getTokenEndpoint());
        return authenticatorClient.retrieveAccessToken(TokenRequest.builder()
            .tokenUrl(discoveryDocumentResponse.getTokenEndpoint())
            .clientId(clientId)
            .code(authenticationResponse.getCode())
            .ssoToken(authenticationResponse.getSsoToken())
            .redirectUrl(redirectUrl)
            .codeVerifier(codeVerifier)
            .idpEnc(discoveryDocumentResponse.getIdpEnc())
            .build());
    }

    private void assertThatIdpIdentityIsValid(final PkiIdentity idpIdentity) {
        Objects.requireNonNull(idpIdentity);
        Objects.requireNonNull(idpIdentity.getCertificate());
    }

    private void assertThatClientIsInitialized() throws IdpClientException {
        logger.log(Level.FINE, "Verifying IDP-Client initialization...");

        if (discoveryDocumentResponse == null
            || StringUtils.isEmpty(discoveryDocumentResponse.getAuthorizationEndpoint())
            || StringUtils.isEmpty(discoveryDocumentResponse.getTokenEndpoint())) {
            throw new IdpClientException(
                "IDP-Client not initialized correctly! Call .initialize() before performing an actual operation."
            );
        }
    }

    @Override
    public IIdpClient initializeClient() throws IdpClientException, IdpException, IdpJoseException {
        logger.info("Initializing using url: " + discoveryDocumentUrl);
        discoveryDocumentResponse = authenticatorClient.retrieveDiscoveryDocument(discoveryDocumentUrl);
        return this;
    }

    public String getDiscoveryDocumentUrl() {
        return discoveryDocumentUrl;
    }
}
