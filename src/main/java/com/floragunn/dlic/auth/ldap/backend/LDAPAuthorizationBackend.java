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

package com.floragunn.dlic.auth.ldap.backend;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.ldaptive.BindRequest;
import org.ldaptive.Connection;
import org.ldaptive.ConnectionConfig;
import org.ldaptive.Credential;
import org.ldaptive.DefaultConnectionFactory;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.Response;
import org.ldaptive.SearchScope;
import org.ldaptive.control.RequestControl;
import org.ldaptive.provider.ProviderConnection;
import org.ldaptive.sasl.ExternalConfig;
import org.ldaptive.ssl.AllowAnyHostnameVerifier;
import org.ldaptive.ssl.AllowAnyTrustManager;
import org.ldaptive.ssl.CredentialConfig;
import org.ldaptive.ssl.CredentialConfigFactory;
import org.ldaptive.ssl.SslConfig;
import org.ldaptive.ssl.ThreadLocalTLSSocketFactory;

import com.floragunn.dlic.auth.ldap.LdapUser;
import com.floragunn.dlic.auth.ldap.util.ConfigConstants;
import com.floragunn.dlic.auth.ldap.util.LdapHelper;
import com.floragunn.dlic.auth.ldap.util.Utils;
import com.floragunn.searchguard.auth.AuthorizationBackend;
import com.floragunn.searchguard.ssl.util.SSLConfigConstants;
import com.floragunn.searchguard.support.PemKeyReader;
import com.floragunn.searchguard.support.WildcardMatcher;
import com.floragunn.searchguard.user.AuthCredentials;
import com.floragunn.searchguard.user.User;

import io.netty.util.internal.PlatformDependent;

public class LDAPAuthorizationBackend implements AuthorizationBackend {

    private static final String COM_SUN_JNDI_LDAP_OBJECT_DISABLE_ENDPOINT_IDENTIFICATION = "com.sun.jndi.ldap.object.disableEndpointIdentification";
    private static final List<String> DEFAULT_TLS_PROTOCOLS = Arrays.asList("TLSv1.2", "TLSv1.1");
    static final String ONE_PLACEHOLDER = "{1}";
    static final String TWO_PLACEHOLDER = "{2}";
    static final String DEFAULT_ROLEBASE = "";
    static final String DEFAULT_ROLESEARCH = "(member={0})";
    static final String DEFAULT_ROLENAME = "name";
    static final String DEFAULT_USERROLENAME = "memberOf";

    static {
        Utils.init();
    }

    protected static final Logger log = LogManager.getLogger(LDAPAuthorizationBackend.class);
    private final Settings settings;
    private final Path configPath;

    public LDAPAuthorizationBackend(final Settings settings, final Path configPath) {
        this.settings = settings;
        this.configPath = configPath;
    }

    public static Connection getConnection(final Settings settings, final Path configPath) throws Exception {

        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Connection>() {
                @Override
                public Connection run() throws Exception {
                    boolean isJava9OrHigher = PlatformDependent.javaVersion() >= 9;
                    ClassLoader originalClassloader = null;
                    if (isJava9OrHigher) {
                        originalClassloader = Thread.currentThread().getContextClassLoader();
                        Thread.currentThread().setContextClassLoader(new Java9CL());
                    }

                    return getConnection0(settings, configPath, originalClassloader, isJava9OrHigher);
                }
            });
        } catch (PrivilegedActionException e) {
            throw e.getException();
        }

    }

    @SuppressWarnings("unchecked")
    private static Connection getConnection0(final Settings settings, final Path configPath, final ClassLoader cl,
            final boolean needRestore) throws KeyStoreException, NoSuchAlgorithmException, CertificateException,
            FileNotFoundException, IOException, LdapException {
        final boolean enableSSL = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL, false);

        final List<String> ldapHosts = settings.getAsList(ConfigConstants.LDAP_HOSTS,
                Collections.singletonList("localhost"));

        Connection connection = null;
        Exception lastException = null;

        for (String ldapHost : ldapHosts) {

            if (log.isTraceEnabled()) {
                log.trace("Connect to {}", ldapHost);
            }

            try {

                final String[] split = ldapHost.split(":");

                int port;

                if (split.length > 1) {
                    port = Integer.parseInt(split[1]);
                } else {
                    port = enableSSL ? 636 : 389;
                }

                final ConnectionConfig config = new ConnectionConfig();
                config.setLdapUrl("ldap" + (enableSSL ? "s" : "") + "://" + split[0] + ":" + port);

                if (log.isTraceEnabled()) {
                    log.trace("Connect to {}", config.getLdapUrl());
                }

                final Map<String, Object> props = configureSSL(config, settings, configPath);

                DefaultConnectionFactory connFactory = new DefaultConnectionFactory(config);
                connFactory.getProvider().getProviderConfig().setProperties(props);
                connection = connFactory.getConnection();

                final String bindDn = settings.get(ConfigConstants.LDAP_BIND_DN, null);
                final String password = settings.get(ConfigConstants.LDAP_PASSWORD, null);

                if (log.isDebugEnabled()) {
                    log.debug("bindDn {}, password {}", bindDn,
                            password != null && password.length() > 0 ? "****" : "<not set>");
                }

                if (bindDn != null && (password == null || password.length() == 0)) {
                    log.error("No password given for bind_dn {}. Will try to authenticate anonymously to ldap", bindDn);
                }

                final boolean enableClientAuth = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL_CLIENT_AUTH,
                        ConfigConstants.LDAPS_ENABLE_SSL_CLIENT_AUTH_DEFAULT);

                if (log.isDebugEnabled()) {
                    if (enableClientAuth && bindDn == null) {
                        log.debug("Will perform External SASL bind because client cert authentication is enabled");
                    } else if (bindDn == null) {
                        log.debug("Will perform anonymous bind because no bind dn is given");
                    } else if (enableClientAuth && bindDn != null) {
                        log.debug(
                                "Will perform simple bind with bind dn because to bind dn is given and overrides client cert authentication");
                    } else if (!enableClientAuth && bindDn != null) {
                        log.debug("Will perform simple bind with bind dn");
                    }
                }

                BindRequest br = enableClientAuth ? new BindRequest(new ExternalConfig()) : new BindRequest();

                if (bindDn != null && password != null && password.length() > 0) {
                    br = new BindRequest(bindDn, new Credential(password));
                }

                connection.open(br);

                if (connection != null && connection.isOpen()) {
                    break;
                }
            } catch (final Exception e) {
                lastException = e;
                log.warn("Unable to connect to ldapserver {} due to {}. Try next.", ldapHost, e.toString());
                if (log.isDebugEnabled()) {
                    log.debug("Unable to connect to ldapserver due to ", e);
                }
                Utils.unbindAndCloseSilently(connection);
                continue;
            }
        }

        if (connection == null || !connection.isOpen()) {
            if (lastException == null) {
                throw new LdapException("Unable to connect to any of those ldap servers " + ldapHosts);
            } else {
                throw new LdapException(
                        "Unable to connect to any of those ldap servers " + ldapHosts + " due to " + lastException,
                        lastException);
            }
        }

        final Connection delegate = connection;

        return new Connection() {

            @Override
            public Response<Void> reopen(BindRequest request) throws LdapException {
                return delegate.reopen(request);
            }

            @Override
            public Response<Void> reopen() throws LdapException {
                return delegate.reopen();
            }

            @Override
            public Response<Void> open(BindRequest request) throws LdapException {
                return delegate.open(request);
            }

            @Override
            public Response<Void> open() throws LdapException {
                return delegate.open();
            }

            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            @Override
            public ProviderConnection getProviderConnection() {
                return delegate.getProviderConnection();
            }

            @Override
            public ConnectionConfig getConnectionConfig() {
                return delegate.getConnectionConfig();
            }

            @Override
            public void close(RequestControl[] controls) {
                try {
                    delegate.close(controls);
                } finally {
                    restoreClassLoader();
                }
            }

            @Override
            public void close() {
                try {
                    delegate.close();
                } finally {
                    restoreClassLoader();
                }
            }

            private void restoreClassLoader() {
                if (needRestore) {
                    try {
                        AccessController.doPrivileged(new PrivilegedExceptionAction<Void>() {
                            @Override
                            public Void run() throws Exception {
                                Thread.currentThread().setContextClassLoader(cl);
                                return null;
                            }
                        });
                    } catch (PrivilegedActionException e) {
                        log.warn("Unable to restore classloader because of " + e.getException(), e.getException());
                    }
                }
            }
        };
    }

    private static Map<String, Object> configureSSL(final ConnectionConfig config, final Settings settings,
            final Path configPath) throws Exception {

        final Map<String, Object> props = new HashMap<String, Object>();
        final boolean enableSSL = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL, false);
        final boolean enableStartTLS = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_START_TLS, false);

        if (enableSSL || enableStartTLS) {

            final boolean enableClientAuth = settings.getAsBoolean(ConfigConstants.LDAPS_ENABLE_SSL_CLIENT_AUTH,
                    ConfigConstants.LDAPS_ENABLE_SSL_CLIENT_AUTH_DEFAULT);

            final boolean trustAll = settings.getAsBoolean(ConfigConstants.LDAPS_TRUST_ALL, false);

            final boolean verifyHostnames = !trustAll && settings.getAsBoolean(ConfigConstants.LDAPS_VERIFY_HOSTNAMES,
                    ConfigConstants.LDAPS_VERIFY_HOSTNAMES_DEFAULT);

            if (log.isDebugEnabled()) {
                log.debug("verifyHostname {}:", verifyHostnames);
                log.debug("trustall {}:", trustAll);
            }

            if (enableStartTLS && !verifyHostnames) {
                props.put("jndi.starttls.allowAnyHostname", "true");
            }

            final boolean pem = settings.get(ConfigConstants.LDAPS_PEMTRUSTEDCAS_FILEPATH, null) != null
                    || settings.get(ConfigConstants.LDAPS_PEMTRUSTEDCAS_CONTENT, null) != null;

            final SslConfig sslConfig = new SslConfig();
            CredentialConfig cc;

            if (pem) {
                X509Certificate[] trustCertificates = PemKeyReader.loadCertificatesFromStream(
                        PemKeyReader.resolveStream(ConfigConstants.LDAPS_PEMTRUSTEDCAS_CONTENT, settings));

                if (trustCertificates == null) {
                    trustCertificates = PemKeyReader.loadCertificatesFromFile(PemKeyReader
                            .resolve(ConfigConstants.LDAPS_PEMTRUSTEDCAS_FILEPATH, settings, configPath, !trustAll));
                }
                // for client authentication
                X509Certificate authenticationCertificate = PemKeyReader.loadCertificateFromStream(
                        PemKeyReader.resolveStream(ConfigConstants.LDAPS_PEMCERT_CONTENT, settings));

                if (authenticationCertificate == null) {
                    authenticationCertificate = PemKeyReader.loadCertificateFromFile(PemKeyReader
                            .resolve(ConfigConstants.LDAPS_PEMCERT_FILEPATH, settings, configPath, enableClientAuth));
                }

                PrivateKey authenticationKey = PemKeyReader.loadKeyFromStream(
                        settings.get(ConfigConstants.LDAPS_PEMKEY_PASSWORD),
                        PemKeyReader.resolveStream(ConfigConstants.LDAPS_PEMKEY_CONTENT, settings));

                if (authenticationKey == null) {
                    authenticationKey = PemKeyReader
                            .loadKeyFromFile(settings.get(ConfigConstants.LDAPS_PEMKEY_PASSWORD), PemKeyReader.resolve(
                                    ConfigConstants.LDAPS_PEMKEY_FILEPATH, settings, configPath, enableClientAuth));
                }

                cc = CredentialConfigFactory.createX509CredentialConfig(trustCertificates, authenticationCertificate,
                        authenticationKey);

                if (log.isDebugEnabled()) {
                    log.debug("Use PEM to secure communication with LDAP server (client auth is {})",
                            authenticationKey != null);
                }

            } else {
                final KeyStore trustStore = PemKeyReader.loadKeyStore(
                        PemKeyReader.resolve(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_TRUSTSTORE_FILEPATH, settings,
                                configPath, !trustAll),
                        settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_TRUSTSTORE_PASSWORD,
                                SSLConfigConstants.DEFAULT_STORE_PASSWORD),
                        settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_TRUSTSTORE_TYPE));

                final List<String> trustStoreAliases = settings.getAsList(ConfigConstants.LDAPS_JKS_TRUST_ALIAS, null);

                // for client authentication
                final KeyStore keyStore = PemKeyReader.loadKeyStore(
                        PemKeyReader.resolve(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_FILEPATH, settings,
                                configPath, enableClientAuth),
                        settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_PASSWORD,
                                SSLConfigConstants.DEFAULT_STORE_PASSWORD),
                        settings.get(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_TYPE));
                final String keyStorePassword = settings.get(
                        SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_PASSWORD,
                        SSLConfigConstants.DEFAULT_STORE_PASSWORD);

                final String keyStoreAlias = settings.get(ConfigConstants.LDAPS_JKS_CERT_ALIAS, null);
                final String[] keyStoreAliases = keyStoreAlias == null ? null : new String[] { keyStoreAlias };

                if (enableClientAuth && keyStoreAliases == null) {
                    throw new IllegalArgumentException(ConfigConstants.LDAPS_JKS_CERT_ALIAS + " not given");
                }

                if (log.isDebugEnabled()) {
                    log.debug("Use Trust-/Keystore to secure communication with LDAP server (client auth is {})",
                            keyStore != null);
                    log.debug("trustStoreAliases: {}, keyStoreAlias: {}", trustStoreAliases, keyStoreAlias);
                }

                cc = CredentialConfigFactory.createKeyStoreCredentialConfig(trustStore,
                        trustStoreAliases == null ? null : trustStoreAliases.toArray(new String[0]), keyStore,
                        keyStorePassword, keyStoreAliases);

            }

            sslConfig.setCredentialConfig(cc);

            if (trustAll) {
                sslConfig.setTrustManagers(new AllowAnyTrustManager());
            }

            if (!verifyHostnames) {
                sslConfig.setHostnameVerifier(new AllowAnyHostnameVerifier());
                final String deiProp = System.getProperty(COM_SUN_JNDI_LDAP_OBJECT_DISABLE_ENDPOINT_IDENTIFICATION);
                
                if (deiProp == null || !Boolean.parseBoolean(deiProp)) {
                    log.warn("In order to disable host name verification for LDAP connections (verify_hostnames: true), "
                            + "you also need to set set the system property "+COM_SUN_JNDI_LDAP_OBJECT_DISABLE_ENDPOINT_IDENTIFICATION+" to true when starting the JVM running ES. "
                            + "This applies for all Java versions released since July 2018.");
                    // See:
                    // https://www.oracle.com/technetwork/java/javase/8u181-relnotes-4479407.html
                    // https://www.oracle.com/technetwork/java/javase/10-0-2-relnotes-4477557.html
                    // https://www.oracle.com/technetwork/java/javase/11-0-1-relnotes-5032023.html
                }
                
                System.setProperty(COM_SUN_JNDI_LDAP_OBJECT_DISABLE_ENDPOINT_IDENTIFICATION, "true");

            }

            // https://github.com/floragunncom/search-guard/issues/227
            final List<String> enabledCipherSuites = settings.getAsList(ConfigConstants.LDAPS_ENABLED_SSL_CIPHERS,
                    Collections.emptyList());
            final List<String> enabledProtocols = settings.getAsList(ConfigConstants.LDAPS_ENABLED_SSL_PROTOCOLS,
                    DEFAULT_TLS_PROTOCOLS);

            if (!enabledCipherSuites.isEmpty()) {
                sslConfig.setEnabledCipherSuites(enabledCipherSuites.toArray(new String[0]));
                log.debug("enabled ssl cipher suites for ldaps {}", enabledCipherSuites);
            }

            log.debug("enabled ssl/tls protocols for ldaps {}", enabledProtocols);
            sslConfig.setEnabledProtocols(enabledProtocols.toArray(new String[0]));
            config.setSslConfig(sslConfig);
        }

        config.setUseSSL(enableSSL);
        config.setUseStartTLS(enableStartTLS);

        final long connectTimeout = settings.getAsLong(ConfigConstants.LDAP_CONNECT_TIMEOUT, 5000L); // 0L means TCP
                                                                                                     // default timeout
        final long responseTimeout = settings.getAsLong(ConfigConstants.LDAP_RESPONSE_TIMEOUT, 0L); // 0L means wait
                                                                                                    // infinitely

        config.setConnectTimeout(Duration.ofMillis(connectTimeout < 0L ? 0L : connectTimeout)); // 5 sec by default
        config.setResponseTimeout(Duration.ofMillis(responseTimeout < 0L ? 0L : responseTimeout));

        if (log.isDebugEnabled()) {
            log.debug("Connect timeout: " + config.getConnectTimeout() + "/ResponseTimeout: "
                    + config.getResponseTimeout());
        }
        return props;

    }

    @Override
    public void fillRoles(final User user, final AuthCredentials optionalAuthCreds)
            throws ElasticsearchSecurityException {

        if (user == null) {
            return;
        }

        String authenticatedUser;
        String originalUserName;
        LdapEntry entry = null;
        String dn = null;

        if (user instanceof LdapUser) {
            entry = ((LdapUser) user).getUserEntry();
            authenticatedUser = entry.getDn();
            originalUserName = ((LdapUser) user).getOriginalUsername();
        } else {
            authenticatedUser = Utils.escapeStringRfc2254(user.getName());
            originalUserName = user.getName();
        }

        final boolean rolesearchEnabled = settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_ROLESEARCH_ENABLED, true);

        if (log.isDebugEnabled()) {
            log.debug("Try to get roles for {}", authenticatedUser);
        }

        if (log.isTraceEnabled()) {
            log.trace("user class: {}", user.getClass());
            log.trace("authenticatedUser: {}", authenticatedUser);
            log.trace("originalUserName: {}", originalUserName);
            log.trace("entry: {}", String.valueOf(entry));
            log.trace("dn: {}", dn);
        }

        final List<String> skipUsers = settings.getAsList(ConfigConstants.LDAP_AUTHZ_SKIP_USERS,
                Collections.emptyList());
        if (!skipUsers.isEmpty() && (WildcardMatcher.matchAny(skipUsers, originalUserName)
                || WildcardMatcher.matchAny(skipUsers, authenticatedUser))) {
            if (log.isDebugEnabled()) {
                log.debug("Skipped search roles of user {}/{}", authenticatedUser, originalUserName);
            }
            return;
        }

        Connection connection = null;

        try {

            if (entry == null || dn == null) {

                connection = getConnection(settings, configPath);

                if (isValidDn(authenticatedUser)) {
                    // assume dn
                    if (log.isTraceEnabled()) {
                        log.trace("{} is a valid DN", authenticatedUser);
                    }

                    entry = LdapHelper.lookup(connection, authenticatedUser);

                    if (entry == null) {
                        throw new ElasticsearchSecurityException("No user '" + authenticatedUser + "' found");
                    }

                } else {
                    entry = LDAPAuthenticationBackend.exists(user.getName(), connection, settings);

                    if (log.isTraceEnabled()) {
                        log.trace("{} is not a valid DN and was resolved to {}", authenticatedUser, entry);
                    }

                    if (entry == null || entry.getDn() == null) {
                        throw new ElasticsearchSecurityException("No user " + authenticatedUser + " found");
                    }
                }

                dn = entry.getDn();

                if (log.isTraceEnabled()) {
                    log.trace("User found with DN {}", dn);
                }
            }

            final Set<LdapName> ldapRoles = new HashSet<>(150);
            final Set<String> nonLdapRoles = new HashSet<>(150);

            // Roles as an attribute of the user entry
            // default is userrolename: memberOf
            final String userRoleNames = settings.get(ConfigConstants.LDAP_AUTHZ_USERROLENAME, DEFAULT_USERROLENAME);

            if (log.isTraceEnabled()) {
                log.trace("raw userRoleName(s): {}", userRoleNames);
            }

            // we support more than one rolenames, must be separated by a comma
            for (String userRoleName : userRoleNames.split(",")) {
                final String roleName = userRoleName.trim();
                if (entry.getAttribute(roleName) != null) {
                    final Collection<String> userRoles = entry.getAttribute(roleName).getStringValues();
                    for (final String possibleRoleDN : userRoles) {
                        if (isValidDn(possibleRoleDN)) {
                            ldapRoles.add(new LdapName(possibleRoleDN));
                        } else {
                            nonLdapRoles.add(possibleRoleDN);
                        }
                    }
                }
            }

            if (log.isTraceEnabled()) {
                log.trace("User attr. ldap roles count: {}", ldapRoles.size());
                log.trace("User attr. ldap roles {}", ldapRoles);
                log.trace("User attr. non-ldap roles count: {}", nonLdapRoles.size());
                log.trace("User attr. non-ldap roles {}", nonLdapRoles);

            }

            // The attribute in a role entry containing the name of that role, Default is
            // "name".
            // Can also be "dn" to use the full DN as rolename.
            // rolename: name
            final String roleName = settings.get(ConfigConstants.LDAP_AUTHZ_ROLENAME, DEFAULT_ROLENAME);

            if (log.isTraceEnabled()) {
                log.trace("roleName: {}", roleName);
            }

            // Specify the name of the attribute which value should be substituted with {2}
            // Substituted with an attribute value from user's directory entry, of the
            // authenticated user
            // userroleattribute: null
            final String userRoleAttributeName = settings.get(ConfigConstants.LDAP_AUTHZ_USERROLEATTRIBUTE, null);

            if (log.isTraceEnabled()) {
                log.trace("userRoleAttribute: {}", userRoleAttributeName);
                log.trace("rolesearch: {}", settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH));
            }

            String userRoleAttributeValue = null;
            final LdapAttribute userRoleAttribute = entry.getAttribute(userRoleAttributeName);

            if (userRoleAttribute != null) {
                userRoleAttributeValue = userRoleAttribute.getStringValue();
            }

            final List<LdapEntry> rolesResult = !rolesearchEnabled ? null
                    : LdapHelper.search(connection, settings.get(ConfigConstants.LDAP_AUTHZ_ROLEBASE, DEFAULT_ROLEBASE),
                            settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH)
                                    .replace(LDAPAuthenticationBackend.ZERO_PLACEHOLDER, Utils.escapeStringRfc2254(dn))
                                    .replace(ONE_PLACEHOLDER, originalUserName).replace(TWO_PLACEHOLDER,
                                            userRoleAttributeValue == null ? TWO_PLACEHOLDER : userRoleAttributeValue),
                            SearchScope.SUBTREE);

            if (rolesResult != null && !rolesResult.isEmpty()) {
                for (final Iterator<LdapEntry> iterator = rolesResult.iterator(); iterator.hasNext();) {
                    final LdapEntry searchResultEntry = iterator.next();
                    ldapRoles.add(new LdapName(searchResultEntry.getDn()));
                }
            }

            if (log.isTraceEnabled()) {
                log.trace("non user attr. roles count: {}", rolesResult != null ? rolesResult.size() : 0);
                log.trace("non user attr. roles {}", rolesResult);
                log.trace("roles count total {}", ldapRoles.size());
            }

            // nested roles, makes only sense for DN style role names
            if (settings.getAsBoolean(ConfigConstants.LDAP_AUTHZ_RESOLVE_NESTED_ROLES, false)) {

                final List<String> nestedRoleFilter = settings.getAsList(ConfigConstants.LDAP_AUTHZ_NESTEDROLEFILTER,
                        Collections.emptyList());

                if (log.isTraceEnabled()) {
                    log.trace("Evaluate nested roles");
                }

                final Set<LdapName> nestedReturn = new HashSet<>(ldapRoles);

                for (final LdapName roleLdapName : ldapRoles) {

                    final Set<LdapName> nestedRoles = resolveNestedRoles(roleLdapName, connection, userRoleNames, 0,
                            rolesearchEnabled, nestedRoleFilter);

                    if (log.isTraceEnabled()) {
                        log.trace("{} nested roles for {}", nestedRoles.size(), roleLdapName);
                    }

                    nestedReturn.addAll(nestedRoles);
                }

                for (final LdapName roleLdapName : nestedReturn) {
                    final String role = getRoleFromAttribute(roleLdapName, roleName);

                    if (!Strings.isNullOrEmpty(role)) {
                        user.addRole(role);
                    } else {
                        log.warn("No or empty attribute '{}' for entry {}", roleName, roleLdapName);
                    }
                }

            } else {
                // DN roles, extract rolename according to config
                for (final LdapName roleLdapName : ldapRoles) {
                    final String role = getRoleFromAttribute(roleLdapName, roleName);

                    if (!Strings.isNullOrEmpty(role)) {
                        user.addRole(role);
                    } else {
                        log.warn("No or empty attribute '{}' for entry {}", roleName, roleLdapName);
                    }
                }

            }

            // add all non-LDAP roles from user attributes to the final set of backend roles
            for (String nonLdapRoleName : nonLdapRoles) {
                user.addRole(nonLdapRoleName);
            }

            if (log.isDebugEnabled()) {
                log.debug("Roles for {} -> {}", user.getName(), user.getRoles());
            }

            if (log.isTraceEnabled()) {
                log.trace("returned user: {}", user);
            }

        } catch (final Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Unable to fill user roles due to ", e);
            }
            throw new ElasticsearchSecurityException(e.toString(), e);
        } finally {
            Utils.unbindAndCloseSilently(connection);
        }

    }

    protected Set<LdapName> resolveNestedRoles(final LdapName roleDn, final Connection ldapConnection,
            String userRoleName, int depth, final boolean rolesearchEnabled, final List<String> roleFilter)
            throws ElasticsearchSecurityException, LdapException {

        if (!roleFilter.isEmpty() && WildcardMatcher.matchAny(roleFilter, roleDn.toString())) {

            if (log.isTraceEnabled()) {
                log.trace("Filter nested role {}", roleDn);
            }

            return Collections.emptySet();
        }

        depth++;

        final Set<LdapName> result = new HashSet<>(20);

        final LdapEntry e0 = LdapHelper.lookup(ldapConnection, roleDn.toString());

        if (e0.getAttribute(userRoleName) != null) {
            final Collection<String> userRoles = e0.getAttribute(userRoleName).getStringValues();

            for (final String possibleRoleDN : userRoles) {
                if (isValidDn(possibleRoleDN)) {
                    try {
                        result.add(new LdapName(possibleRoleDN));
                    } catch (InvalidNameException e) {
                        // ignore
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Cannot add {} as a role because its not a valid dn", possibleRoleDN);
                    }
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("result nested attr count for depth {} : {}", depth, result.size());
        }

        final String escapedDn = Utils.escapeStringRfc2254(roleDn.toString());

        final List<LdapEntry> rolesResult = !rolesearchEnabled ? null
                : LdapHelper.search(ldapConnection, settings.get(ConfigConstants.LDAP_AUTHZ_ROLEBASE, DEFAULT_ROLEBASE),
                        settings.get(ConfigConstants.LDAP_AUTHZ_ROLESEARCH, DEFAULT_ROLESEARCH)
                                .replace(LDAPAuthenticationBackend.ZERO_PLACEHOLDER, escapedDn)
                                .replace(ONE_PLACEHOLDER, escapedDn),
                        SearchScope.SUBTREE);

        if (log.isTraceEnabled()) {
            log.trace("result nested search count for depth {}: {}", depth,
                    rolesResult == null ? 0 : rolesResult.size());
        }

        if (rolesResult != null) {
            for (final LdapEntry entry : rolesResult) {
                try {
                    final LdapName dn = new LdapName(entry.getDn());
                    result.add(dn);
                } catch (final InvalidNameException e) {
                    throw new LdapException(e);
                }
            }
        }

        int maxDepth = ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH_DEFAULT;
        try {
            maxDepth = settings.getAsInt(ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH,
                    ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH_DEFAULT);
        } catch (Exception e) {
            log.error(ConfigConstants.LDAP_AUTHZ_MAX_NESTED_DEPTH + " is not parseable: " + e, e);
        }

        if (depth < maxDepth) {
            for (final LdapName nm : new HashSet<LdapName>(result)) {
                final Set<LdapName> in = resolveNestedRoles(nm, ldapConnection, userRoleName, depth, rolesearchEnabled,
                        roleFilter);
                result.addAll(in);
            }
        }

        return result;
    }

    @Override
    public String getType() {
        return "ldap";
    }

    private boolean isValidDn(final String dn) {

        if (Strings.isNullOrEmpty(dn)) {
            return false;
        }

        try {
            new LdapName(dn);
        } catch (final Exception e) {
            return false;
        }

        return true;
    }

    private String getRoleFromAttribute(final LdapName ldapName, final String role) {

        if (ldapName == null || Strings.isNullOrEmpty(role)) {
            return null;
        }

        if ("dn".equalsIgnoreCase(role)) {
            return ldapName.toString();
        }

        List<Rdn> rdns = new ArrayList<>(ldapName.getRdns().size());
        rdns.addAll(ldapName.getRdns());

        Collections.reverse(rdns);

        for (Rdn rdn : rdns) {
            if (role.equalsIgnoreCase(rdn.getType())) {

                if (rdn.getValue() == null) {
                    return null;
                }

                return String.valueOf(rdn.getValue());
            }
        }

        return null;
    }

    private final static Class clazz = ThreadLocalTLSSocketFactory.class;

    private final static class Java9CL extends ClassLoader {

        public Java9CL() {
            super();
        }

        public Java9CL(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class loadClass(String name) throws ClassNotFoundException {

            if (!name.equalsIgnoreCase("org.ldaptive.ssl.ThreadLocalTLSSocketFactory")) {
                return super.loadClass(name);
            }

            return clazz;
        }

    }
}
