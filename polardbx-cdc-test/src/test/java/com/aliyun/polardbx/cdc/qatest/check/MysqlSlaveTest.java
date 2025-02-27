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
package com.aliyun.polardbx.cdc.qatest.check;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.polardbx.cdc.qatest.base.RplBaseTestCase;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assert;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static com.aliyun.polardbx.cdc.qatest.base.JdbcUtil.getColumnNameList;
import static com.aliyun.polardbx.cdc.qatest.base.PropertiesUtil.usingBinlogX;

/**
 * created by ziyang.lb
 **/
@Slf4j
public class MysqlSlaveTest extends RplBaseTestCase {

    @Test
    public void checkSlaveStatus() {
        if (usingBinlogX) {
            check(getCdcSyncDbConnectionFirst(), "binlog-x-first");
            check(getCdcSyncDbConnectionSecond(), "binlog-x-second");
            check(getCdcSyncDbConnectionThird(), "binlog-x-third");
        } else {
            check(getCdcSyncDbConnection(), "global-binlog");
        }
    }

    @SneakyThrows
    private void check(Connection connection, String slaveType) {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("show slave status");
            if (rs.next()) {
                String e1 = null;
                String e2 = null;
                List<String> columns = getColumnNameList(rs);
                List<Pair<String, String>> result = new ArrayList<>();

                for (String c : columns) {
                    String str = rs.getString(c);
                    result.add(Pair.of(c, str));
                    if (StringUtils.equals("Last_Error", c)) {
                        e1 = str;
                    }
                    if (StringUtils.equals("Last_SQL_Error", c)) {
                        e2 = str;
                    }
                }

                Assert.assertTrue(JSONObject.toJSONString(result, true), StringUtils.isBlank(e1));
                Assert.assertTrue(JSONObject.toJSONString(result, true), StringUtils.isBlank(e2));
                log.info("show slave status result for {} is \r\n {}", slaveType,
                    JSONObject.toJSONString(result, true));
            }
        }
    }
}
