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
package org.apache.sling.extensions.oidc_rp.support;

import java.io.IOException;
import java.util.Objects;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.extensions.oidc_rp.OAuthToken;
import org.apache.sling.extensions.oidc_rp.OAuthTokenRefresher;
import org.apache.sling.extensions.oidc_rp.OAuthTokenStore;
import org.apache.sling.extensions.oidc_rp.OAuthTokens;
import org.apache.sling.extensions.oidc_rp.OAuthUris;
import org.apache.sling.extensions.oidc_rp.OidcConnection;
import org.apache.sling.extensions.oidc_rp.TokenState;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OAuthEnabledSlingServlet extends SlingSafeMethodsServlet {

	private static final long serialVersionUID = 1L;
	
	private final Logger logger = LoggerFactory.getLogger(getClass());

    private final OidcConnection connection;

    private final OAuthTokenStore tokenStore;

    private final OAuthTokenRefresher oidcClient;
	
    protected OAuthEnabledSlingServlet(OidcConnection connection, OAuthTokenStore tokenStore, OAuthTokenRefresher oidcClient) {
        this.connection = Objects.requireNonNull(connection, "connection may not null");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore may not null");
        this.oidcClient = Objects.requireNonNull(oidcClient, "oidcClient may not null");
    }

	@Override
	protected void doGet(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response)
			throws ServletException, IOException {
	    
	    if ( request.getUserPrincipal() == null ) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User is not authenticated");
            return;
	    }

	    String redirectPath = Objects.requireNonNull(getRedirectPath(request), "getRedirectPath() may not return null");
	    
	    if ( logger.isDebugEnabled() )
	        logger.debug("Configured with connection (name={}) and redirectPath={}", connection.name(), redirectPath);
	    
	    OAuthToken tokenResponse = tokenStore.getAccessToken(connection, request.getResourceResolver());
	    
		switch ( tokenResponse.getState() ) {
	      case VALID:
	        doGetWithToken(request, response, tokenResponse);
	        break;
	      case MISSING:
	        response.sendRedirect(OAuthUris.getOidcEntryPointUri(connection, request, redirectPath).toString());
	        break;
	      case EXPIRED:
	        OAuthToken refreshToken = tokenStore.getRefreshToken(connection, request.getResourceResolver());
	        if ( refreshToken.getState() != TokenState.VALID ) {
	          response.sendRedirect(OAuthUris.getOidcEntryPointUri(connection, request, redirectPath).toString());
	          return;
	        }
	        
	        OAuthTokens oidcTokens = oidcClient.refreshTokens(connection, refreshToken.getValue());
	        tokenStore.persistTokens(connection, request.getResourceResolver(), oidcTokens);
	        doGetWithToken(request, response, tokenResponse);
	        break;
	    }
	}
	
	// TODO - do we need this as a protected method?
	protected @NotNull String getRedirectPath(@NotNull SlingHttpServletRequest request) {
	    return request.getRequestURI();
	}

	protected abstract void doGetWithToken(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response, OAuthToken token)
	        throws ServletException, IOException;
}
