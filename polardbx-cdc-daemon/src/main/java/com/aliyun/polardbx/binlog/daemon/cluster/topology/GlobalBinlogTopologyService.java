/**
 * Copyright (c) 2013-2022, Alibaba Group Holding Limited;
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * </p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aliyun.polardbx.binlog.daemon.cluster.topology;

import com.aliyun.polardbx.binlog.ConfigKeys;
import com.aliyun.polardbx.binlog.DynamicApplicationConfig;
import com.aliyun.polardbx.binlog.dao.BinlogScheduleHistoryMapper;
import com.aliyun.polardbx.binlog.dao.BinlogTaskConfigDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.BinlogTaskConfigMapper;
import com.aliyun.polardbx.binlog.dao.BinlogTaskInfoMapper;
import com.aliyun.polardbx.binlog.dao.DumperInfoMapper;
import com.aliyun.polardbx.binlog.dao.NodeInfoDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.NodeInfoMapper;
import com.aliyun.polardbx.binlog.dao.StorageHistoryInfoMapper;
import com.aliyun.polardbx.binlog.domain.Cursor;
import com.aliyun.polardbx.binlog.domain.StorageContent;
import com.aliyun.polardbx.binlog.domain.TaskType;
import com.aliyun.polardbx.binlog.domain.po.BinlogScheduleHistory;
import com.aliyun.polardbx.binlog.domain.po.BinlogTaskConfig;
import com.aliyun.polardbx.binlog.domain.po.StorageHistoryInfo;
import com.aliyun.polardbx.binlog.domain.po.StorageInfo;
import com.aliyun.polardbx.binlog.error.PolardbxException;
import com.aliyun.polardbx.binlog.error.RetryableException;
import com.aliyun.polardbx.binlog.leader.RuntimeLeaderElector;
import com.aliyun.polardbx.binlog.scheduler.ClusterSnapshot;
import com.aliyun.polardbx.binlog.scheduler.ExecutionSnapshot;
import com.aliyun.polardbx.binlog.scheduler.ResourceManager;
import com.aliyun.polardbx.binlog.scheduler.ScheduleHistoryContent;
import com.aliyun.polardbx.binlog.scheduler.model.Container;
import com.aliyun.polardbx.binlog.scheduler.model.ExecutionConfig;
import com.aliyun.polardbx.binlog.util.SystemDbConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.mybatis.dynamic.sql.SqlBuilder;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aliyun.polardbx.binlog.ConfigKeys.CLUSTER_SNAPSHOT_VERSION_KEY;
import static com.aliyun.polardbx.binlog.ConfigKeys.CLUSTER_SUSPEND_TOPOLOGY_REBUILDING;
import static com.aliyun.polardbx.binlog.ConfigKeys.CLUSTER_TOPOLOGY_DUMPER_MASTER_NODE_KEY;
import static com.aliyun.polardbx.binlog.Constants.GROUP_NAME_GLOBAL;
import static com.aliyun.polardbx.binlog.SpringContextHolder.getObject;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.buildExpectedStorageTso;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.buildStorageHistoryInfo;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.buildStorageInfos;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.checkContainers;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.clearStaleInfos;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.lockAndCheck;
import static com.aliyun.polardbx.binlog.daemon.cluster.topology.TopologyServiceHelper.shouldRefreshTopology;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;

/**
 * Created by ShuGuang & ziyang.lb
 */
@Slf4j
public class GlobalBinlogTopologyService implements TopologyService {
    private static final Gson GSON = new GsonBuilder().create();

    private final GlobalBinlogTopologyBuilder topologyBuilder;
    private final String clusterId;
    private final String clusterType;

    //mappers
    private final BinlogTaskConfigMapper taskConfigMapper = getObject(BinlogTaskConfigMapper.class);

    private final DumperInfoMapper dumperInfoMapper = getObject(DumperInfoMapper.class);

    private final StorageHistoryInfoMapper storageHistoryMapper = getObject(StorageHistoryInfoMapper.class);

    private final BinlogScheduleHistoryMapper scheduleHistoryMapper = getObject(BinlogScheduleHistoryMapper.class);

    private final NodeInfoMapper nodeInfoMapper = getObject(NodeInfoMapper.class);

    private final BinlogTaskInfoMapper taskInfoMapper = getObject(BinlogTaskInfoMapper.class);

    private final TransactionTemplate transactionTemplate = getObject("metaTransactionTemplate");

    private static long lastForceRefreshTime = System.currentTimeMillis();

    public GlobalBinlogTopologyService(String clusterId, String clusterType) {
        this.clusterId = clusterId;
        this.clusterType = clusterType;
        this.topologyBuilder = new GlobalBinlogTopologyBuilder(clusterId);
    }

    @Override
    public void tryBuild() throws Throwable {
        String suspendTopologyRebuilding = SystemDbConfig.getSystemDbConfig(CLUSTER_SUSPEND_TOPOLOGY_REBUILDING);
        if (StringUtils.isNotBlank(suspendTopologyRebuilding) && "true".equals(suspendTopologyRebuilding)) {
            log.info("current cluster is in suspend state , skip rebuilding cluster topology");
            return;
        }

        //check and prepare parameter
        log.info("current daemon is leader, do with the cluster's topology project!");
        ResourceManager resourceManager = new ResourceManager(clusterId);
        checkNode(resourceManager);
        checkContainers(resourceManager);
        String preClusterConfigStr = SystemDbConfig.getSystemDbConfig(CLUSTER_SNAPSHOT_VERSION_KEY);
        ClusterSnapshot preClusterSnapshot = GSON.fromJson(preClusterConfigStr, ClusterSnapshot.class);
        clearStaleInfos(preClusterSnapshot.getVersion());
        String expectedStorageTso = buildExpectedStorageTso();
        ExecutionSnapshot executionSnapshot = resourceManager.getExecutionSnapshot();

        StorageHistoryInfo storageHistoryInfo = buildStorageHistoryInfo(expectedStorageTso);
        List<StorageInfo> storageInfos = buildStorageInfos(storageHistoryInfo);

        if (shouldRefreshTopology(resourceManager, preClusterSnapshot, storageInfos, executionSnapshot,
            storageHistoryInfo)) {
            long newVersion = preClusterSnapshot.getVersion() + 1;
            List<Container> containers = resourceManager.availableContainers();
            String dumperMasterNode = selectDumperMasterNode(
                containers.stream().map(Container::getContainerId).collect(Collectors.toSet()),
                preClusterSnapshot);

            List<BinlogTaskConfig> topologyConfigs = topologyBuilder.buildTopology(containers, storageInfos,
                expectedStorageTso, newVersion, dumperMasterNode);
            ClusterSnapshot postClusterSnapshot = buildPostClusterSnapshot(topologyConfigs,
                containers, storageInfos, newVersion, dumperMasterNode, storageHistoryInfo);
            persist(clusterId, storageHistoryInfo, storageInfos, topologyConfigs, preClusterSnapshot,
                postClusterSnapshot, executionSnapshot);
            log.info("Topology with version {} is successfully build.", newVersion);
        }
    }

    private void checkNode(ResourceManager resourceManager) throws Throwable {
        //抛异常出去也是继续重试，所以尝试多等一些时间，1min
        RetryTemplate retryTemplate = RetryTemplate.builder()
            .maxAttempts(60)
            .fixedBackoff(1000)
            .retryOn(RetryableException.class)
            .build();

        //未达到法定个数，不能进行拓扑计算，避免不必要的的Topology build
        //nodeCount是数据库中所有合法的node记录的个数，如果出现Node节点宕机，记录还在，所以不影响拓扑重建，即不影响HA
        retryTemplate.execute((RetryCallback<Integer, Throwable>) retryContext -> {
            int nodeCount = resourceManager.allContainers().size();
            int topologyNodeMinSize = DynamicApplicationConfig.getInt(ConfigKeys.TOPOLOGY_NODE_MINSIZE);
            if (nodeCount < topologyNodeMinSize) {
                log.warn("wait for container nodes ready(need " + topologyNodeMinSize
                    + " container at least)..., current node count is " + nodeCount);
                throw new RetryableException("cdc cluster is not ready, current node count is " + nodeCount);
            }
            return nodeCount;
        });
    }

    private ClusterSnapshot buildPostClusterSnapshot(List<BinlogTaskConfig> topologyConfigs,
                                                     List<Container> containers,
                                                     List<StorageInfo> storageInfos,
                                                     long newVersion,
                                                     String dumperMasterNode,
                                                     StorageHistoryInfo storageHistoryInfo) {
        Optional<String> dumperMasterOptional = topologyConfigs.stream()
            .filter(c -> TaskType.Dumper.name().equals(c.getRole()) && c.getContainerId().equals(dumperMasterNode))
            .map(BinlogTaskConfig::getTaskName)
            .findFirst();
        String dumperMasterName;
        if (!dumperMasterOptional.isPresent()) {
            throw new PolardbxException("can not found dumper on container " + dumperMasterNode +
                " with topology configs :" + topologyConfigs);
        } else {
            dumperMasterName = dumperMasterOptional.get();
        }

        return new ClusterSnapshot(newVersion,
            System.currentTimeMillis(),
            containers.stream().map(Container::getContainerId).collect(Collectors.toSet()),
            storageInfos.stream().map(StorageInfo::getStorageInstId).collect(Collectors.toSet()),
            dumperMasterNode,
            dumperMasterName,
            storageHistoryInfo == null ? ExecutionConfig.ORIGIN_TSO : storageHistoryInfo.getTso(),
            clusterType);
    }

    private String selectDumperMasterNode(Set<String> containers, ClusterSnapshot preClusterSnapshot) {

        //如果强制指定了master node, 且node状态正常，则使用强制指定的node
        String assignedDumperNode = DynamicApplicationConfig.getString(CLUSTER_TOPOLOGY_DUMPER_MASTER_NODE_KEY);
        if (StringUtils.isNotBlank(assignedDumperNode) && containers.contains(assignedDumperNode)) {
            log.info("Dumper master node is selected by force mode, with name {}", assignedDumperNode);
            return assignedDumperNode;
        }

        //取位点最大的Container对应的Dumper为MasterDumper
        Map<Cursor, List<Pair<Cursor, String>>> cursorsMap =
            nodeInfoMapper.select(s -> s.where(NodeInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId))
                    .and(NodeInfoDynamicSqlSupport.containerId, SqlBuilder.isIn(containers)))
                .stream().filter(d -> StringUtils.isNotBlank(d.getLatestCursor()))
                .map(s -> new ImmutablePair<>(GSON.fromJson(s.getLatestCursor(), Cursor.class), s.getContainerId()))
                .collect(Collectors.groupingBy(Pair::getLeft));
        Optional<Cursor> maxCursorOptional = cursorsMap.keySet().stream().max(Comparator.comparing(s -> s));
        if (maxCursorOptional.isPresent()) {
            //如果有多个container的cursor并列最大，则随机取一个
            String selectedContainer = cursorsMap.get(maxCursorOptional.get()).get(0).getRight();
            log.info("Dumper master node is selected by max cursor mode, with name {} and max cursor {}.",
                selectedContainer, maxCursorOptional.get());
            return selectedContainer;
        }

        //优先取上一次的Node继续当master
        String lastNode = preClusterSnapshot.getDumperMasterNode();
        if (StringUtils.isNotBlank(lastNode) && containers.contains(lastNode)) {
            log.info("Dumper master node is selected by previous mode, with name {}.", lastNode);
            return lastNode;
        }

        //随机取一个container作为MasterNode
        String masterNode = containers.stream().findAny().get();
        log.warn("Dumper master is selected by min-load mode, with name {}.", masterNode);
        return masterNode;
    }

    private void persist(final String cluster, final StorageHistoryInfo storageHistoryInfo,
                         final List<StorageInfo> storageInfos, final List<BinlogTaskConfig> taskConfigs,
                         final ClusterSnapshot preClusterSnapshot, final ClusterSnapshot postClusterSnapshot,
                         final ExecutionSnapshot executionSnapshot) {
        // 持久化之前再次进行一下验证，如果已经不是Leader，则放弃持久化
        if (!RuntimeLeaderElector.isDaemonLeader()) {
            log.info("current daemon is not a leader, skip the topology persisting.!");
            return;
        }

        transactionTemplate.execute((o) -> {
            if (!lockAndCheck(preClusterSnapshot)) {
                return null;
            }

            //执行拓扑保存
            for (BinlogTaskConfig taskConfig : taskConfigs) {
                Optional<BinlogTaskConfig> config = taskConfigMapper.selectOne(
                    s -> s.where(BinlogTaskConfigDynamicSqlSupport.clusterId, isEqualTo(cluster))
                        .and(BinlogTaskConfigDynamicSqlSupport.taskName, isEqualTo(taskConfig.getTaskName())));
                if (config.isPresent()) {
                    BinlogTaskConfig origin = config.get();
                    taskConfig.setId(origin.getId());
                    taskConfig.setStatus(null);
                    taskConfigMapper.updateByPrimaryKeySelective(taskConfig);
                } else {
                    taskConfigMapper.insert(taskConfig);
                }
            }
            Set<String> configs = taskConfigs.stream().map(BinlogTaskConfig::getTaskName).collect(Collectors.toSet());
            taskConfigMapper.delete(
                s -> s.where(BinlogTaskConfigDynamicSqlSupport.taskName, SqlBuilder.isNotIn(configs))
                    .and(BinlogTaskConfigDynamicSqlSupport.clusterId, isEqualTo(cluster)));

            // 删除Dumper_info和Task_info
            dumperInfoMapper.delete(s -> s);
            taskInfoMapper.delete(s -> s);

            //初始化storageHistory
            if (storageHistoryInfo == null) {
                StorageContent content = new StorageContent();
                content.setStorageInstIds(storageInfos.stream()
                    .map(StorageInfo::getStorageInstId).collect(Collectors.toList()));

                StorageHistoryInfo info = new StorageHistoryInfo();
                info.setStatus(0);
                info.setTso(ExecutionConfig.ORIGIN_TSO);
                info.setStorageContent(GSON.toJson(content));
                info.setInstructionId("-1");
                info.setClusterId(clusterId);
                info.setGroupName(GROUP_NAME_GLOBAL);
                storageHistoryMapper.insert(info);
            }

            //对版本号进行+1并更新
            SystemDbConfig.updateSystemDbConfig(CLUSTER_SNAPSHOT_VERSION_KEY, GSON.toJson(postClusterSnapshot));

            //记录历史
            BinlogScheduleHistory history = new BinlogScheduleHistory();
            history.setVersion(postClusterSnapshot.getVersion());
            history.setClusterId(clusterId);
            history.setContent(
                GSON.toJson(new ScheduleHistoryContent(executionSnapshot, taskConfigs, postClusterSnapshot)));
            scheduleHistoryMapper.insert(history);
            return null;
        });
    }

    private boolean needRestart(BinlogTaskConfig newConfig, BinlogTaskConfig oldConfig) {
        // 如果是IP发生了变更，TaskKeepAlive会自动调度，无需设置为"待重启"状态
        if (StringUtils.equals(newConfig.getIp(), oldConfig.getIp())) {
            // 如果是mem或Port发生了变更，靠重启进程来实现
            if (!newConfig.getMem().equals(oldConfig.getMem())) {
                return true;
            }
            if (!newConfig.getPort().equals(oldConfig.getPort())) {
                return true;
            }
        }
        return false;
    }
}
