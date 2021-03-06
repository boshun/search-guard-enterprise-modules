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

package com.floragunn.dlic.auth.ldap2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.settings.Settings;
import org.ldaptive.BindRequest;
import org.ldaptive.Connection;
import org.ldaptive.ConnectionFactory;
import org.ldaptive.Credential;
import org.ldaptive.LdapEntry;
import org.ldaptive.LdapException;
import org.ldaptive.Response;
import org.ldaptive.pool.ConnectionPool;

import com.floragunn.dlic.auth.ldap.LdapUser;
import com.floragunn.dlic.auth.ldap.util.ConfigConstants;
import com.floragunn.dlic.auth.ldap.util.Utils;
import com.floragunn.dlic.util.SettingsBasedSSLConfigurator.SSLConfigException;
import com.floragunn.searchguard.auth.AuthenticationBackend;
import com.floragunn.searchguard.auth.Destroyable;
import com.floragunn.searchguard.user.AuthCredentials;
import com.floragunn.searchguard.user.User;

public class LDAPAuthenticationBackend2 implements AuthenticationBackend, Destroyable {

    protected static final Logger log = LogManager.getLogger(LDAPAuthenticationBackend2.class);

    private final Settings settings;

    private ConnectionPool connectionPool;
    private ConnectionFactory connectionFactory;
    private ConnectionFactory authConnectionFactory;
    private LDAPUserSearcher userSearcher;
    private final int customAttrMaxValueLen;
    private final List<String> whitelistedAttributes;

    public LDAPAuthenticationBackend2(final Settings settings, final Path configPath) throws SSLConfigException {
        this.settings = settings;

        LDAPConnectionFactoryFactory ldapConnectionFactoryFactory = new LDAPConnectionFactoryFactory(settings,
                configPath);

        this.connectionPool = ldapConnectionFactoryFactory.createConnectionPool();
        this.connectionFactory = ldapConnectionFactoryFactory.createConnectionFactory(this.connectionPool);

        if (this.connectionPool != null) {
            this.authConnectionFactory = ldapConnectionFactoryFactory.createBasicConnectionFactory();
        } else {
            this.authConnectionFactory = this.connectionFactory;
        }

        this.userSearcher = new LDAPUserSearcher(settings);
        customAttrMaxValueLen = settings.getAsInt(ConfigConstants.LDAP_CUSTOM_ATTR_MAXVAL_LEN, 36);
        whitelistedAttributes = settings.getAsList(ConfigConstants.LDAP_CUSTOM_ATTR_WHITELIST,
                null);
    }
    
    @Override
    public User authenticate(final AuthCredentials credentials) throws ElasticsearchSecurityException {
        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<User>() {
                @Override
                public User run() throws Exception {
                    return authenticate0(credentials);
                }
            });
        } catch (PrivilegedActionException e) {
            if (e.getException() instanceof ElasticsearchSecurityException) {
                throw (ElasticsearchSecurityException) e.getException();
            } else if (e.getException() instanceof RuntimeException) {
                throw (RuntimeException) e.getException();
            } else {
                throw new RuntimeException(e.getException());
            }
        }
    }


    private User authenticate0(final AuthCredentials credentials) throws ElasticsearchSecurityException {

        Connection ldapConnection = null;
        final String user = credentials.getUsername();
        byte[] password = credentials.getPassword();

        try {

            ldapConnection = connectionFactory.getConnection();
            ldapConnection.open();

            LdapEntry entry = userSearcher.exists(ldapConnection, user);

            // fake a user that no exists
            // makes guessing if a user exists or not harder when looking on the
            // authentication delay time
            if (entry == null && settings.getAsBoolean(ConfigConstants.LDAP_FAKE_LOGIN_ENABLED, false)) {
                String fakeLognDn = settings.get(ConfigConstants.LDAP_FAKE_LOGIN_DN,
                        "CN=faketomakebindfail,DC=" + UUID.randomUUID().toString());
                entry = new LdapEntry(fakeLognDn);
                password = settings.get(ConfigConstants.LDAP_FAKE_LOGIN_PASSWORD, "fakeLoginPwd123")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (entry == null) {
                throw new ElasticsearchSecurityException("No user " + user + " found");
            }

            final String dn = entry.getDn();

            if (log.isTraceEnabled()) {
                log.trace("Try to authenticate dn {}", dn);
            }

            if (this.connectionPool == null) {
                authenticateByLdapServer(ldapConnection, dn, password);
            } else {
                authenticateByLdapServerWithSeparateConnection(dn, password);
            }

            final String usernameAttribute = settings.get(ConfigConstants.LDAP_AUTHC_USERNAME_ATTRIBUTE, null);
            String username = dn;

            if (usernameAttribute != null && entry.getAttribute(usernameAttribute) != null) {
                username = Utils.getSingleStringValue(entry.getAttribute(usernameAttribute));
            }

            if (log.isDebugEnabled()) {
                log.debug("Authenticated username {}", username);
            }

            // by default all ldap attributes which are not binary and with a max value
            // length of 36 are included in the user object
            // if the whitelist contains at least one value then all attributes will be
            // additional check if whitelisted (whitelist can contain wildcard and regex)
            return new LdapUser(username, user, entry, credentials, customAttrMaxValueLen, whitelistedAttributes);

        } catch (final Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Unable to authenticate user due to ", e);
            }
            throw new ElasticsearchSecurityException(e.toString(), e);
        } finally {
            Arrays.fill(password, (byte) '\0');
            password = null;
            Utils.unbindAndCloseSilently(ldapConnection);
        }

    }

    @Override
    public String getType() {
        return "ldap";
    }

    
    @Override
    public boolean exists(final User user) {
        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

     
        return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
            @Override
            public Boolean run() {
                return exists0(user);
            }
        });
        
    }

    private boolean exists0(final User user) {
        Connection ldapConnection = null;
        String userName = user.getName();

        if (user instanceof LdapUser) {
            userName = ((LdapUser) user).getUserEntry().getDn();
        }

        try {
            ldapConnection = this.connectionFactory.getConnection();
            ldapConnection.open();
            LdapEntry userEntry = this.userSearcher.exists(ldapConnection, userName);
            
            boolean exists = userEntry != null;
            
            if(exists) {
                user.addAttributes(LdapUser.extractLdapAttributes(userName, userEntry, customAttrMaxValueLen, whitelistedAttributes));
            }
            
            return exists;
        } catch (final Exception e) {
            log.warn("User {} does not exist due to " + e, userName);
            if (log.isDebugEnabled()) {
                log.debug("User does not exist due to ", e);
            }
            return false;
        } finally {
            Utils.unbindAndCloseSilently(ldapConnection);
        }
    }

    private void authenticateByLdapServer(final Connection connection, final String dn, byte[] password)
            throws LdapException {
        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Response<Void>>() {
                @Override
                public Response<Void> run() throws LdapException {
                    return connection.getProviderConnection().bind(new BindRequest(dn, new Credential(password)));
                }
            });
        } catch (PrivilegedActionException e) {
            if (e.getException() instanceof LdapException) {
                throw (LdapException) e.getException();
            } else if (e.getException() instanceof RuntimeException) {
                throw (RuntimeException) e.getException();
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private void authenticateByLdapServerWithSeparateConnection(final String dn, byte[] password) throws LdapException {
        try (Connection unpooledConnection = this.authConnectionFactory.getConnection()) {
            unpooledConnection.open();
            authenticateByLdapServer(unpooledConnection, dn, password);
        }
    }

    @Override
    public void destroy() {
        if (this.connectionPool != null) {
            this.connectionPool.close();
            this.connectionPool = null;
        }

    }

}
