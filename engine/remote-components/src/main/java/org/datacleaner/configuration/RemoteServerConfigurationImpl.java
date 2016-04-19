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
package org.datacleaner.configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.datacleaner.descriptors.RemoteDescriptorProvider;
import org.datacleaner.job.concurrent.ScheduledTaskRunner;
import org.datacleaner.job.concurrent.TaskListener;
import org.datacleaner.job.concurrent.TaskRunner;
import org.datacleaner.job.tasks.Task;
import org.datacleaner.restclient.ComponentRESTClient;
import org.datacleaner.restclient.DataCloudUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link RemoteServerConfiguration}.
 */
public class RemoteServerConfigurationImpl implements RemoteServerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RemoteServerConfigurationImpl.class);
    private static final int TEST_CONNECTION_TIMEOUT = 15 * 1000; // [ms]
    private static final long ERROR_DELAY_MIN = 1;
    private static final long OK_DELAY_MIN = 5;
    private final Map<String, RemoteServerState> actualStateMap;
    private ServerStatusTask serverStatusTask;
    private ScheduledTaskRunner scheduledTaskRunner;
    private List<RemoteServerStateListener> listeners = Collections.synchronizedList(new ArrayList<>());
    protected final List<RemoteServerData> remoteServerDataList;

    public RemoteServerConfigurationImpl(RemoteServerConfigurationImpl remoteServerConfiguration){
        actualStateMap = remoteServerConfiguration.actualStateMap;
        serverStatusTask = remoteServerConfiguration.serverStatusTask;
        scheduledTaskRunner = remoteServerConfiguration.scheduledTaskRunner;
        listeners = remoteServerConfiguration.listeners;
        remoteServerDataList = remoteServerConfiguration.remoteServerDataList;
    }

    public RemoteServerConfigurationImpl(List<RemoteServerData> serverData, TaskRunner taskRunner) {
        actualStateMap = new ConcurrentHashMap<>();
        remoteServerDataList = new ArrayList<>(serverData);
        for (RemoteServerData remoteServerData : serverData) {
            actualStateMap.put(remoteServerData.getServerName(),
                    new RemoteServerState(RemoteServerState.State.NOT_CONNECTED, remoteServerData.getUsername(), null));
        }

        if (taskRunner == null || !(taskRunner instanceof ScheduledTaskRunner)) {
            logger.info("Task runner isn't ScheduledTaskRunner. Remote server status task won't be scheduled.");
        } else {
            scheduledTaskRunner = (ScheduledTaskRunner) taskRunner;
        }
    }

    @Override
    public List<RemoteServerData> getServerList() {
        return Collections.unmodifiableList(remoteServerDataList);
    }

    @Override
    public RemoteServerData getServerConfig(String serverName) {
        if (serverName == null) {
            return null;
        }

        for (RemoteServerData remoteServerData : remoteServerDataList) {
            String configServerName = remoteServerData.getServerName();
            if (configServerName == null) {
                continue;
            }
            if (configServerName.toLowerCase().equals(serverName.toLowerCase())) {
                return remoteServerData;
            }
        }
        return null;
    }

    @Override
    public RemoteServerState getActualState(String remoteServerName) {
        scheduleTask();
        return actualStateMap.get(remoteServerName);
    }

    @Override
    public void addListener(RemoteServerStateListener listener) {
        scheduleTask();
        listeners.add(listener);
    }

    @Override
    public void removeListener(RemoteServerStateListener listener) {
        listeners.remove(listener);
    }

    private synchronized void scheduleTask() {
        if (scheduledTaskRunner != null && serverStatusTask == null) {
            serverStatusTask = new ServerStatusTask();
            ServerStatusListener serverStatusListener = new ServerStatusListener();
            scheduledTaskRunner.runScheduled(serverStatusTask, serverStatusListener, 0, ERROR_DELAY_MIN, TimeUnit.MINUTES);
        }
    }

    private RemoteServerState checkServerAvailability(RemoteServerData remoteServerData) {
        if (remoteServerData.getServerName().equals(RemoteDescriptorProvider.DATACLOUD_SERVER_NAME)) {
            return checkDataCloudServerAvailability(remoteServerData);
        } else {
            return checkOtherServerAvailability(remoteServerData);
        }
    }

    private RemoteServerState checkDataCloudServerAvailability(RemoteServerData remoteServerData) {
        DataCloudUser dataCloudUserInfo = null;
        try {
            ComponentRESTClient restClient =
                    new ComponentRESTClient(remoteServerData.getUrl(), remoteServerData.getUsername(),
                            remoteServerData.getPassword());
            dataCloudUserInfo = restClient.getDataCloudUserInfo();
        } catch (Exception e) {
            logger.warn("DataCloud server connection problem: " + e.getMessage());
            return new RemoteServerState(RemoteServerState.State.ERROR, remoteServerData.getUsername(), e.getMessage());
        }
        RemoteServerState.State state;
        if (dataCloudUserInfo.getCredit() != null && dataCloudUserInfo.getCredit() > 0) {
            state = RemoteServerState.State.OK;
        } else {
            state = RemoteServerState.State.NO_CREDIT;
        }
        return new RemoteServerState(state, dataCloudUserInfo.getEmail(), dataCloudUserInfo.getRealName(),
                dataCloudUserInfo.getCredit(), dataCloudUserInfo.isEmailConfirmed());
    }

    private RemoteServerState checkOtherServerAvailability(RemoteServerData remoteServerData) {
        try (Socket socket = new Socket()) {
            URL siteURL = new URL(remoteServerData.getUrl());
            int port = siteURL.getPort();
            if (port <= 0) {
                port = siteURL.getDefaultPort();
            }
            InetSocketAddress endpoint = new InetSocketAddress(siteURL.getHost(), port);
            socket.connect(endpoint, TEST_CONNECTION_TIMEOUT);
            final boolean connectionCheckResult = socket.isConnected();
            if (connectionCheckResult) {
                return new RemoteServerState(RemoteServerState.State.OK, remoteServerData.getUsername(), null);
            } else {
                return new RemoteServerState(RemoteServerState.State.ERROR, remoteServerData.getUsername(), null);
            }
        } catch (IOException e) {
            logger.warn(
                    "Server '" + remoteServerData.getServerName() + "(" + remoteServerData.getUrl() + ")' is down: "
                            + e.getMessage());
            return new RemoteServerState(RemoteServerState.State.ERROR, remoteServerData.getUsername(), e.getMessage());
        }
    }

    private class ServerStatusTask implements Task {

        private List<String> stateChanged;

        private long iterCounter = 0;

        @Override
        public void execute() throws Exception {
            stateChanged = new ArrayList<>();
            if(iterCounter % OK_DELAY_MIN == 0) {
                for (RemoteServerData remoteServerData : remoteServerDataList) {
                   checkStatus(remoteServerData);
                }
            }else {
                Set<String> errorServers = getErrorServers();
                for (String errorServer : errorServers) {
                    RemoteServerData remoteServerData = getServerConfig(errorServer);
                    checkStatus(remoteServerData);
                }
            }
            iterCounter++;
        }

        private void checkStatus(RemoteServerData remoteServerData){
            String serverName = remoteServerData.getServerName();
            RemoteServerState state = checkServerAvailability(remoteServerData);
            RemoteServerState oldState = actualStateMap.get(serverName);
            if (!state.equals(oldState)) { //old state can be null - new remote server.
                actualStateMap.put(serverName, state);
                stateChanged.add(serverName);
            }
        }

        public List<String> getStateChanged() {
            return stateChanged;
        }
    }

    private Set<String> getErrorServers(){
        Set<String> errorServers = new HashSet<>();
        for (Map.Entry<String, RemoteServerState> serverStateEntry : actualStateMap.entrySet()) {
            if(serverStateEntry.getValue().getActualState() == RemoteServerState.State.ERROR){
                errorServers.add(serverStateEntry.getKey());
            }
        }
        return errorServers;
    }

    private class ServerStatusListener implements TaskListener {

        @Override
        public void onBegin(final Task task) {

        }

        @Override
        public void onComplete(final Task task) {
            ServerStatusTask serverStatusTask = (ServerStatusTask) task;
            for (String changeServerName : serverStatusTask.getStateChanged()) {
                for (RemoteServerStateListener listener : listeners) {
                    RemoteServerState remoteServerState = actualStateMap.get(changeServerName);
                    logger.info("Remote server {} has new state {}", changeServerName, remoteServerState);
                    listener.onRemoteServerStateChange(changeServerName, remoteServerState);
                }
            }
        }

        @Override
        public void onError(final Task task, final Throwable throwable) {
            logger.error("Error in Remote server status task.", throwable);
        }
    }
}
