/*
 * Copyright 2016-2018 by floragunn GmbH - All rights reserved
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

package com.floragunn.dlic.auth.http.jwt.keybyoidc;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Map;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.ssl.PrivateKeyDetails;
import org.apache.http.ssl.PrivateKeyStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.floragunn.dlic.AbstractNonClusterTest;
import com.floragunn.dlic.util.SettingsBasedSSLConfigurator;
import com.floragunn.searchguard.cyrpto.CryptoManagerFactory;
import com.floragunn.searchguard.test.helper.file.FileHelper;
import com.floragunn.searchguard.test.helper.network.SocketUtils;
import com.google.common.hash.Hashing;

public class KeySetRetrieverTest extends AbstractNonClusterTest {
    protected static MockIpdServer mockIdpServer;

    @BeforeClass
    public static void setUp() throws Exception {
        mockIdpServer = new MockIpdServer(TestJwk.Jwks.ALL);
    }

    @AfterClass
    public static void tearDown() {
        if (mockIdpServer != null) {
            try {
                mockIdpServer.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void cacheTest() {
        KeySetRetriever keySetRetriever = new KeySetRetriever(mockIdpServer.getDiscoverUri(), null, true);

        keySetRetriever.get();

        Assert.assertEquals(1, keySetRetriever.getOidcCacheMisses());
        Assert.assertEquals(0, keySetRetriever.getOidcCacheHits());

        keySetRetriever.get();
        Assert.assertEquals(1, keySetRetriever.getOidcCacheMisses());
        Assert.assertEquals(1, keySetRetriever.getOidcCacheHits());
    }

    @Test
    public void clientCertTest() throws Exception {

        try (MockIpdServer sslMockIdpServer = new MockIpdServer(TestJwk.Jwks.ALL, SocketUtils.findAvailableTcpPort(),
                true) {
            @Override
            protected void handleDiscoverRequest(HttpRequest request, HttpResponse response, HttpContext context)
                    throws HttpException, IOException {

                MockIpdServer.SSLTestHttpServerConnection connection = (MockIpdServer.SSLTestHttpServerConnection) ((HttpCoreContext) context)
                        .getConnection();

                X509Certificate peerCert = (X509Certificate) connection.getPeerCertificates()[0];

                try {
                    String sha256Fingerprint = Hashing.sha256().hashBytes(peerCert.getEncoded()).toString();

                    Assert.assertEquals("04b2b8baea7a0a893f0223d95b72081e9a1e154a0f9b1b4e75998085972b1b68",
                            sha256Fingerprint);

                } catch (CertificateEncodingException e) {
                    throw new RuntimeException(e);
                }

                super.handleDiscoverRequest(request, response, context);
            }
        }) {
            SSLContextBuilder sslContextBuilder = SSLContexts.custom();

            KeyStore trustStore = CryptoManagerFactory.getInstance().getKeystoreInstance("JKS");
            InputStream trustStream = new FileInputStream(
                    FileHelper.getAbsoluteFilePathFromClassPath("jwt/truststore"+(!utFips()?".jks":".BCFKS")).toFile());
            trustStore.load(trustStream, "changeit".toCharArray());

            KeyStore keyStore = CryptoManagerFactory.getInstance().getKeystoreInstance("JKS");
            InputStream keyStream = new FileInputStream(
                    FileHelper.getAbsoluteFilePathFromClassPath("jwt/spock-keystore"+(!utFips()?".jks":".BCFKS")).toFile());

            keyStore.load(keyStream, "changeit".toCharArray());

            sslContextBuilder.loadTrustMaterial(trustStore, null);

            sslContextBuilder.loadKeyMaterial(keyStore, "changeit".toCharArray(), new PrivateKeyStrategy() {

                @Override
                public String chooseAlias(Map<String, PrivateKeyDetails> aliases, Socket socket) {
                    return "spock";
                }
            });

            SettingsBasedSSLConfigurator.SSLConfig sslConfig = new SettingsBasedSSLConfigurator.SSLConfig(
                    sslContextBuilder.build(), new String[] { "TLSv1.2", "TLSv1.1" }, null, null, false, false, false,
                    trustStore, null, keyStore, null, null);

            KeySetRetriever keySetRetriever = new KeySetRetriever(sslMockIdpServer.getDiscoverUri(), sslConfig, false);

            keySetRetriever.get();

        }
    }
}
