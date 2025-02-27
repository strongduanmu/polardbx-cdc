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
package com.aliyun.polardbx.rpl.filter;

import com.aliyun.polardbx.binlog.canal.binlog.dbms.DBMSAction;
import com.aliyun.polardbx.rpl.applier.StatisticalProxy;
import com.aliyun.polardbx.rpl.taskmeta.DataImportMeta;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author jiyue 2021/8/17 17:57
 * @since 5.0.0.0
 */
@Data
@Slf4j
public class DataImportFilter extends BaseFilter {

    private DataImportMeta.PhysicalMeta importMeta;
    private Set<String> doDbs;
    private Map<String, Set<String>> doTables;
    private Map<String, String> dstDbMapping;
    private Map<String, String> tableNameMapping;
    private Set<Long> ignoreServerIds;
    private Map<String, Boolean> filterCache;

    public DataImportFilter(DataImportMeta.PhysicalMeta importMeta) {
        this.importMeta = importMeta;
    }

    @Override
    public boolean init() {
        try {
            doTables = importMeta.getPhysicalDoTableList();
            doDbs = importMeta.getSrcDbList();
            dstDbMapping = importMeta.getDstDbMapping();
            tableNameMapping = importMeta.getRewriteTableMapping();
            ignoreServerIds = initIgnoreServerIds(importMeta.getIgnoreServerIds());
            log.warn("ignore server ids: {}", ignoreServerIds);
            filterCache = new HashMap<>(128);
            return true;
        } catch (Throwable e) {
            log.error("DataImportFilter init failed", e);
            return false;
        }
    }


    @Override
    public boolean ignoreEvent(String schema, String tbName, DBMSAction action, long serverId) {
        if (ignoreServerIds.contains(serverId)) {
            return true;
        }
        String key = schema + "." + tbName + "." + action.name();
        if (filterCache.containsKey(key)) {
            return filterCache.get(key);
        }

        boolean skip = !dbOk(schema) || !tableOk(schema, tbName);
        filterCache.put(key, skip);

        if (skip) {
            StatisticalProxy.getInstance().addSkipCount(1);
        }
        return skip;
    }

    /**
     * refer: https://github.com/mysql/mysql-server/blob/8.0/sql/rpl_filter.cc bool
     * Rpl_filter::tables_ok(const char *db, TABLE_LIST *tables)
     */
    private boolean tableOk(String db, String tb) {
        if (!doTables.containsKey(db)) {
            return false;
        }
        if (doTables.get(db).contains(tb)) {
            return true;
        }
        // 如果doTables没有（源库为空或者广播表）
        if (doTables.get(db).size() == 0) {
            return false;
        }
        return doTables.get(db).size() == 0;
    }

    /**
     * refer: https://github.com/mysql/mysql-server/blob/8.0/sql/rpl_filter.cc bool
     * Rpl_filter::db_ok(const char *db, bool need_increase_counter)
     */
    private boolean dbOk(String db) {
        if (doDbs.size() > 0) {
            return doDbs.contains(db);
        }
        return true;
    }

    @Override
    public String getRewriteTable(String table) {
        return (tableNameMapping != null && tableNameMapping.containsKey(table)) ?
            tableNameMapping.get(table) : table;
    }

    @Override
    public String getRewriteDb(String schema, DBMSAction action) {
        return (dstDbMapping != null && dstDbMapping.containsKey(schema)) ?
            dstDbMapping.get(schema) : schema;
    }

}

