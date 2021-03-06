/*
 * Copyright 2016-2017 by floragunn GmbH - All rights reserved
 * 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed here is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * This software is free of charge for non-commercial and academic use.
 * For commercial use in a production environment you have to obtain a license
 * from https://floragunn.com
 * 
 */

package com.floragunn.searchguard.dlic.rest.api;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.threadpool.ThreadPool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.floragunn.searchguard.auditlog.AuditLog;
import com.floragunn.searchguard.configuration.AdminDNs;
import com.floragunn.searchguard.configuration.IndexBaseConfigurationRepository;
import com.floragunn.searchguard.dlic.rest.support.Utils;
import com.floragunn.searchguard.dlic.rest.validation.AbstractConfigurationValidator;
import com.floragunn.searchguard.dlic.rest.validation.InternalUsersValidator;
import com.floragunn.searchguard.privileges.PrivilegesEvaluator;
import com.floragunn.searchguard.ssl.transport.PrincipalExtractor;
import com.floragunn.searchguard.support.ConfigConstants;

public class InternalUsersApiAction extends PatchableResourceApiAction {

    @Inject
    public InternalUsersApiAction(final Settings settings, final Path configPath, final RestController controller,
            final Client client, final AdminDNs adminDNs, final IndexBaseConfigurationRepository cl,
            final ClusterService cs, final PrincipalExtractor principalExtractor, final PrivilegesEvaluator evaluator,
            ThreadPool threadPool, AuditLog auditLog) {
        super(settings, configPath, controller, client, adminDNs, cl, cs, principalExtractor, evaluator, threadPool,
                auditLog);

        // legacy mapping for backwards compatibility
        // TODO: remove in SG7
        controller.registerHandler(Method.GET, "/_searchguard/api/user/{name}", this);
        controller.registerHandler(Method.GET, "/_searchguard/api/user/", this);
        controller.registerHandler(Method.DELETE, "/_searchguard/api/user/{name}", this);
        controller.registerHandler(Method.PUT, "/_searchguard/api/user/{name}", this);

        // corrected mapping, introduced in SG6
        controller.registerHandler(Method.GET, "/_searchguard/api/internalusers/{name}", this);
        controller.registerHandler(Method.GET, "/_searchguard/api/internalusers/", this);
        controller.registerHandler(Method.DELETE, "/_searchguard/api/internalusers/{name}", this);
        controller.registerHandler(Method.PUT, "/_searchguard/api/internalusers/{name}", this);
        controller.registerHandler(Method.PATCH, "/_searchguard/api/internalusers/", this);
        controller.registerHandler(Method.PATCH, "/_searchguard/api/internalusers/{name}", this);

    }

    @Override
    protected Endpoint getEndpoint() {
        return Endpoint.INTERNALUSERS;
    }

    @Override
    protected void handlePut(RestChannel channel, final RestRequest request, final Client client,
            final Settings.Builder additionalSettingsBuilder) {

        final String username = request.param("name");

        if (username == null || username.length() == 0) {
            badRequestResponse(channel, "No " + getResourceName() + " specified.");
            return;
        }

        if(username.contains(".")) {
            badRequestResponse(channel, "No dots are allowed in the name. User the username attribute: https://docs.search-guard.com/latest/internal-users-database.");
            return;
        }

        // TODO it might be sensible to consolidate this with the overridden method in
        // order to minimize duplicated logic

        final Tuple<Long, Settings> configurationSettings = loadAsSettings(getConfigName(), false);

        if (isHidden(configurationSettings.v2(), username)) {
            forbidden(channel, "Resource '" + username + "' is not available.");
            return;
        }

        // check if resource is writeable
        Boolean readOnly = configurationSettings.v2().getAsBoolean(username + "." + ConfigConstants.CONFIGKEY_READONLY,
                Boolean.FALSE);
        if (readOnly) {
            forbidden(channel, "Resource '" + username + "' is read-only.");
            return;
        }

        // if password is set, it takes precedence over hash
        String plainTextPassword = additionalSettingsBuilder.get("password");
        if (plainTextPassword != null && plainTextPassword.length() > 0) {
            additionalSettingsBuilder.remove("password");
            additionalSettingsBuilder.put("hash", hash(plainTextPassword.toCharArray()));
        }

        // check if user exists
        final Tuple<Long, Settings.Builder> internaluser = load(ConfigConstants.CONFIGNAME_INTERNAL_USERS, false);
        final Map<String, Object> config = Utils.convertJsonToxToStructuredMap(internaluser.v2().build());

        final boolean userExisted = config.containsKey(username);

        // when updating an existing user password hash can be blank, which means no
        // changes

        // sanity checks, hash is mandatory for newly created users
        if (!userExisted && additionalSettingsBuilder.get("hash") == null) {
            badRequestResponse(channel, "Please specify either 'hash' or 'password' when creating a new internal user.");
            return;
        }

        // for existing users, hash is optional
        if (userExisted && additionalSettingsBuilder.get("hash") == null) {
            // sanity check, this should usually not happen
            @SuppressWarnings("unchecked")
            Map<String, String> existingUserSettings = (Map<String, String>) config.get(username);
            if (!existingUserSettings.containsKey("hash")) {
                internalErrorResponse(channel, 
                        "Existing user " + username + " has no password, and no new password or hash was specified.");
                return;
            }
            additionalSettingsBuilder.put("hash", (String) existingUserSettings.get("hash"));
        }

        config.remove(username);

        // checks complete, create or update the user
        config.put(username, Utils.convertJsonToxToStructuredMap(additionalSettingsBuilder.build()));

        saveAnUpdateConfigs(client, request, ConfigConstants.CONFIGNAME_INTERNAL_USERS, Utils.convertStructuredMapToBytes(config), new OnSucessActionListener<IndexResponse>(channel) {
            
            @Override
            public void onResponse(IndexResponse response) {
                if (userExisted) {
                    successResponse(channel, "'" + username + "' updated.");
                } else {
                    createdResponse(channel, "'" + username + "' created.");
                }
                
            }
        }, internaluser.v1());

        

    }

    @Override
    protected void filter(Settings.Builder builder) {
        super.filter(builder);
        // replace password hashes in addition. We must not remove them from the
        // Builder since this would remove users completely if they
        // do not have any addition properties like roles or attributes
        Set<String> entries = builder.build().names();
        for (String key : entries) {
            builder.put(key + ".hash", "");
        }
    }
    
    @Override
    protected AbstractConfigurationValidator postProcessApplyPatchResult(RestChannel channel, RestRequest request, JsonNode existingResourceAsJsonNode,
            JsonNode updatedResourceAsJsonNode, String resourceName) {
    	AbstractConfigurationValidator retVal = null;
        JsonNode passwordNode = updatedResourceAsJsonNode.get("password");

        if (passwordNode != null) {
            String plainTextPassword = passwordNode.asText();
            try {
				XContentBuilder builder = channel.newBuilder();
				builder.startObject();
				builder.field("password", plainTextPassword);
				builder.endObject();
				retVal = getValidator(request, BytesReference.bytes(builder), resourceName);
			} catch (IOException e) {
				log.error(e);
			}

            ((ObjectNode) updatedResourceAsJsonNode).remove("password");
            ((ObjectNode) updatedResourceAsJsonNode).set("hash", new TextNode(hash(plainTextPassword.toCharArray())));
            return retVal;
        }
        
        return null;
    }

    public static String hash(final char[] clearTextPassword) {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        final String hash = OpenBSDBCrypt.generate((Objects.requireNonNull(clearTextPassword)), salt, 12);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(clearTextPassword, '\0');
        return hash;
    }

    @Override
    protected String getResourceName() {
        return "user";
    }

    @Override
    protected String getConfigName() {
        return ConfigConstants.CONFIGNAME_INTERNAL_USERS;
    }

    @Override
    protected AbstractConfigurationValidator getValidator(RestRequest request, BytesReference ref, Object... params) {
        return new InternalUsersValidator(request, ref, this.settings, params);
    }
}
