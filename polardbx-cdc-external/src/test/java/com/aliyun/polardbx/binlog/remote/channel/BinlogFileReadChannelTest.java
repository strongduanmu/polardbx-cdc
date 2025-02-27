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
package com.aliyun.polardbx.binlog.remote.channel;

import com.aliyun.polardbx.binlog.SpringContextBootStrap;
import com.aliyun.polardbx.binlog.channel.BinlogFileReadChannel;
import com.aliyun.polardbx.binlog.remote.Appender;
import com.aliyun.polardbx.binlog.remote.RemoteBinlogProxy;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * @author yudong
 * @since 2022/10/27 20:00
 **/
public class BinlogFileReadChannelTest {
    private static final String fileName = "binlog.000001";
    private static FileChannel localChannel;
    private static BinlogFileReadChannel binlogFileReadChannel;

    @BeforeClass
    public static void prepare() throws IOException {
        // prepare oss client
        final SpringContextBootStrap appContextBootStrap =
            new SpringContextBootStrap("spring/spring.xml");
        appContextBootStrap.boot();

        prepareTestFile();
    }

    @AfterClass
    public static void after() {
        new File(fileName).deleteOnExit();
        RemoteBinlogProxy.getInstance().deleteFile(fileName);
    }

    private static void prepareTestFile() throws IOException {
        if (RemoteBinlogProxy.getInstance().isObjectsExistForPrefix(fileName)) {
            RemoteBinlogProxy.getInstance().deleteFile(fileName);
        }

        Appender appender = RemoteBinlogProxy.getInstance().providerAppender(fileName);
        appender.begin();
        File localFile = new File(fileName);
        if (localFile.exists()) {
            localFile.delete();
        }
        localFile.createNewFile();
        FileOutputStream outputStream = new FileOutputStream(localFile, true);
        byte[] buffer = new byte[1024];
        // file size : 1K ~ 1M bytes
        int n = new Random().nextInt(1024) + 1;
        for (int i = 0; i < n; i++) {
            new Random().nextBytes(buffer);
            outputStream.write(buffer);
            appender.append(buffer, buffer.length);
        }

        int extra = new Random().nextInt(1024) + 1;
        byte[] extraBytes = new byte[extra];
        new Random().nextBytes(extraBytes);
        outputStream.write(extraBytes);
        outputStream.close();
        appender.append(extraBytes, extraBytes.length);
        appender.end();

        localChannel = new FileInputStream(fileName).getChannel();
        binlogFileReadChannel = new BinlogFileReadChannel(
            RemoteBinlogProxy.getInstance().prepareReadChannel(fileName), null);
    }

    @Test
    public void testSize() throws IOException {
        long actual = binlogFileReadChannel.size();
        long expect = localChannel.size();
        Assert.assertEquals(expect, actual);
    }

    @Test
    public void testPosition() throws IOException {
        long fileSize = localChannel.size();
        long pos = (long) (Math.random() * fileSize);

        binlogFileReadChannel.position(pos);
        localChannel.position(pos);
        Assert.assertEquals(localChannel.position(), binlogFileReadChannel.position());
        ByteBuffer buffer1 = ByteBuffer.allocate(1024);
        ByteBuffer buffer2 = ByteBuffer.allocate(1024);
        int readLen1;
        int readLen2;
        CRC32 localCrc = new CRC32();
        CRC32 ossCrc = new CRC32();
        while ((readLen1 = localChannel.read(buffer1)) > 0) {
            readLen2 = binlogFileReadChannel.read(buffer2);
            Assert.assertEquals(readLen1, readLen2);
            buffer1.flip();
            buffer2.flip();
            localCrc.update(buffer1.array(), 0, buffer1.limit());
            ossCrc.update(buffer2.array(), 0, buffer2.limit());
            Assert.assertEquals(localCrc.getValue(), ossCrc.getValue());
        }
        Assert.assertEquals(-1, binlogFileReadChannel.read(buffer2));
    }

    @Test
    public void testReadWithPos() throws IOException {
        // 测试从某个指定的位置读取一次

        long fileSize = localChannel.size();
        ByteBuffer buffer1 = ByteBuffer.allocate(1024);
        ByteBuffer buffer2 = ByteBuffer.allocate(1024);
        int readLen1;
        int readLen2;
        CRC32 localCrc = new CRC32();
        CRC32 ossCrc = new CRC32();

        // 随机测试100次
        for (int i = 0; i < 100; i++) {
            long pos = (long) (Math.random() * fileSize);
            readLen1 = localChannel.read(buffer1, pos);
            readLen2 = binlogFileReadChannel.read(buffer2, pos);
            Assert.assertEquals(readLen1, readLen2);
            buffer1.flip();
            buffer2.flip();
            localCrc.update(buffer1.array(), 0, buffer1.limit());
            ossCrc.update(buffer2.array(), 0, buffer2.limit());
            Assert.assertEquals(localCrc.getValue(), ossCrc.getValue());
        }
    }

}
