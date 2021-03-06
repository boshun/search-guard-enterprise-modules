/*
 * Copyright 2017 by floragunn GmbH - All rights reserved
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

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.action.licenseinfo.LicenseInfoAction;
import com.floragunn.searchguard.action.licenseinfo.LicenseInfoRequest;
import com.floragunn.searchguard.action.licenseinfo.LicenseInfoResponse;
import com.floragunn.searchguard.auditlog.AuditLog;
import com.floragunn.searchguard.configuration.AdminDNs;
import com.floragunn.searchguard.configuration.IndexBaseConfigurationRepository;
import com.floragunn.searchguard.configuration.SearchGuardLicense;
import com.floragunn.searchguard.dlic.rest.validation.AbstractConfigurationValidator;
import com.floragunn.searchguard.dlic.rest.validation.LicenseValidator;
import com.floragunn.searchguard.privileges.PrivilegesEvaluator;
import com.floragunn.searchguard.ssl.transport.PrincipalExtractor;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.support.LicenseHelper;

public class LicenseApiAction extends AbstractApiAction {
	
	public final static String CONFIG_LICENSE_KEY = "searchguard.dynamic.license";
	
	protected LicenseApiAction(Settings settings, Path configPath, RestController controller, Client client, AdminDNs adminDNs,
			IndexBaseConfigurationRepository cl, ClusterService cs, PrincipalExtractor principalExtractor, 
			final PrivilegesEvaluator evaluator, ThreadPool threadPool, AuditLog auditLog) {
		super(settings, configPath, controller, client, adminDNs, cl, cs, principalExtractor, evaluator, threadPool, auditLog);		
		controller.registerHandler(Method.DELETE, "/_searchguard/api/license", this);
		controller.registerHandler(Method.GET, "/_searchguard/api/license", this);
		controller.registerHandler(Method.PUT, "/_searchguard/api/license", this);
		controller.registerHandler(Method.POST, "/_searchguard/api/license", this);

	}

	@Override
	protected Endpoint getEndpoint() {
		return Endpoint.LICENSE;
	}

	@Override
	protected void handleGet(RestChannel channel, RestRequest request, Client client, Builder additionalSettings) {

		client.execute(LicenseInfoAction.INSTANCE, new LicenseInfoRequest(), new ActionListener<LicenseInfoResponse>() {

			@Override
			public void onFailure(final Exception e) {
			    request.params().clear();
	            logger.error("Unable to fetch license due to", e);
	            internalErrorResponse(channel, "Unable to fetch license: " + e.getMessage());
			}

			@Override
			public void onResponse(final LicenseInfoResponse ur) {
			    successResponse(channel, ur);
			}
		});
	}
	
	@Override
	protected void handlePut(RestChannel channel, final RestRequest request, final Client client,
			final Settings.Builder licenseBuilder) {
		
		String licenseString = licenseBuilder.get("sg_license");
		
		if (licenseString == null || licenseString.length() == 0) {
			badRequestResponse(channel, "License must not be null.");
			return;
		}
		
		// try to decode the license String as base 64, armored PGP encoded String
		String plaintextLicense;
		
		try {
			plaintextLicense = LicenseHelper.validateLicense(licenseString);					
		} catch (Exception e) {
			log.error("Could not decode license {} due to", licenseString, e);
			badRequestResponse(channel, "License could not be decoded due to: " + e.getMessage());
			return;
		}
		
		SearchGuardLicense license = new SearchGuardLicense(XContentHelper.convertToMap(XContentType.JSON.xContent(), plaintextLicense, true), cs);
		
		// check if license is valid at all, honor unsupported switch in es.yml 
		if (!license.isValid() && !acceptInvalidLicense) {
			badRequestResponse(channel, "License invalid due to: " + String.join(",", license.getMsgs()));
			return;
		}
				
		// load existing configuration into new map
		final Tuple<Long, Settings.Builder> existing = load(getConfigName(), false);
		
		if (log.isTraceEnabled()) {
			log.trace(existing.v2().build().toString());	
		}
		
		// license already present?		
		boolean licenseExists = existing.v2().get(CONFIG_LICENSE_KEY) != null;
		
		// license is valid, overwrite old value
		existing.v2().put(CONFIG_LICENSE_KEY, licenseString);
		
		saveAnUpdateConfigs(channel, client, request, getConfigName(), existing.v2(), new OnSucessActionListener<IndexResponse>(channel) {

            @Override
            public void onResponse(IndexResponse response) {
                if (licenseExists) {
                    successResponse(channel, "License updated.");
                } else {
                    // fallback, should not happen since we always have at least a trial license
                    log.warn("License created via REST API.");
                    createdResponse(channel, "License created.");
                }
                
            }
        }, existing.v1());
		
	}

	protected void handlePost(RestChannel channel, final RestRequest request, final Client client,
			final Settings.Builder additionalSettings) {
		notImplemented(channel, Method.POST);
	}

	@Override
	protected AbstractConfigurationValidator getValidator(RestRequest request, BytesReference ref, Object... param) {
		return new LicenseValidator(request, ref, this.settings, param);
	}

	@Override
	protected String getResourceName() {
		// not needed
		return null;
	}

	@Override
	protected String getConfigName() {		
		return ConfigConstants.CONFIGNAME_CONFIG;
	}

}
