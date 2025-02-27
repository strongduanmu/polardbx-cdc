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
package com.aliyun.polardbx.binlog.format.field;

import com.aliyun.polardbx.binlog.format.field.datatype.CreateField;
import com.aliyun.polardbx.binlog.format.field.domain.MDate;
import com.aliyun.polardbx.binlog.format.utils.MySQLType;

public class NewDateField extends Field {

    public NewDateField(CreateField createField) {
        super(createField);
    }

    @Override
    public byte[] encodeInternal() {
        String value = buildDataStr();
        MDate date = new MDate();
        date.parse(value);
        long tmp = date.getDay() + date.getMonth() * 32 + date.getYear() * 16 * 32;
        return toByte(tmp, 3);
    }

    @Override
    public byte[] doGetTableMeta() {
        return new byte[0];
    }

    @Override
    public MySQLType getMysqlType() {
        return MySQLType.MYSQL_TYPE_DATE;
    }
}
