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

import java.util.List;

public abstract class ResolvedConnection {
    protected final String name;
    protected final String authorizationEndpoint;
    protected final String tokenEndpoint;
    protected final String clientId;
    protected final String clientSecret;
    protected final List<String> scopes;
    protected final List<String> additionalAuthorizationParameters;

    public ResolvedConnection(String name, String authorizationEndpoint, String tokenEndpoint, String clientId,
                                   String clientSecret, List<String> scopes, List<String> additionalAuthorizationParameters) {
        this.name = name;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.additionalAuthorizationParameters = additionalAuthorizationParameters;
    }


    public String name() {
        return name;
    }

    public String authorizationEndpoint() {
        return authorizationEndpoint;
    }

    public String tokenEndpoint() {
        return tokenEndpoint;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public List<String> scopes() {
        return scopes;
    }

    public List<String> additionalAuthorizationParameters() {
        return additionalAuthorizationParameters;
    }
}
