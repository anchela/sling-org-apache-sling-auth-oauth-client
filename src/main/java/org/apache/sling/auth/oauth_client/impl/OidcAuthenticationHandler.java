/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.auth.oauth_client.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ErrorResponse;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Identifier;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import org.apache.jackrabbit.oak.spi.security.authentication.credentials.CredentialsSupport;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityProvider;
import org.apache.sling.auth.core.spi.AuthenticationHandler;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.DefaultAuthenticationFeedbackHandler;
import org.apache.sling.auth.oauth_client.ClientConnection;
import org.apache.sling.auth.oauth_client.spi.LoginCookieManager;
import org.apache.sling.auth.oauth_client.spi.OidcAuthCredentials;
import org.apache.sling.auth.oauth_client.spi.UserInfoProcessor;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.resource.api.JcrResourceConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component(
        service = AuthenticationHandler.class,
        immediate = true
)

@Designate(ocd = OidcAuthenticationHandler.Config.class, factory = true)
public class OidcAuthenticationHandler extends DefaultAuthenticationFeedbackHandler implements AuthenticationHandler {


    private static final Logger logger = LoggerFactory.getLogger(OidcAuthenticationHandler.class);
    private static final String AUTH_TYPE = "oidc";
    public static final String REDIRECT_ATTRIBUTE_NAME = "sling.redirect";

    private final SlingRepository repository;

    private final Map<String, ClientConnection> connections;
    private final OAuthStateManager stateManager;

    String idp;

    private  final String callbackUri;

    private LoginCookieManager loginCookieManager;

    private String defaultRedirect;

    private String defaultConnectionName;

    private UserInfoProcessor userInfoProcessor;

    private static final long serialVersionUID = 1L;

    private boolean userInfoEnabled;

    // We don't want leave the cookie lying around for a long time because it it not needed.
    // At the same time, some OAuth user authentication flows take a long time due to
    // consent, account selection, 2FA, etc so we cannot make this too short.
    protected static final int COOKIE_MAX_AGE_SECONDS = 300;

    private boolean pkceEnabled;

    @ObjectClassDefinition(
            name = "Apache Sling Oidc Authentication Handler",
            description = "Apache Sling Oidc Authentication Handler Service"
    )

    @interface Config {
        @AttributeDefinition(name = "Path",
                description = "Repository path for which this authentication handler should be used by Sling. If this is " +
                        "empty, the authentication handler will be disabled. By default this is set to \"/\".")
        String path() default "/";

        @AttributeDefinition(name = "Sync Handler Configuration Name",
                description = "Name of Sync Handler Configuration")
        String idp() default "oidc";

        @AttributeDefinition(name = "Callback URI",
                description = "Callback URI")
        String callbackUri() default "callbackUri";

        @AttributeDefinition(name = "Default Redirect",
                description = "Default Redirect")
        String defaultRedirect() default "/";

        @AttributeDefinition(name = "Default Connection Name",
                description = "Default Connection Name")
        String defaultConnectionName() default "";

        @AttributeDefinition(name = "PKCE Enabled",
                description = "PKCE Enabled")
        boolean pkceEnabled() default false;

        @AttributeDefinition(name = "UserInfo Enabled",
                description = "UserInfo Enabled")
        String userInfoEnabled() default "true";

    }

    @Activate
    public OidcAuthenticationHandler(@Reference(policyOption = ReferencePolicyOption.GREEDY) @NotNull SlingRepository repository,
                                     @NotNull BundleContext bundleContext, @Reference List<ClientConnection> connections,
                                     @Reference OAuthStateManager stateManager,
                                     Config config,
                                     @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY) LoginCookieManager loginCookieManager,
                                     @Reference(policyOption = ReferencePolicyOption.GREEDY) UserInfoProcessor userInfoProcessor
    ) {

        this.repository = repository;
        this.connections = connections.stream()
                .collect(Collectors.toMap( ClientConnection::name, Function.identity()));
        this.stateManager = stateManager;
        this.idp = config.idp();
        this.callbackUri = config.callbackUri();
        this.defaultRedirect = config.defaultRedirect();
        this.loginCookieManager = loginCookieManager;
        this.defaultConnectionName = config.defaultConnectionName();
        this.userInfoProcessor = userInfoProcessor;
        this.userInfoEnabled = Boolean.parseBoolean(config.userInfoEnabled());
        this.pkceEnabled = config.pkceEnabled();

        logger.debug("activate: registering ExternalIdentityProvider");
        bundleContext.registerService(
                new String[]{ExternalIdentityProvider.class.getName(), CredentialsSupport.class.getName()}, new OidcIdentityProvider(idp),
                null);

        logger.info("OidcAuthenticationHandler successfully activated");

    }



    @Override
    public AuthenticationInfo extractCredentials(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response) {
        logger.debug("inside extractCredentials");

        // Check if the request is authenticated by a oidc login token
        AuthenticationInfo authInfo = loginCookieManager.verifyLoginCookie(request, response);
        if (authInfo != null) {
            // User has a login token
            return authInfo;
        }

        //The request is not authenticated. Check the Authorization Code
        StringBuffer requestURL = request.getRequestURL();
        if ( request.getQueryString() != null )
            requestURL.append('?').append(request.getQueryString());

        AuthorizationResponse authResponse;
        Optional<OAuthState> clientState; //state returned by the idp in the redirect request
        String authCode; //authorization code returned by the idp in the redirect request
        Cookie stateCookie = null;
        Cookie codeVerifierCookie = null;
        try {
            authResponse = AuthorizationResponse.parse(new URI(requestURL.toString()));

            clientState = stateManager.toOAuthState(authResponse.getState());
            if ( !clientState.isPresent() )  {
                // Do not return null to indicate that the handler cannot extract credentials
                throw new IllegalStateException("No state found in authorization response");
            }

            if (authResponse.toSuccessResponse().getAuthorizationCode() == null) {
                throw new IllegalStateException("No authorization code found in authorization response");
            }

            // Retrieve the state value from the cookie
            Cookie[] cookies = request.getCookies();
            if ( cookies == null ) {
                throw new IllegalStateException("Failed state check: No cookies found");
            }
            // iterate over the cookie and get the one with name OAuthStateManager.COOKIE_NAME_REQUEST_KEY
            for (Cookie cookie : cookies) {
                if (OAuthStateManager.COOKIE_NAME_REQUEST_KEY.equals(cookie.getName())) {
                    stateCookie = cookie;
                }
                if (pkceEnabled && OAuthStateManager.COOKIE_NAME_CODE_VERIFIER.equals(cookie.getName())) {
                    codeVerifierCookie = cookie;
                }
            }
            if ( stateCookie == null ) {
                throw new IllegalStateException(String.format("Failed state check: No request cookie named %s found", OAuthStateManager.COOKIE_NAME_REQUEST_KEY));
            }
            if ( pkceEnabled && codeVerifierCookie == null ) {
                logger.debug("Failed state check: No request cookie named '{}' found", OAuthStateManager.COOKIE_NAME_CODE_VERIFIER);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return AuthenticationInfo.FAIL_AUTH;
            }

        } catch (ParseException | URISyntaxException e) {
            logger.debug("Failed to parse authorization response");
            return null;
        }

        String stateFromAuthServer = clientState.get().perRequestKey();
        String stateFromClient = stateCookie.getValue();

        if ( ! stateFromAuthServer.equals(stateFromClient) )
            throw new IllegalStateException("Failed state check: request keys from client and server are not the same");

        if ( !authResponse.indicatesSuccess() ) {
            AuthorizationErrorResponse errorResponse = authResponse.toErrorResponse();
            throw new IllegalStateException("Authentication failed", new RuntimeException(toErrorMessage("Error in authentication response", errorResponse)));
        }

        Optional<String> redirect = Optional.ofNullable(clientState.get().redirect());
        // TODO: find a better way to pass it?
        request.setAttribute(REDIRECT_ATTRIBUTE_NAME,redirect);

        authCode = authResponse.toSuccessResponse().getAuthorizationCode().getValue();

        String desiredConnectionName = clientState.get().connectionName();
        if ( desiredConnectionName == null || desiredConnectionName.isEmpty() )
            throw new IllegalArgumentException("No connection found in clientState");

        ClientConnection connection = connections.get(desiredConnectionName);
        if ( connection == null )
            throw new IllegalArgumentException(String.format("Requested unknown connection '%s'", desiredConnectionName));

        ResolvedOidcConnection conn = ResolvedOidcConnection.resolve(connection);

        ClientID clientId = new ClientID(conn.clientId());
        Secret clientSecret = new Secret(conn.clientSecret());

        ClientSecretBasic clientCredentials = new ClientSecretBasic(clientId, clientSecret);

        AuthorizationCode code = new AuthorizationCode(authCode);

        TokenRequest tokenRequest;
        try {
            URI tokenEndpoint = new URI(conn.tokenEndpoint());

            if (pkceEnabled) {
                // Make the token request, with PKCE
                // TODO: Add ClientSecretBasic
                tokenRequest = new TokenRequest(
                        tokenEndpoint,
                        clientId,
                        new AuthorizationCodeGrant(code, new URI(callbackUri), new CodeVerifier(codeVerifierCookie.getValue())));
            } else {
                clientCredentials = new ClientSecretBasic(clientId, clientSecret);

                tokenRequest = new TokenRequest.Builder(
                        tokenEndpoint,
                        clientCredentials,
                        new AuthorizationCodeGrant(code, new URI(callbackUri))
                ).build();
            }
        } catch (URISyntaxException e) {
                logger.error("Token Endpoint is not a valid URI: {} Error: {}", conn.tokenEndpoint(), e.getMessage());
                throw new RuntimeException(String.format("Token Endpoint is not a valid URI: %s", conn.tokenEndpoint()));
        }

        HTTPRequest httpRequest = tokenRequest.toHTTPRequest();
        // GitHub requires an explicitly set Accept header, otherwise the response is url encoded
        // https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#2-users-are-redirected-back-to-your-site-by-github
        // see also https://bitbucket.org/connect2id/oauth-2.0-sdk-with-openid-connect-extensions/issues/107/support-application-x-www-form-urlencoded
        httpRequest.setAccept("application/json");
        HTTPResponse httpResponse;
        try {
            httpResponse = httpRequest.send();
        } catch (IOException e) {
            logger.error("Failed to exchange authorization code for access token: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }

        // extract id token from the response
        TokenResponse tokenResponse;
        IDTokenClaimsSet claims;
        try {
            tokenResponse = OIDCTokenResponseParser.parse(httpResponse);

            if ( !tokenResponse.indicatesSuccess() ) {
                logger.debug("Token error. Received code: {}, message: {}", tokenResponse.toErrorResponse().getErrorObject().getCode(), tokenResponse.toErrorResponse().getErrorObject().getDescription());
                throw new IllegalStateException("Token exchange error", new RuntimeException(toErrorMessage("Error in token response", tokenResponse.toErrorResponse())));
            }
            tokenResponse = tokenResponse.toSuccessResponse();

            claims = validateIdToken(tokenResponse, conn);
        } catch (ParseException e) {
            logger.error("Failed to parse token response: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        } catch (BadJOSEException | JOSEException e) {
            logger.error("Failed to validate token: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        }
        // Make the request to userInfo
        OidcAuthCredentials credentials;
        if (userInfoEnabled) {
            HTTPResponse httpResponseUserInfo;
            UserInfoResponse userInfoResponse;
            try {
                httpResponseUserInfo = new UserInfoRequest(new URI(((OidcConnectionImpl) connection).userInfoUrl()), tokenResponse.toSuccessResponse().getTokens().getAccessToken())
                        .toHTTPRequest()
                        .send();
                userInfoResponse = UserInfoResponse.parse(httpResponseUserInfo);
                if (!userInfoResponse.indicatesSuccess()) {
                    // The request failed, e.g. due to invalid or expired token
                    logger.debug("UserInfo error. Received code: {}, message: {}", userInfoResponse.toErrorResponse().getErrorObject().getCode(), userInfoResponse.toErrorResponse().getErrorObject().getDescription());
                    throw new IllegalStateException("Token exchange error", new RuntimeException(toErrorMessage("Error in token response", tokenResponse.toErrorResponse())));

                }

                // Extract the claims
                UserInfo userInfo = userInfoResponse.toSuccessResponse().getUserInfo();

                //process credentials
                credentials = userInfoProcessor.process(userInfo, tokenResponse, idp);

            } catch (IOException | URISyntaxException | ParseException e) {
                logger.error("Error while processing UserInfo: {}", e.getMessage(), e);
                throw new IllegalStateException(e);
            }
        } else {
            credentials = userInfoProcessor.process(null, tokenResponse, idp);
        }
        //create authInfo
        String subject = claims.getSubject().getValue();
        authInfo = new AuthenticationInfo(AUTH_TYPE, subject);
        authInfo.put(JcrResourceConstants.AUTHENTICATION_INFO_CREDENTIALS, credentials);

        logger.info("User {} authenticated", subject);
        return authInfo;


    }

    /**
     * Validates the ID token received from the OpenID provider.
     * According to this cocumentation: https://connect2id.com/blog/how-to-validate-an-openid-connect-id-token
     * it perform following validations:
     * <ul>
     *  <li> Checks if the ID token JWS algorithm matches the expected one.</li>
     *  <li> Checks the ID token signature (or HMAC) using the provided key material (from the JWK set URL or the client secret).</li>
     *  <li> Checks if the ID token issuer (iss) and audience (aud) match the expected IdP and client_id.</li>
     *  <li> Checks if the ID token is within the specified validity window (between the given issue time and expiration time, given a 1 minute leeway to accommodate clock skew).</li>
     *  <li> Check the nonce value if one is expected.</li>
     * </ul>
     *
     * @param tokenResponse The token response containing the ID token.
     * @param conn         The resolved OIDC connection.
     * @return The validated ID token claims set.
     * @throws BadJOSEException If the ID token is invalid.
     * @throws JOSEException     If there is an error during validation.
     */
    private IDTokenClaimsSet validateIdToken(TokenResponse tokenResponse, ResolvedOidcConnection conn) throws BadJOSEException, JOSEException {
        Issuer issuer = new Issuer(conn.issuer());
        ClientID clientID = new ClientID(conn.clientId());
        JWSAlgorithm jwsAlg = JWSAlgorithm.RS256; //TODO: Read from config
        URL jwkSetURL = null;
        try {
            jwkSetURL = conn.jwkSetURL().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }

        IDTokenValidator validator = new IDTokenValidator(issuer, clientID, jwsAlg, jwkSetURL);
        return validator.validate(tokenResponse.toSuccessResponse().getTokens().toOIDCTokens().getIDToken(), null);

    }

    private static String toErrorMessage(String context, ErrorResponse error) {

        ErrorObject errorObject = error.getErrorObject();
        StringBuilder message = new StringBuilder();

        message.append(context)
                .append(": ")
                .append(errorObject.getCode());

        message.append(". Status code: ").append(errorObject.getHTTPStatusCode());

        String description = errorObject.getDescription();
        if ( description != null )
            message.append(". ").append(description);

        return message.toString();
    }

    @Override
    public boolean requestCredentials(HttpServletRequest request, HttpServletResponse response) {
        logger.debug("inside requestCredentials");
        String desiredConnectionName = request.getParameter("c");
        if ( desiredConnectionName == null ) {
            logger.debug("Missing mandatory request parameter 'c' using default connection '{}'", defaultConnectionName);
            desiredConnectionName = defaultConnectionName;
        }
        try {
            ClientConnection connection = connections.get(desiredConnectionName);
            if ( connection == null ) {
                logger.debug("Client requested unknown connection '{}'; known: '{}'", desiredConnectionName, connections.keySet());
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Client requested unknown connection");
                return false;
            }

            var redirect = getAuthenticationRequestUri(connection, request, URI.create(callbackUri));
            // add all the cookies to the response
            redirect.cookies().forEach(response::addCookie);
            response.sendRedirect(redirect.uri().toString());
            return true;
        } catch (IOException e) {
            logger.error("Unexpected error while sending redirect.", e);
            return false;
        }
    }

    private RedirectTarget getAuthenticationRequestUri(ClientConnection connection, HttpServletRequest request, URI redirectUri) {

        ResolvedOidcConnection conn = ResolvedOidcConnection.resolve(connection);

        // The client ID provisioned by the OpenID provider when
        // the client was registered
        ClientID clientID = new ClientID(conn.clientId());

        String connectionName = connection.name();
        String redirect = request.getParameter(OAuthStateManager.PARAMETER_NAME_REDIRECT);
        String perRequestKey = new Identifier().getValue();

        ArrayList<Cookie> cookies = new ArrayList<>();
        Cookie cookie = new Cookie(OAuthStateManager.COOKIE_NAME_REQUEST_KEY, perRequestKey);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookies.add(cookie);

        State state = stateManager.toNimbusState(new OAuthState(perRequestKey, connectionName, redirect));

        URI authorizationEndpointUri = URI.create(conn.authorizationEndpoint());

        AuthorizationRequest.Builder authRequestBuilder;
        if (pkceEnabled) {
            // Generate a new random 256 bit code verifier for PKCE
            CodeVerifier codeVerifier = new CodeVerifier();

            authRequestBuilder = new AuthorizationRequest.Builder(
                    new ResponseType("code"),
                    clientID)
                    .endpointURI(authorizationEndpointUri)
                    .redirectionURI(redirectUri)
                    .scope(new Scope(conn.scopes().toArray(new String[0])))
                    .state(state)
                    .codeChallenge(codeVerifier, CodeChallengeMethod.S256);

            Cookie codeVerifierCookie = new Cookie(OAuthStateManager.COOKIE_NAME_CODE_VERIFIER, codeVerifier.getValue());
            codeVerifierCookie.setHttpOnly(true);
            codeVerifierCookie.setSecure(true);
            codeVerifierCookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);

            cookies.add(codeVerifierCookie);

        } else {
            // Compose the OpenID authentication request (for the code flow)
            authRequestBuilder = new AuthorizationRequest.Builder(
                    ResponseType.CODE,
                    clientID)
                    .scope(new Scope(conn.scopes().toArray(new String[0])))
                    .endpointURI(authorizationEndpointUri)
                    .redirectionURI(redirectUri)
                    .state(state);
        }
        if ( conn.additionalAuthorizationParameters() != null ) {
            conn.additionalAuthorizationParameters().stream()
                    .map( s -> s.split("=") )
                    .filter( p -> p.length == 2 )
                    .forEach( p -> authRequestBuilder.customParameter(p[0], p[1]));
        }

        return new RedirectTarget(authRequestBuilder.build().toURI(), cookies);
    }

    record RedirectTarget(URI uri, List<Cookie> cookies) {}

    @Override
    public void dropCredentials(HttpServletRequest request, HttpServletResponse response) {
        // TODO: perform logout from Sling and redirect?
    }
    
    @Override
    public boolean authenticationSucceeded(HttpServletRequest request, HttpServletResponse response, AuthenticationInfo authInfo) {

        if (loginCookieManager == null) {
            logger.debug("TokenUpdate service is not available");
            return super.authenticationSucceeded(request, response, authInfo);
        }

        if(loginCookieManager.getLoginCookie(request) !=null) {
            // A valid login cookie has been sent
            // According to AuthenticationFeedbackHandler javadoc we send false to confirm that the request is authenticated
            return false;
        }

        Object creds = authInfo.get(JcrResourceConstants.AUTHENTICATION_INFO_CREDENTIALS);
        if (creds instanceof OidcAuthCredentials) {
            OidcAuthCredentials sc = (OidcAuthCredentials) creds;
            Object tokenValueObject = sc.getAttribute(".token");
            if (tokenValueObject != null && !tokenValueObject.toString().isEmpty()) {
                String token = tokenValueObject.toString();
                if (!token.isEmpty()) {
                    logger.debug("Calling TokenUpdate service to update token cookie");
                    loginCookieManager.setLoginCookie(request, response, repository, sc);
                }
            }

            try {
                Object redirect = request.getAttribute(REDIRECT_ATTRIBUTE_NAME);
                if ( redirect != null && redirect instanceof String ) {
                    response.sendRedirect(redirect.toString());
                } else {
                    response.sendRedirect(defaultRedirect);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

}
