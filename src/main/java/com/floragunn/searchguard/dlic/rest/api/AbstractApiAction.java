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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateAction;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateNodeResponse;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateRequest;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse;
import com.floragunn.searchguard.auditlog.AuditLog;
import com.floragunn.searchguard.configuration.AdminDNs;
import com.floragunn.searchguard.configuration.IndexBaseConfigurationRepository;
import com.floragunn.searchguard.dlic.rest.support.Utils;
import com.floragunn.searchguard.dlic.rest.validation.AbstractConfigurationValidator;
import com.floragunn.searchguard.dlic.rest.validation.AbstractConfigurationValidator.ErrorType;
import com.floragunn.searchguard.privileges.PrivilegesEvaluator;
import com.floragunn.searchguard.ssl.transport.PrincipalExtractor;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.user.User;

public abstract class AbstractApiAction extends BaseRestHandler {

	protected final Logger log = LogManager.getLogger(this.getClass());

	protected final IndexBaseConfigurationRepository cl;
	protected final ClusterService cs;
	final ThreadPool threadPool;
	private String searchguardIndex;
	private final RestApiPrivilegesEvaluator restApiPrivilegesEvaluator;
	protected final Boolean acceptInvalidLicense;
	protected final AuditLog auditLog;

	protected AbstractApiAction(final Settings settings, final Path configPath, final RestController controller,
			final Client client, final AdminDNs adminDNs, final IndexBaseConfigurationRepository cl,
			final ClusterService cs, final PrincipalExtractor principalExtractor, final PrivilegesEvaluator evaluator,
			ThreadPool threadPool, AuditLog auditLog) {
		super(settings);
		this.searchguardIndex = settings.get(ConfigConstants.SEARCHGUARD_CONFIG_INDEX_NAME,
				ConfigConstants.SG_DEFAULT_CONFIG_INDEX);
		this.acceptInvalidLicense = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_UNSUPPORTED_RESTAPI_ACCEPT_INVALID_LICENSE, Boolean.FALSE);

		this.cl = cl;
		this.cs = cs;
		this.threadPool = threadPool;
		this.restApiPrivilegesEvaluator = new RestApiPrivilegesEvaluator(settings, adminDNs, evaluator,
				principalExtractor, configPath, threadPool);
		this.auditLog = auditLog;
	}

	protected abstract AbstractConfigurationValidator getValidator(RestRequest request, BytesReference ref, Object... params);

	protected abstract String getResourceName();

	protected abstract String getConfigName();

	protected Tuple<String[], RestResponse> handleApiRequest(final RestChannel channel, final RestRequest request, final Client client)
			throws Throwable {

		// validate additional settings, if any
		AbstractConfigurationValidator validator = getValidator(request, request.content());
		if (!validator.validateSettings()) {
			request.params().clear();
			return new Tuple<String[], RestResponse>(new String[0],
					new BytesRestResponse(RestStatus.BAD_REQUEST, validator.errorsAsXContent(channel)));
		}
		switch (request.method()) {
		case DELETE:
			return handleDelete(channel,request, client, validator.settingsBuilder());
		case POST:
			return handlePost(channel,request, client, validator.settingsBuilder());
		case PUT:
			return handlePut(channel,request, client, validator.settingsBuilder());
		case GET:
			return handleGet(channel,request, client, validator.settingsBuilder());
		default:
			throw new IllegalArgumentException(request.method() + " not supported");
		}
	}

	protected Tuple<String[], RestResponse> handleDelete(final RestChannel channel, final RestRequest request, final Client client,
			final Settings.Builder additionalSettingsBuilder) throws Throwable {
		final String name = request.param("name");

		if (name == null || name.length() == 0) {
			return badRequestResponse(channel, "No " + getResourceName() + " specified");
		}

		final Settings existingAsSettings = loadAsSettings(getConfigName(), false);
		
		if (isHidden(existingAsSettings, name)) {
            return notFound(channel, getResourceName() + " " + name + " not found.");
		}
		
		if (isReadOnly(existingAsSettings, name)) {
			return forbidden(channel, "Resource '"+ name +"' is read-only.");
		}
		
		final Map<String, Object> config = Utils.convertJsonToxToStructuredMap(Settings.builder().put(existingAsSettings).build()); 

		boolean resourceExisted = config.containsKey(name);
		config.remove(name);
		if (resourceExisted) {
			save(client, request, getConfigName(), Utils.convertStructuredMapToBytes(config));
			return successResponse(channel, "'" + name + "' deleted.", getConfigName());
		} else {
			return notFound(channel, getResourceName() + " " + name + " not found.");
		}
	}

	protected Tuple<String[], RestResponse> handlePut(final RestChannel channel, final RestRequest request, final Client client,
			final Settings.Builder additionalSettingsBuilder) throws Throwable {
		
		final String name = request.param("name");

		if (name == null || name.length() == 0) {
			return badRequestResponse(channel, "No " + getResourceName() + " specified");
		}

		final Settings existingAsSettings = loadAsSettings(getConfigName(), false);

		if (isHidden(existingAsSettings, name)) {
            return forbidden(channel, "Resource '"+ name +"' is not available.");		    
		}
		
		if (isReadOnly(existingAsSettings, name)) {
			return forbidden(channel, "Resource '"+ name +"' is read-only.");
		}
		
		if (log.isTraceEnabled()) {
			log.trace(additionalSettingsBuilder.build());
		}
		
		final Map<String, Object> con = Utils.convertJsonToxToStructuredMap(existingAsSettings); 
		
		boolean existed = con.containsKey(name);

		con.put(name, Utils.convertJsonToxToStructuredMap(additionalSettingsBuilder.build()));
		
		save(client, request, getConfigName(), Utils.convertStructuredMapToBytes(con));
		if (existed) {
			return successResponse(channel, "'" + name + "' updated.", getConfigName());
		} else {
			return createdResponse(channel, "'" + name + "' created.", getConfigName());
		}
	}

	protected Tuple<String[], RestResponse> handlePost(final RestChannel channel, final RestRequest request, final Client client,
			final Settings.Builder additionalSettings) throws Throwable {
		return notImplemented(channel, Method.POST);
	}

	protected Tuple<String[], RestResponse> handleGet(final RestChannel channel, RestRequest request, Client client, Builder additionalSettings)
			throws Throwable {

		final String resourcename = request.param("name");

		final Settings.Builder settingsBuilder = load(getConfigName(), true);

		// filter hidden resources and sensitive settings
		filter(settingsBuilder);
		
		final Settings configurationSettings = settingsBuilder.build();

		// no specific resource requested, return complete config
		if (resourcename == null || resourcename.length() == 0) {
			return new Tuple<String[], RestResponse>(new String[0],
					new BytesRestResponse(RestStatus.OK, convertToJson(channel, configurationSettings)));
		}
		
		
		
		final Map<String, Object> con = 
		        new HashMap<>(Utils.convertJsonToxToStructuredMap(Settings.builder().put(configurationSettings).build()))
		        .entrySet()
		        .stream()
		        .filter(f->f.getKey() != null && f.getKey().equals(resourcename)) //copy keys
		        .collect(Collectors.toMap(p -> p.getKey(), p -> p.getValue()));

		if (!con.containsKey(resourcename)) {
			return notFound(channel, "Resource '" + resourcename + "' not found.");
		}
		return new Tuple<String[], RestResponse>(new String[0],
				new BytesRestResponse(RestStatus.OK, XContentHelper.convertToJson(Utils.convertStructuredMapToBytes(con), false, false, XContentType.JSON)));
	}

	protected final Settings.Builder load(final String config, boolean triggerComplianceWhenCached) {
		return Settings.builder().put(loadAsSettings(config, triggerComplianceWhenCached));
	}

	protected final Settings loadAsSettings(final String config, boolean triggerComplianceWhenCached) {
		return cl.getConfiguration(config, triggerComplianceWhenCached);
	}

	protected boolean ensureIndexExists(final Client client) {
		if (!cs.state().metaData().hasConcreteIndex(this.searchguardIndex)) {
			return false;
		}
		return true;
	}
	
	protected void filter(Settings.Builder builder) {
	    Settings settings = builder.build();
	    
        for (Map.Entry<String, Settings> entry : settings.getAsGroups(true).entrySet()) {
            if (entry.getValue().getAsBoolean("hidden", false)) {
                for (String subKey : entry.getValue().keySet()) {
                    builder.remove(entry.getKey() + "." + subKey);
                }
            }
        }
	}
	
	protected void save(final RestChannel channel, final Client client, final RestRequest request, final String config,
            final Settings.Builder settings) throws Throwable {
	    save(client, request, config, toSource(channel, settings));
	}

	protected void save(final Client client, final RestRequest request, final String config,
			final BytesReference bytesRef) throws Throwable {
		final Semaphore sem = new Semaphore(0);
		final List<Throwable> exception = new ArrayList<Throwable>(1);
		final IndexRequest ir = new IndexRequest(this.searchguardIndex);

		String type = "sg";
		String id = config;

		if (cs.state().metaData().index(this.searchguardIndex).mapping("config") != null) {
			type = config;
			id = "0";
		}

		client.index(ir.type(type).id(id).setRefreshPolicy(RefreshPolicy.IMMEDIATE).source(config, bytesRef),
				new ActionListener<IndexResponse>() {

					@Override
					public void onResponse(final IndexResponse response) {
						sem.release();
						if (logger.isDebugEnabled()) {
							logger.debug("{} successfully updated", config);
						}
					}

					@Override
					public void onFailure(final Exception e) {
						sem.release();
						exception.add(e);
						logger.error("Cannot update {} due to", config, e);
					}
				});

		if (!sem.tryAcquire(2, TimeUnit.MINUTES)) {
			// timeout
			logger.error("Cannot update {} due to timeout}", config);
			throw new ElasticsearchException("Timeout updating " + config);
		}

		if (exception.size() > 0) {
			throw exception.get(0);
		}

	}

	@Override
	protected final RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {

		// consume all parameters first so we can return a correct HTTP status,
		// not 400
		consumeParameters(request);

		// TODO: - Initialize if non-existant
		// check if SG index has been initialized
		if (!ensureIndexExists(client)) {
			return channel -> channel.sendResponse(
					new BytesRestResponse(RestStatus.INTERNAL_SERVER_ERROR, ErrorType.SG_NOT_INITIALIZED.getMessage())); //TODO return json
		}

		// check if request is authorized
		String authError = restApiPrivilegesEvaluator.checkAccessPermissions(request, getEndpoint());

		if (authError != null) {
			logger.error("No permission to access REST API: " + authError);
			final User user = (User) threadPool.getThreadContext().getTransient(ConfigConstants.SG_USER);
			auditLog.logMissingPrivileges(authError, user==null?null:user.getName(), request);
			// for rest request
			request.params().clear();
			return channel -> channel.sendResponse((BytesRestResponse)forbidden(channel, "No permission to access REST API: " + authError).v2());
		}
		
		return channel -> {

    		final Semaphore sem = new Semaphore(0);
    		final List<Throwable> exception = new ArrayList<Throwable>(1);
    		final Tuple<String[], RestResponse> response;
    
    		final Object originalUser = threadPool.getThreadContext().getTransient(ConfigConstants.SG_USER);
    		final Object originalRemoteAddress = threadPool.getThreadContext().getTransient(ConfigConstants.SG_REMOTE_ADDRESS);
    		final Object originalOrigin = threadPool.getThreadContext().getTransient(ConfigConstants.SG_ORIGIN);
    		
    		try (StoredContext ctx = threadPool.getThreadContext().stashContext()) {
    
    			threadPool.getThreadContext().putHeader(ConfigConstants.SG_CONF_REQUEST_HEADER, "true");
    			threadPool.getThreadContext().putTransient(ConfigConstants.SG_USER, originalUser);
    			threadPool.getThreadContext().putTransient(ConfigConstants.SG_REMOTE_ADDRESS, originalRemoteAddress);
    			threadPool.getThreadContext().putTransient(ConfigConstants.SG_ORIGIN, originalOrigin);
    
    			response = handleApiRequest(channel, request, client);
    
    			// reload config
    			if (response.v1().length > 0) {
    
    				final ConfigUpdateRequest cur = new ConfigUpdateRequest(response.v1());
    				// cur.putInContext(ConfigConstants.SG_USER,
    				// new User((String)
    				// request.getFromContext(ConfigConstants.SG_SSL_PRINCIPAL)));
    
    				client.execute(ConfigUpdateAction.INSTANCE, cur, new ActionListener<ConfigUpdateResponse>() {
    
    					@Override
    					public void onFailure(final Exception e) {
    						sem.release();
    						logger.error("Cannot update {} due to", Arrays.toString(response.v1()), e);
    						exception.add(e);
    					}
    
    					@Override
    					public void onResponse(final ConfigUpdateResponse ur) {
    						sem.release();
    						if (!checkConfigUpdateResponse(ur)) {
    							logger.error("Cannot update {}", Arrays.toString(response.v1()));
    							exception.add(
    									new ElasticsearchException("Unable to update " + Arrays.toString(response.v1())));
    						} else if (logger.isDebugEnabled()) {
    							logger.debug("Configs {} successfully updated", Arrays.toString(response.v1()));
    						}
    					}
    				});
    
    			} else {
    				sem.release();
    			}
    
    		} catch (final Throwable e) {
    			logger.error("Unexpected exception {}", e.toString(), e);
    			request.params().clear();
    			channel.sendResponse(new BytesRestResponse(RestStatus.INTERNAL_SERVER_ERROR, e.toString())); //TODO return json
    			return;
    		}
    
    		try {
    			if (!sem.tryAcquire(2, TimeUnit.MINUTES)) {
    				// timeout
    				logger.error("Cannot update {} due to timeout", Arrays.toString(response.v1()));
    				throw new ElasticsearchException("Timeout updating " + Arrays.toString(response.v1()));
    			}
    		} catch (final InterruptedException e) {
    			Thread.currentThread().interrupt();
    		}
    
    		if (exception.size() > 0) {
    			request.params().clear();
    			channel.sendResponse(new BytesRestResponse(RestStatus.INTERNAL_SERVER_ERROR, exception.get(0).toString()));//TODO return json
    			return;
    		}
    
    		channel.sendResponse(response.v2());
		};

	}

	protected static BytesReference toSource(RestChannel channel, final Settings.Builder settingsBuilder) throws IOException {
		final XContentBuilder builder = channel.newBuilder();
		builder.startObject(); // 1
		settingsBuilder.build().toXContent(builder, ToXContent.EMPTY_PARAMS);
		builder.endObject(); // 2
		return BytesReference.bytes(builder);
	}

	protected boolean checkConfigUpdateResponse(final ConfigUpdateResponse response) {

		final int nodeCount = cs.state().getNodes().getNodes().size();
		final int expectedConfigCount = 1;

		boolean success = response.getNodes().size() == nodeCount;
		if (!success) {
			logger.error(
					"Expected " + nodeCount + " nodes to return response, but got only " + response.getNodes().size());
		}

		for (final String nodeId : response.getNodesMap().keySet()) {
			final ConfigUpdateNodeResponse node = response.getNodesMap().get(nodeId);
			final boolean successNode = node.getUpdatedConfigTypes() != null
					&& node.getUpdatedConfigTypes().length == expectedConfigCount;

			if (!successNode) {
				logger.error("Expected " + expectedConfigCount + " config types for node " + nodeId + " but got only "
						+ Arrays.toString(node.getUpdatedConfigTypes()));
			}

			success = success && successNode;
		}

		return success;
	}

	protected static XContentBuilder convertToJson(RestChannel channel, Settings settings) throws IOException {
		XContentBuilder builder = channel.newBuilder();
		builder.startObject();
		settings.toXContent(builder, ToXContent.EMPTY_PARAMS);
		builder.endObject();
		return builder;
	}

	protected Tuple<String[], RestResponse> response(RestChannel channel, RestStatus status, String statusString, String message,
			String... configs) {

		try {
			final XContentBuilder builder = channel.newBuilder();
			builder.startObject();
			builder.field("status", statusString);
			builder.field("message", message);
			builder.endObject();
			String[] configsToUpdate = configs == null ? new String[0] : configs;
			return new Tuple<String[], RestResponse>(configsToUpdate, new BytesRestResponse(status, builder));
		} catch (IOException ex) {
			logger.error("Cannot build response", ex);
			return null;
		}
	}

	protected Tuple<String[], RestResponse> successResponse(RestChannel channel, String message, String... configs) {
		return response(channel, RestStatus.OK, RestStatus.OK.name(), message, configs);
	}

	protected Tuple<String[], RestResponse> createdResponse(RestChannel channel, String message, String... configs) {
		return response(channel, RestStatus.CREATED, RestStatus.CREATED.name(), message, configs);
	}

	protected Tuple<String[], RestResponse> badRequestResponse(RestChannel channel, String message) {
		return response(channel, RestStatus.BAD_REQUEST, RestStatus.BAD_REQUEST.name(), message);
	}

	protected Tuple<String[], RestResponse> notFound(RestChannel channel, String message) {
		return response(channel, RestStatus.NOT_FOUND, RestStatus.NOT_FOUND.name(), message);
	}

	protected Tuple<String[], RestResponse> forbidden(RestChannel channel, String message) {
		return response(channel, RestStatus.FORBIDDEN, RestStatus.FORBIDDEN.name(), message);
	}

	protected Tuple<String[], RestResponse> internalErrorResponse(RestChannel channel, String message) {
		return response(channel, RestStatus.INTERNAL_SERVER_ERROR, RestStatus.INTERNAL_SERVER_ERROR.name(), message);
	}

	protected Tuple<String[], RestResponse> unprocessable(RestChannel channel, String message) {
		return response(channel, RestStatus.UNPROCESSABLE_ENTITY, RestStatus.UNPROCESSABLE_ENTITY.name(), message);
	}

	protected Tuple<String[], RestResponse> notImplemented(RestChannel channel, Method method) {
		return response(channel, RestStatus.NOT_IMPLEMENTED, RestStatus.NOT_IMPLEMENTED.name(),
				"Method " + method.name() + " not supported for this action.");
	}
	
	protected boolean isReadOnly(Settings settings, String resourceName) {
	    return settings.getAsBoolean(resourceName+ "." + ConfigConstants.CONFIGKEY_READONLY, Boolean.FALSE);
	}

    protected boolean isHidden(Settings settings, String resourceName) {
        return settings.getAsBoolean(resourceName+ "." + ConfigConstants.CONFIGKEY_HIDDEN, Boolean.FALSE);
    }
	
	/**
	 * Consume all defined parameters for the request. Before we handle the
	 * request in subclasses where we actually need the parameter, some global
	 * checks are performed, e.g. check whether the SG index exists. Thus, the
	 * parameter(s) have not been consumed, and ES will always return a 400 with
	 * an internal error message.
	 * 
	 * @param request
	 */
	protected void consumeParameters(final RestRequest request) {
		request.param("name");
	}

	@Override
	public String getName() {
		return getClass().getSimpleName();
	}

	protected abstract Endpoint getEndpoint();

}
