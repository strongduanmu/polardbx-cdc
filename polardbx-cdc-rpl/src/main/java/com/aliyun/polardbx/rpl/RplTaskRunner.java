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
package com.aliyun.polardbx.rpl;

import com.alibaba.fastjson.JSON;
import com.aliyun.polardbx.binlog.AddressUtil;
import com.aliyun.polardbx.binlog.canal.core.model.BinlogPosition;
import com.aliyun.polardbx.binlog.domain.po.RplService;
import com.aliyun.polardbx.binlog.domain.po.RplStateMachine;
import com.aliyun.polardbx.binlog.domain.po.RplTask;
import com.aliyun.polardbx.binlog.domain.po.RplTaskConfig;
import com.aliyun.polardbx.rpl.applier.BaseApplier;
import com.aliyun.polardbx.rpl.applier.FullCopyApplier;
import com.aliyun.polardbx.rpl.applier.MergeApplier;
import com.aliyun.polardbx.rpl.applier.MergeTransactionApplier;
import com.aliyun.polardbx.rpl.applier.MysqlApplier;
import com.aliyun.polardbx.rpl.applier.RecoveryApplier;
import com.aliyun.polardbx.rpl.applier.SplitApplier;
import com.aliyun.polardbx.rpl.applier.SplitTransactionApplier;
import com.aliyun.polardbx.rpl.applier.StatisticalProxy;
import com.aliyun.polardbx.rpl.applier.TableParallelApplier;
import com.aliyun.polardbx.rpl.applier.TransactionApplier;
import com.aliyun.polardbx.rpl.common.TaskContext;
import com.aliyun.polardbx.rpl.extractor.BaseExtractor;
import com.aliyun.polardbx.rpl.extractor.MysqlBinlogExtractor;
import com.aliyun.polardbx.rpl.extractor.CdcExtractor;
import com.aliyun.polardbx.rpl.extractor.RdsBinlogExtractor;
import com.aliyun.polardbx.rpl.extractor.ReconExtractor;
import com.aliyun.polardbx.rpl.extractor.ValidationExtractor;
import com.aliyun.polardbx.rpl.extractor.flashback.RecoveryExtractor;
import com.aliyun.polardbx.rpl.extractor.full.MysqlFullExtractor;
import com.aliyun.polardbx.rpl.filter.BaseFilter;
import com.aliyun.polardbx.rpl.filter.DataImportFilter;
import com.aliyun.polardbx.rpl.filter.FlashBackFilter;
import com.aliyun.polardbx.rpl.filter.ReplicaFilter;
import com.aliyun.polardbx.rpl.pipeline.BasePipeline;
import com.aliyun.polardbx.rpl.pipeline.SerialPipeline;
import com.aliyun.polardbx.rpl.taskmeta.ApplierConfig;
import com.aliyun.polardbx.rpl.taskmeta.ApplierType;
import com.aliyun.polardbx.rpl.taskmeta.CdcExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.DataImportMeta;
import com.aliyun.polardbx.rpl.taskmeta.DbTaskMetaManager;
import com.aliyun.polardbx.rpl.taskmeta.ExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.ExtractorType;
import com.aliyun.polardbx.rpl.taskmeta.FSMMetaManager;
import com.aliyun.polardbx.rpl.taskmeta.FilterType;
import com.aliyun.polardbx.rpl.taskmeta.FullExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.HostInfo;
import com.aliyun.polardbx.rpl.taskmeta.PipelineConfig;
import com.aliyun.polardbx.rpl.taskmeta.RdsExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.RecoveryApplierConfig;
import com.aliyun.polardbx.rpl.taskmeta.RecoveryExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.RecoveryMeta;
import com.aliyun.polardbx.rpl.taskmeta.ReplicaMeta;
import com.aliyun.polardbx.rpl.taskmeta.ReconExtractorConfig;
import com.aliyun.polardbx.rpl.taskmeta.ValidationExtractorConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @author shicai.xsc 2020/12/8 14:11
 * @since 5.0.0.0
 */
@Data
@Slf4j
public class RplTaskRunner {

    private long taskId;

    private RplTask task;

    private RplTaskConfig taskConfig;

    private BaseExtractor extractor;

    private BasePipeline pipeline;

    private BaseApplier applier;

    private BaseFilter filter;

    private String config;

    private boolean enableStatistic = true;

    public RplTaskRunner(long taskId) {
        this.taskId = taskId;
    }

    public void start() {
        try {
            log.info("RplTaskEngine initializing");
            if (!init()) {
                log.error("RplTaskRunner init failed");
                System.exit(-1);
                return;
            }
            log.info("RplTaskEngine initialized");

            // start task
            log.info("RplTaskEngine starting");
            pipeline.start();
            log.info("RplTaskRunner started");

            // wait task done
            while (!pipeline.checkDone()) {
                Thread.sleep(1000);
            }

            FSMMetaManager.setTaskFinish(taskId);
            log.info("RplTaskRunner done");
        } catch (Throwable e) {
            log.error("RplTaskRunner exited", e);
            System.exit(-1);
        }
    }

    public void stop() {
        pipeline.stop();
    }

    private boolean init() {
        try {
            task = DbTaskMetaManager.getTask(taskId);
            taskConfig = DbTaskMetaManager.getTaskConfig(task.getId());
            RplService service = DbTaskMetaManager.getService(task.getServiceId());
            RplStateMachine stateMachine = DbTaskMetaManager.getStateMachine(service.getStateMachineId());
            if (task == null || service == null || stateMachine == null) {
                log.error("Has been deleted from db");
                System.exit(1);
            }
            config = stateMachine.getConfig();
            log.info("RplTaskRunner init, task id: {}", taskId);

            // do this before init applier and extractor
            TaskContext context = TaskContext.getInstance();
            context.setService(service);
            context.setTask(task);
            context.setTaskConfig(taskConfig);
            context.setWorker(AddressUtil.getHostAddress().getHostAddress());
            context.setStateMachine(stateMachine);
            context.setConfig(config);
            context.setPhysicalNum(0);
            ExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ExtractorConfig.class);
            if (extractorConfig.getFilterType() == FilterType.IMPORT_FILTER.getValue()) {
                DataImportMeta.PhysicalMeta importMeta = JSON.parseObject(
                    extractorConfig.getSourceToTargetConfig(), DataImportMeta.PhysicalMeta.class);
                context.setPhysicalMeta(importMeta);
                DataImportMeta meta = JSON.parseObject(config, DataImportMeta.class);
                context.setPhysicalNum(meta.getMetaList().size());
            }

            log.info("RplTaskRunner prepare filter");
            initFilter();
            log.info("RplTaskRunner prepare extractor");
            initExtractor();
            log.info("RplTaskRunner prepare applier");
            initApplier();
            log.info("RplTaskRunner prepare pipeline");
            initPipeline();
            StatisticalProxy.getInstance().init(pipeline, task.getPosition());
            extractor.setPipeline(pipeline);

            log.info("RplTaskRunner init all");
            return filter.init() && extractor.init() && pipeline.init() && applier.init();
        } catch (Throwable e) {
            log.error("RplTaskRunner init failed", e);
            return false;
        }
    }

    private void initFullExtractor(int extractorType) throws Exception {
        FullExtractorConfig taskExtractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(),
            FullExtractorConfig.class);
        switch (ExtractorType.from(extractorType)) {
        case DATA_IMPORT_FULL:
            extractor =
                new MysqlFullExtractor(taskExtractorConfig, taskExtractorConfig.getHostInfo(), (DataImportFilter) filter);
            break;
        case RPL_FULL:
            extractor =
                new MysqlFullExtractor(taskExtractorConfig, taskExtractorConfig.getHostInfo(), (ReplicaFilter) filter);
            break;
        default:
            break;
        }
    }

    private void initFullValidationExtractor() {
        ValidationExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ValidationExtractorConfig.class);
        ApplierConfig applierConfig = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        HostInfo applierHost = applierConfig.getHostInfo();
        extractor = new ValidationExtractor(extractorConfig, extractorConfig.getHostInfo(), applierHost, (DataImportFilter) filter);
    }

    private void initReconExtractor() {
        ReconExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ReconExtractorConfig.class);
        ApplierConfig applierConfig = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        HostInfo applierHost = applierConfig.getHostInfo();
        extractor = new ReconExtractor(extractorConfig, extractorConfig.getHostInfo(), applierHost, (DataImportFilter) filter);
    }

    private void initCrossCheckFullValExtractor() {
        ValidationExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ValidationExtractorConfig.class);
        ApplierConfig applierConfig = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        HostInfo applierHost = applierConfig.getHostInfo();
        extractor = new ValidationExtractor(extractorConfig, extractorConfig.getHostInfo(), applierHost, (DataImportFilter) filter);
    }

    private void initCrossCheckReconExtractor() {
        ReconExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ReconExtractorConfig.class);
        ApplierConfig applierConfig = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        HostInfo applierHost = applierConfig.getHostInfo();
        extractor = new ReconExtractor(extractorConfig, extractorConfig.getHostInfo(), applierHost, (DataImportFilter) filter);
    }

    private void initIncExtractor(int extractorType) {
        BinlogPosition binlogPosition = null;
        if (StringUtils.isNotBlank(task.getPosition())) {
            binlogPosition = BinlogPosition.parseFromString(task.getPosition());
        }
        ApplierConfig applierConfig = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        switch (ExtractorType.from(extractorType)) {
        case RPL_INC:
            ExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ExtractorConfig.class);
            extractor = new MysqlBinlogExtractor(extractorConfig,
                extractorConfig.getHostInfo(),
                applierConfig.getHostInfo(),
                binlogPosition,
                filter);
            break;
        case DATA_IMPORT_INC:
            RdsExtractorConfig rdsExtractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), RdsExtractorConfig.class);
            extractor = new RdsBinlogExtractor(rdsExtractorConfig,
                rdsExtractorConfig.getHostInfo(),
                applierConfig.getHostInfo(),
                binlogPosition,
                filter);
            break;
        default:
            break;
        }
        // if applier enabled transaction, the extractor should NOT filter
        // TransactionEnd
        ((MysqlBinlogExtractor) extractor)
            .setFilterTransactionEnd(applierConfig.getApplierType() != ApplierType.TRANSACTION.getValue());
    }

    private void initCdcIncExtractor(int extractorType) {
        BinlogPosition binlogPosition = null;
        if (StringUtils.isNotBlank(task.getPosition())) {
            binlogPosition = BinlogPosition.parseFromString(task.getPosition());
        }

        CdcExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), CdcExtractorConfig.class);
        extractor = new CdcExtractor(extractorConfig,
            extractorConfig.getCdcServerIp(),
            extractorConfig.getCdcServerPort(),
            extractorConfig.getHostInfo(),
            filter,
            binlogPosition);
    }

    private void initRecoveryExtractor() {
        RecoveryExtractorConfig extractorConfig =
            JSON.parseObject(taskConfig.getExtractorConfig(), RecoveryExtractorConfig.class);
        extractor = new RecoveryExtractor(extractorConfig, filter);
    }

    private void initExtractor() throws Exception {
        ExtractorConfig config = JSON.parseObject(taskConfig.getExtractorConfig(), ExtractorConfig.class);
        switch (ExtractorType.from(config.getExtractorType())) {
        case DATA_IMPORT_FULL:
        case RPL_FULL:
            initFullExtractor(config.getExtractorType());
            break;
        case DATA_IMPORT_INC:
        case RPL_INC:
            initIncExtractor(config.getExtractorType());
            break;
        case CDC_INC:
            initCdcIncExtractor(config.getExtractorType());
            break;
        case FULL_VALIDATION:
            initFullValidationExtractor();
            break;
        case RECONCILIATION:
            initReconExtractor();
            break;
        case FULL_VALIDATION_CROSSCHECK:
            initCrossCheckFullValExtractor();
            break;
        case RECONCILIATION_CROSSCHECK:
            initCrossCheckReconExtractor();
            break;
        case RECOVERY:
            initRecoveryExtractor();
        default:
            break;
        }

    }

    private void initApplier() {
        ApplierConfig config = JSON.parseObject(taskConfig.getApplierConfig(), ApplierConfig.class);
        ExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ExtractorConfig.class);
        HostInfo hostInfo = config.getHostInfo();

        switch (ApplierType.from(config.getApplierType())) {
        case TRANSACTION:
            applier = new TransactionApplier(config, hostInfo);
            break;
        case SERIAL:
            applier = new MysqlApplier(config, hostInfo);
            break;
        case TABLE_PARALLEL:
            applier = new TableParallelApplier(config, hostInfo);
            break;
        case SPLIT:
            applier = new SplitApplier(config, hostInfo);
            break;
        case SPLIT_TRANSACTION:
            applier = new SplitTransactionApplier(config, hostInfo);
            break;
        case MERGE:
            applier = new MergeApplier(config, hostInfo);
            break;
        case MERGE_TRANSACTION:
            applier = new MergeTransactionApplier(config, hostInfo);
            break;
        case JUST_EXTRACT:
            applier = new BaseApplier(config);
            break;
        case FULL_COPY:
            applier = new FullCopyApplier(config, hostInfo);
            break;
        case RECOVERY:
            initRecoveryApplier();
            break;
        default:
            break;
        }
        if (applier instanceof MysqlApplier) {
            ((MysqlApplier) applier).setSrcHostInfo(extractorConfig.getHostInfo());
        }
    }

    private void initRecoveryApplier() {
        RecoveryApplierConfig config = JSON.parseObject(taskConfig.getApplierConfig(), RecoveryApplierConfig.class);
        applier = new RecoveryApplier(config);
        ((RecoveryApplier) applier).setExtractor(extractor);
        ((RecoveryApplier) applier).setTaskId(taskId);
    }

    private void initPipeline() {
        PipelineConfig pipelineConfig = JSON.parseObject(taskConfig.getPipelineConfig(), PipelineConfig.class);
        pipeline = new SerialPipeline(pipelineConfig, extractor, applier);
    }

    private void initFilter() {
        ExtractorConfig extractorConfig = JSON.parseObject(taskConfig.getExtractorConfig(), ExtractorConfig.class);
        switch (FilterType.from(extractorConfig.getFilterType())) {
        case RPL_FILTER:
            ReplicaMeta replicaMeta = JSON.parseObject(
                extractorConfig.getSourceToTargetConfig(), ReplicaMeta.class);
            filter = new ReplicaFilter(replicaMeta);
            break;
        case IMPORT_FILTER:
            DataImportMeta.PhysicalMeta importMeta = JSON.parseObject(
                extractorConfig.getSourceToTargetConfig(), DataImportMeta.PhysicalMeta.class);
            filter = new DataImportFilter(importMeta);
            break;
        case NO_FILTER:
            filter = new BaseFilter();
            break;
        case FLASHBACK_FILTER:
            RecoveryMeta recoveryMeta = JSON.parseObject(config, RecoveryMeta.class);
            filter = new FlashBackFilter(recoveryMeta);
            break;
        default:
            break;
        }
    }
}
