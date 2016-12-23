/**
 * DataCleaner (community edition)
 * Copyright (C) 2014 Neopost - Customer Information Management
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.datacleaner.metamodel.datahub;

import static org.datacleaner.metamodel.datahub.DataHubSecurityMode.CAS;

import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;

import javax.net.ssl.SSLException;

import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.datacleaner.util.SecurityUtils;
import org.datacleaner.util.http.MonitorHttpClient;

/**
 * Describes the connection information needed to connect to the DataHub.
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class DataHubConnection {

    public static final String CAS_PATH = "/cas";
    public static final String DEFAULT_SCHEMA = "MDM";

    private final String _hostname;
    private final int _port;
    private final boolean _useHTTPS;
    private final String _username;
    private final String _password;

    private final String _scheme;
    private final DataHubSecurityMode _securityMode;
    private boolean _acceptUnverifiedSslPeers;

    public DataHubConnection(final String hostname, final Integer port, final String username, final String password,
            final boolean useHTTPS, final boolean acceptUnverifiedSslPeers,
            final DataHubSecurityMode dataHubSecurityMode) {

        _hostname = hostname;
        _port = port;
        _useHTTPS = useHTTPS;
        _username = username;
        _password = password;
        _scheme = _useHTTPS ? "https" : "http";
        _acceptUnverifiedSslPeers = acceptUnverifiedSslPeers;
        _securityMode = dataHubSecurityMode;
    }

    public MonitorHttpClient getHttpClient(final String contextUrl) {
        final CloseableHttpClient httpClient = getCloseableHttpClient();

        if (CAS.equals(_securityMode)) {
            return new DataHubCASMonitorHttpClient(httpClient, getCasServerUrl(), _username, _password, contextUrl);
        } else {
            return new DataHubDefaultMonitorHttpClient(httpClient, getHostname(), getPort(), _username, _password);
        }
    }

    private CloseableHttpClient getCloseableHttpClient() {
        final HttpClientBuilder clientBuilder =
                HttpClients.custom().useSystemProperties().setRetryHandler(new DataHubRequestRetryHandler());
        if (_acceptUnverifiedSslPeers) {
            clientBuilder.setSSLSocketFactory(SecurityUtils.createUnsafeSSLConnectionSocketFactory());
        }
        return clientBuilder.build();
    }

    /**
     * Returns a client suitable for calling REST services on the DataHub
     * @param contextUrl
     * @return A client.
     */
    public MonitorHttpClient getServiceClient(final String contextUrl) {
        final CloseableHttpClient httpClient = getCloseableHttpClient();

        if (CAS.equals(_securityMode)) {
            return new DataHubCASMonitorHttpClient(httpClient, getCasServerUrl(), _username, _password, contextUrl);
        } else {
            return new DataHubDefaultMonitorHttpClient(httpClient, getHostname(), getPort(), _username, _password);
        }

    }

    public String getHostname() {
        return _hostname;
    }

    public int getPort() {
        return _port;
    }

    private String getCasServerUrl() {

        final URIBuilder uriBuilder = getBaseUrlBuilder();
        appendToPath(uriBuilder, CAS_PATH);

        try {
            return uriBuilder.build().toString();
        } catch (final URISyntaxException uriSyntaxException) {
            throw new IllegalStateException(uriSyntaxException);
        }
    }

    protected URIBuilder getBaseUrlBuilder() {
        final URIBuilder baseUriBuilder = new URIBuilder();
        baseUriBuilder.setScheme(_scheme);
        baseUriBuilder.setHost(_hostname);

        if ((_useHTTPS && _port != 443) || (!_useHTTPS && _port != 80)) {
            // only add port if it differs from default ports of HTTP/HTTPS.
            baseUriBuilder.setPort(_port);
        }
        return baseUriBuilder;
    }

    private URIBuilder appendToPath(final URIBuilder uriBuilder, final String pathSegment) {
        if (uriBuilder.getPath() != null) {
            uriBuilder.setPath(uriBuilder.getPath() + pathSegment);
        }

        return uriBuilder.setPath(pathSegment);
    }

    private class DataHubRequestRetryHandler extends DefaultHttpRequestRetryHandler {
        DataHubRequestRetryHandler() {
            super(3, false,
                    Arrays.asList(InterruptedIOException.class, UnknownHostException.class, SSLException.class));
        }
    }
}
