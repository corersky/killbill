/*
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.util.nodes;

import java.io.IOException;
import java.util.List;

import org.killbill.CreatorName;
import org.killbill.billing.broadcast.BroadcastApi;
import org.killbill.billing.osgi.api.PluginInfo;
import org.killbill.billing.osgi.api.PluginsInfoApi;
import org.killbill.billing.util.nodes.dao.NodeInfoDao;
import org.killbill.billing.util.nodes.dao.NodeInfoModelDao;
import org.killbill.billing.util.nodes.json.NodeInfoModelJson;
import org.killbill.billing.util.nodes.json.PluginInfoModelJson;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;

public class DefaultKillbillNodesApi implements KillbillNodesApi {

    private final Logger logger = LoggerFactory.getLogger(DefaultKillbillNodesApi.class);

    private final NodeInfoDao nodeInfoDao;
    private final BroadcastApi broadcastApi;
    private final NodeInfoMapper mapper;
    private final Clock clock;
    private final PluginsInfoApi pluginInfoApi;

    @Inject
    public DefaultKillbillNodesApi(final NodeInfoDao nodeInfoDao, final BroadcastApi broadcastApi, final NodeInfoMapper mapper, final Clock clock, final PluginsInfoApi pluginInfoApi) {
        this.nodeInfoDao = nodeInfoDao;
        this.broadcastApi = broadcastApi;
        this.pluginInfoApi = pluginInfoApi;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    public Iterable<NodeInfo> getNodesInfo() {
        final List<NodeInfoModelDao> allNodes = nodeInfoDao.getAll();

        final Iterable<NodeInfoModelJson> allModelNodes = Iterables.transform(allNodes, new Function<NodeInfoModelDao, NodeInfoModelJson>() {
            @Override
            public NodeInfoModelJson apply(final NodeInfoModelDao input) {
                try {
                    return mapper.deserializeNodeInfo(input.getNodeInfo());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return Iterables.transform(allModelNodes, new Function<NodeInfoModelJson, NodeInfo>() {
            @Override
            public NodeInfo apply(final NodeInfoModelJson input) {
                return new DefaultNodeInfo(input);
            }
        });
    }

    @Override
    public void triggerNodeCommand(final NodeCommand nodeCommand, final boolean localNodeOnly) {

        final String event;
        try {
            event = mapper.serializeNodeCommand(nodeCommand.getNodeCommandMetadata());
            broadcastApi.broadcast(DefaultKillbillNodesService.NODES_SERVICE_NAME, nodeCommand.getNodeCommandType(), event, clock.getUTCNow(), "unset", localNodeOnly);

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void notifyPluginChanged(final PluginInfo plugin) {
        final String updatedNodeInfoJson;
        try {
            updatedNodeInfoJson = computeLatestNodeInfo();
            nodeInfoDao.updateNodeInfo(CreatorName.get(), updatedNodeInfoJson);
        } catch (final IOException e) {
            logger.warn("Failed to update nodeInfo after plugin change", e);
        }
    }


    private String computeLatestNodeInfo() throws IOException {

        final NodeInfoModelDao nodeInfo = nodeInfoDao.getByNodeName(CreatorName.get());
        NodeInfoModelJson nodeInfoJson = mapper.deserializeNodeInfo(nodeInfo.getNodeInfo());

        final Iterable<PluginInfo> rawPluginInfo = pluginInfoApi.getPluginsInfo();
        final List<PluginInfo> pluginInfos = rawPluginInfo.iterator().hasNext() ? ImmutableList.<PluginInfo>copyOf(rawPluginInfo) : ImmutableList.<PluginInfo>of();

        final NodeInfoModelJson updatedNodeInfoJson = new NodeInfoModelJson(CreatorName.get(),
                                                                            nodeInfoJson.getBootTime(),
                                                                            clock.getUTCNow(),
                                                                            nodeInfoJson.getKillbillVersion(),
                                                                            nodeInfoJson.getApiVersion(),
                                                                            nodeInfoJson.getPluginApiVersion(),
                                                                            nodeInfoJson.getCommonVersion(),
                                                                            nodeInfoJson.getPlatformVersion(),
                                                                            ImmutableList.copyOf(Iterables.transform(pluginInfos, new Function<PluginInfo, PluginInfoModelJson>() {
                                                                                @Override
                                                                                public PluginInfoModelJson apply(final PluginInfo input) {
                                                                                    return new PluginInfoModelJson(input);
                                                                                }
                                                                            })));

        final String nodeInfoValue = mapper.serializeNodeInfo(updatedNodeInfoJson);
        return nodeInfoValue;
    }
}
