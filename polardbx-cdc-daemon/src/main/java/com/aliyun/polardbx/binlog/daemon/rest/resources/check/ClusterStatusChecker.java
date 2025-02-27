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
package com.aliyun.polardbx.binlog.daemon.rest.resources.check;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.polardbx.binlog.ClusterTypeEnum;
import com.aliyun.polardbx.binlog.CommonUtils;
import com.aliyun.polardbx.binlog.ConfigKeys;
import com.aliyun.polardbx.binlog.DynamicApplicationConfig;
import com.aliyun.polardbx.binlog.SpringContextHolder;
import com.aliyun.polardbx.binlog.daemon.rest.entities.ServerInfo;
import com.aliyun.polardbx.binlog.dao.BinlogTaskConfigDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.BinlogTaskConfigMapper;
import com.aliyun.polardbx.binlog.dao.BinlogTaskInfoDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.BinlogTaskInfoMapper;
import com.aliyun.polardbx.binlog.dao.DumperInfoMapper;
import com.aliyun.polardbx.binlog.dao.NodeInfoDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.NodeInfoMapper;
import com.aliyun.polardbx.binlog.dao.TaskHeartbeatMapper;
import com.aliyun.polardbx.binlog.dao.XStreamDynamicSqlSupport;
import com.aliyun.polardbx.binlog.dao.XStreamMapper;
import com.aliyun.polardbx.binlog.domain.Cursor;
import com.aliyun.polardbx.binlog.domain.TaskType;
import com.aliyun.polardbx.binlog.domain.po.BinlogTaskConfig;
import com.aliyun.polardbx.binlog.domain.po.BinlogTaskInfo;
import com.aliyun.polardbx.binlog.domain.po.DumperInfo;
import com.aliyun.polardbx.binlog.domain.po.NodeInfo;
import com.aliyun.polardbx.binlog.domain.po.XStream;
import com.aliyun.polardbx.binlog.error.PolardbxException;
import com.aliyun.polardbx.binlog.error.RetryableException;
import com.aliyun.polardbx.binlog.scheduler.model.ExecutionConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.joda.time.DateTime;
import org.mybatis.dynamic.sql.SqlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ClusterStatusChecker {

    private static final Logger logger = LoggerFactory.getLogger(ClusterStatusChecker.class);
    private final static Gson GSON = new GsonBuilder().create();
    private StringBuilder errorLog = new StringBuilder();

    public String check() {

        try {
            ClusterTypeEnum clusterTypeEnum = ClusterTypeEnum.valueOf(DynamicApplicationConfig.getClusterType());
            if (clusterTypeEnum != ClusterTypeEnum.BINLOG_X &&
                clusterTypeEnum != ClusterTypeEnum.BINLOG) {
                return "OK";
            }
            ServerInfo serverInfo = buildServerInfo();

            if (filterEmptyRole(serverInfo)) {
                return "OK";
            }

            //topology config size
            checkTopologyConfigSize(serverInfo);

            if (clusterTypeEnum == ClusterTypeEnum.BINLOG) {
                //node info
                checkNodeInfo(serverInfo);
                //dumper count
                checkDumperCount(serverInfo);
            } else if (clusterTypeEnum == ClusterTypeEnum.BINLOG_X) {
                checkStreamCount(serverInfo);
            }

            //task count
            checkTaskCount(serverInfo, clusterTypeEnum);

            //dumper heartbeat
            checkDumperHeartbeat(serverInfo);

            //task heartbeat
            checkTaskHeartbeat(serverInfo);

            // 如果前面已经失败了，那么下面更高阶的判断就没必要继续了(主要是比较耗时)
            if (errorLog.length() == 0) {
                if (clusterTypeEnum == ClusterTypeEnum.BINLOG) {
                    checkDumperCursorAligned();
                } else if (clusterTypeEnum == ClusterTypeEnum.BINLOG_X) {
                    checkBinlogXCursorAligned();
                }
                queryBinaryLog(clusterTypeEnum);
            }

            String cause = errorLog.toString();

            if (errorLog.length() > 0) {
                logger.info("server status is abnormal, the cause is " + cause);
            } else {
                logger.info("server status is ok.");
            }

            return StringUtils.isBlank(cause) ? "OK" : "ERROR : " + cause;
        } catch (Throwable t) {
            logger.info("something goes wrong when build status.", t);
            errorLog.append(t.getMessage());
            return "ERROR : " + errorLog.toString();
        }
    }

    private boolean filterEmptyRole(ServerInfo serverInfo) {
        // 兼容性逻辑，调度重构之前的版本，NodeInfo的role字段为空，为了保证平滑升级，验证时将role为空的node排除
        List<String> roleEmptyNodes =
            serverInfo.getNodeInfo().stream().map(NodeInfo::getRole).filter(StringUtils::isBlank)
                .collect(Collectors.toList());
        return !roleEmptyNodes.isEmpty();
    }

    private void checkTopologyConfigSize(ServerInfo serverInfo) {
        boolean status = serverInfo.getTopologyConfig().size() > 0;
        if (!status) {
            errorLog.append("Topology config is not ready;");
        }
    }

    private void checkNodeInfo(ServerInfo serverInfo) {
        boolean status = serverInfo.getTopologyConfig().stream()
            .anyMatch(n -> StringUtils
                .equals(n.getContainerId(), DynamicApplicationConfig.getString(ConfigKeys.INST_ID)));
        if (!status) {
            errorLog.append("Node is not in topology config;");
        }
    }

    private void checkDumperCount(ServerInfo serverInfo) {
        Set<String> expectedDumpers = serverInfo.getTopologyConfig().stream()
            .filter(c -> TaskType.Dumper.name().equals(c.getRole())).map(BinlogTaskConfig::getTaskName)
            .collect(Collectors.toSet());
        Set<String> actualDumpers =
            serverInfo.getDumperInfo().stream().map(DumperInfo::getTaskName).collect(Collectors.toSet());
        boolean status = expectedDumpers.equals(actualDumpers);
        if (!status) {
            errorLog.append(String.format("Dumper count is not ready, current running dumpers is %s ,"
                + " expected dumpers is %s;", actualDumpers, expectedDumpers));
        }
    }

    private void checkTaskCount(ServerInfo serverInfo, ClusterTypeEnum clusterTypeEnum) {
        String taskType = "";
        if (clusterTypeEnum == ClusterTypeEnum.BINLOG) {
            taskType = TaskType.Final.name();
        } else if (clusterTypeEnum == ClusterTypeEnum.BINLOG_X) {
            taskType = TaskType.Dispatcher.name();
        }
        String finalTaskType = taskType;
        Set<String> expectedTasks = serverInfo.getTopologyConfig().stream()
            .filter(t -> finalTaskType.equals(t.getRole()) || TaskType.Relay.name().equals(t.getRole()))
            .map(BinlogTaskConfig::getTaskName).collect(Collectors.toSet());
        Set<String> actualTasks = serverInfo.getTaskInfo().stream().map(BinlogTaskInfo::getTaskName).collect(
            Collectors.toSet());
        boolean status = expectedTasks.equals(actualTasks);
        if (!status) {
            errorLog
                .append(String.format("Task count is not ready, current running tasks is %s , expected tasks is %s ;",
                    actualTasks, expectedTasks));
        }
    }

    private void checkDumperHeartbeat(ServerInfo serverInfo) {
        String clusterId = DynamicApplicationConfig.getString(ConfigKeys.CLUSTER_ID);
        TaskHeartbeatMapper taskHeartbeatMapper = SpringContextHolder.getObject(TaskHeartbeatMapper.class);
        long time1 = System.currentTimeMillis();
        boolean status = taskHeartbeatMapper.maxDumperHeartbeatDelay(clusterId)
            < DynamicApplicationConfig.getInt(ConfigKeys.TOPOLOGY_TASK_HEARTBEAT_INTERVAL) * 2;
        if (!status) {
            errorLog.append("Dumper heartbeat time is abnormal, current time is ")
                .append(time1)
                .append(", heartbeat time is ")
                .append(serverInfo.getDumperInfo().stream().map(DumperInfo::getGmtHeartbeat).map(
                    Date::getTime).collect(Collectors.toList())).append(";");
        }
    }

    private void checkTaskHeartbeat(ServerInfo serverInfo) {
        TaskHeartbeatMapper taskHeartbeatMapper = SpringContextHolder.getObject(TaskHeartbeatMapper.class);
        String clusterId = DynamicApplicationConfig.getString(ConfigKeys.CLUSTER_ID);
        long time2 = System.currentTimeMillis();
        boolean status = taskHeartbeatMapper.maxTaskHeartbeatDelay(clusterId)
            < DynamicApplicationConfig.getInt(ConfigKeys.TOPOLOGY_TASK_HEARTBEAT_INTERVAL) * 2;
        if (!status) {
            errorLog.append("Task heartbeat is abnormal, current time is ")
                .append(time2)
                .append(", heartbeat time is ")
                .append(serverInfo.getTaskInfo().stream().map(
                    BinlogTaskInfo::getGmtHeartbeat).map(Date::getTime).collect(Collectors.toList())).append(";");
        }
    }

    private void queryBinaryLog(ClusterTypeEnum clusterTypeEnum) {
        String sql = null;
        if (clusterTypeEnum == ClusterTypeEnum.BINLOG) {
            sql = "show binary logs";
        } else if (clusterTypeEnum == ClusterTypeEnum.BINLOG_X) {
            sql = "show binary streams";
        }
        JdbcTemplate jdbcTemplate = SpringContextHolder.getObject("polarxJdbcTemplate");
        jdbcTemplate.execute(sql);
    }

    private void checkStreamCount(ServerInfo serverInfo) {
        List<String> expectedDumpers = serverInfo.getTopologyConfig().stream()
            .filter(c -> TaskType.DumperX.name().equals(c.getRole())).map(BinlogTaskConfig::getConfig)
            .collect(Collectors.toList());

        AtomicInteger streamCount = new AtomicInteger(0);
        expectedDumpers.forEach(c -> {
            ExecutionConfig config = GSON.fromJson(c, ExecutionConfig.class);
            streamCount.addAndGet(config.getStreamNameSet().size());
        });

        boolean status = NumberUtils
            .compare(streamCount.get(), DynamicApplicationConfig.getInt(ConfigKeys.BINLOG_X_STREAM_COUNT)) == 0;
        if (!status) {
            errorLog.append(String
                .format("stream count not equal config count , topology stream count is %s, config count is %s",
                    streamCount.get(), DynamicApplicationConfig.getInt(ConfigKeys.BINLOG_X_STREAM_COUNT)));
        }
    }

    private ServerInfo buildServerInfo() {
        BinlogTaskConfigMapper binlogTaskConfigMapper = SpringContextHolder.getObject(BinlogTaskConfigMapper.class);
        NodeInfoMapper nodeInfoMapper = SpringContextHolder.getObject(NodeInfoMapper.class);
        BinlogTaskInfoMapper taskInfoMapper = SpringContextHolder.getObject(BinlogTaskInfoMapper.class);
        DumperInfoMapper dumperInfoMapper = SpringContextHolder.getObject(DumperInfoMapper.class);

        String clusterId = DynamicApplicationConfig.getString(ConfigKeys.CLUSTER_ID);
        String polarxInstanceId = DynamicApplicationConfig.getString(ConfigKeys.POLARX_INST_ID);

        List<BinlogTaskConfig> topologyConfigs =
            binlogTaskConfigMapper.select(s -> s.where(BinlogTaskConfigDynamicSqlSupport.clusterId,
                SqlBuilder.isEqualTo(clusterId)));
        List<NodeInfo> nodeInfoList = nodeInfoMapper
            .select(s -> s.where(NodeInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId))
                .and(NodeInfoDynamicSqlSupport.status, SqlBuilder.isEqualTo(0)));
        List<BinlogTaskInfo> taskInfoList = taskInfoMapper
            .select(s -> s.where(BinlogTaskInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId)));
        List<DumperInfo> dumperInfoList = dumperInfoMapper
            .select(s -> s.where(BinlogTaskInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId)));

        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setTopologyConfig(topologyConfigs);
        serverInfo.setClusterId(clusterId);
        serverInfo.setPolarxInstanceId(polarxInstanceId);
        serverInfo.setNodeInfo(nodeInfoList);
        serverInfo.setTaskInfo(taskInfoList);
        serverInfo.setDumperInfo(dumperInfoList);

        return serverInfo;
    }

    private void checkBinlogXCursorAligned() throws Throwable {
        int timeoutThresholdMs = DynamicApplicationConfig.getInt(ConfigKeys.TOPOLOGY_TASK_HEARTBEAT_INTERVAL);
        XStreamMapper xStreamMapper = SpringContextHolder.getObject(XStreamMapper.class);
        RetryTemplate template = RetryTemplate.builder().maxAttempts(15).fixedBackoff(1000)
            .retryOn(RetryableException.class).build();

        String groupName = DynamicApplicationConfig.getString(ConfigKeys.BINLOG_X_STREAM_GROUP_NAME);
        final Set<Cursor> allCursors = template.execute((RetryCallback<Set<Cursor>, Throwable>) retryContext -> {
            List<XStream> xStreamList = xStreamMapper
                .select(s -> s.where(XStreamDynamicSqlSupport.groupName, SqlBuilder.isEqualTo(groupName)));

            if (!xStreamList.stream().allMatch(s -> StringUtils.isNotBlank(s.getLatestCursor()))) {
                logger.warn("wait for log cursor ready failed, ready dumpers are {}",
                    xStreamList.stream().map(XStream::getStreamName).collect(Collectors.toList()));
                throw new RetryableException("wait for all dumpers ready failed.");
            }

            return xStreamList.stream()
                .map(XStream::getLatestCursor)
                .map(i -> JSONObject.parseObject(i, Cursor.class))
                .collect(Collectors.toSet());
        });

        String maxTSOS = allCursors.stream().map(Cursor::getTso).max(Comparator.comparing(s -> s)).get();
        String minTSOS = allCursors.stream().map(Cursor::getTso).min(Comparator.comparing(s -> s)).get();

        Long maxTSO = CommonUtils.getTsoPhysicalTime(maxTSOS, TimeUnit.MILLISECONDS);
        Long minTSO = CommonUtils.getTsoPhysicalTime(minTSOS, TimeUnit.MILLISECONDS);

        // tso 200 + flush interval 1000
        // 只考虑是否对齐，不检测是否延迟，所以忽略heartbeat 30s
        if (maxTSO - minTSO > timeoutThresholdMs + 2000) {
            throw new PolardbxException(
                "cursor is not ready, max cursor tso is {" + maxTSOS + "}, min cursor tso is {" + minTSOS
                    + "}.");
        }

    }

    private void checkDumperCursorAligned() throws Throwable {
        String clusterId = DynamicApplicationConfig.getString(ConfigKeys.CLUSTER_ID);
        String instId = DynamicApplicationConfig.getString(ConfigKeys.INST_ID);
        int timeoutThresholdMs = DynamicApplicationConfig.getInt(ConfigKeys.TOPOLOGY_HEARTBEAT_TIMEOUT_MS);
        NodeInfoMapper nodeInfoMapper = SpringContextHolder.getObject(NodeInfoMapper.class);
        RetryTemplate template = RetryTemplate.builder().maxAttempts(15).fixedBackoff(1000)
            .retryOn(RetryableException.class).build();

        final Set<Cursor> allCursors = template.execute((RetryCallback<Set<Cursor>, Throwable>) retryContext -> {
            List<NodeInfo> nodeInfoList = nodeInfoMapper
                .select(s -> s.where(NodeInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId))
                    .and(NodeInfoDynamicSqlSupport.gmtHeartbeat,
                        SqlBuilder.isGreaterThan(DateTime.now().minusMillis(timeoutThresholdMs).toDate())));

            if (!nodeInfoList.stream().allMatch(s -> StringUtils.isNotBlank(s.getLatestCursor()))) {
                logger.warn("wait for log cursor ready failed, ready dumpers are {}",
                    nodeInfoList.stream().map(NodeInfo::getContainerId).collect(Collectors.toList()));
                throw new RetryableException("wait for all dumpers ready failed.");
            }

            return nodeInfoList.stream()
                .map(NodeInfo::getLatestCursor)
                .map(i -> JSONObject.parseObject(i, Cursor.class))
                .collect(Collectors.toSet());
        });

        template.execute((RetryCallback<Boolean, Throwable>) retryContext -> {
            Cursor lastMaxCursor = allCursors.stream().max(Comparator.comparing(s -> s)).get();
            Cursor thisCursor = nodeInfoMapper
                .select(s -> s.where(NodeInfoDynamicSqlSupport.clusterId, SqlBuilder.isEqualTo(clusterId))
                    .and(NodeInfoDynamicSqlSupport.containerId, SqlBuilder.isEqualTo(instId)))
                .stream().map(NodeInfo::getLatestCursor).map(i -> JSONObject.parseObject(i, Cursor.class))
                .findFirst().get();

            if (thisCursor.compareTo(lastMaxCursor) >= 0) {
                return true;
            } else {
                throw new RetryableException(
                    "cursor is not ready, max cursor is {" + lastMaxCursor + "}, min cursor is {" + thisCursor
                        + "}.");
            }
        });
    }
}
