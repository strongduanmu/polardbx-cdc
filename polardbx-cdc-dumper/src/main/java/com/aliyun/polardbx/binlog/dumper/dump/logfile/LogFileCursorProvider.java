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
package com.aliyun.polardbx.binlog.dumper.dump.logfile;

import com.aliyun.polardbx.binlog.domain.Cursor;
import com.aliyun.polardbx.binlog.event.IEventListener;
import com.aliyun.polardbx.binlog.event.source.LatestFileCursorChangeEvent;
import com.aliyun.polardbx.binlog.remote.io.IFileCursorProvider;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LogFileCursorProvider implements IFileCursorProvider, IEventListener<LatestFileCursorChangeEvent> {

    private final Map<String, Cursor> cursorMap = Maps.newConcurrentMap();

    public LogFileCursorProvider() {
    }

    @Override
    @Subscribe
    public void onEvent(LatestFileCursorChangeEvent event) {
        Cursor cursor = event.getCursor();
        cursorMap.put(cursor.getStream(), cursor);
    }

    @Override
    public Cursor getCursor(String stream) {
        return cursorMap.get(stream);
    }
}
