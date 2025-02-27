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
package com.aliyun.polardbx.binlog.extractor.sort;

import com.aliyun.polardbx.binlog.CommonUtils;
import com.aliyun.polardbx.binlog.ConfigKeys;
import com.aliyun.polardbx.binlog.DynamicApplicationConfig;
import com.aliyun.polardbx.binlog.error.PolardbxException;
import com.aliyun.polardbx.binlog.extractor.log.Transaction;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.aliyun.polardbx.binlog.ConfigKeys.TASK_TRANSACTION_SKIP_WHITELIST;

/**
 * Created by ziyang.lb
 **/
@Slf4j
public class Sorter {
    private static final Logger skipTranslogger = LoggerFactory.getLogger("SKIP_TRANS_LOG");
    private final Set<String> waitTrans;
    private final List<SortItem> items;
    private final Map<String, Transaction> transMap;
    private final PriorityQueue<TransNode> transQueue;
    private final AtomicReference<SortItem> firstSortItem;
    private final boolean RANDOM_DISCARD_COMMIT_SWITCH =
        DynamicApplicationConfig.getBoolean(ConfigKeys.TASK_TRANSACTION_RANDOM_DISCARD_COMMIT);
    private SortItem maxSortItem;

    public Sorter() {
        this.waitTrans = new HashSet<>();
        this.items = new LinkedList<>();
        this.transMap = new HashMap<>();
        this.transQueue = new PriorityQueue<>();
        this.firstSortItem = new AtomicReference<>();
    }

    public void pushTSItem(SortItem item) {
        if (firstSortItem.compareAndSet(null, item)) {
            log.info("First received sortItem is : " + item);
        }

        if (item.getType() == SortItemType.PreWrite) {
            waitTrans.add(item.getXid());
            if (transMap.put(item.getXid(), item.getTransaction()) != null) {
                throw new PolardbxException("duplicate xid for ： " + item.getXid());
            }
        } else {
            if (RANDOM_DISCARD_COMMIT_SWITCH) {
                // xa事务随机丢弃，制造block场景
                if (CommonUtils.randomBoolean(5)) {
                    log.warn("[inject trouble] discard commit event for : " + item.getXid());
                    return;
                }
            }
            if (transMap.get(item.getXid()) == null) {
                if (wantSkip(item.getXid())) {
                    return;
                }
                throw new PolardbxException("xid is not existed in trans map for : " + item.getXid());
            }
            waitTrans.remove(item.getXid());
            if (item.getType() == SortItemType.Commit) {
                transQueue.add(new TransNode(item.getTransaction()));
            }
        }
        items.add(item);
    }

    public int size() {
        return items.size();
    }

    public List<Transaction> getAvailableTrans() {
        List<Transaction> result = new LinkedList<>();
        if (items.isEmpty()) {
            return result;
        }

        int skipThreshold = DynamicApplicationConfig.getInt(ConfigKeys.TASK_TRANSACTION_SKIP_THRESHOLD);
        if (items.size() > skipThreshold) {
            // 加一个优化， items 比较大的情况，可能有丢失commit或者rollback现象
            // 只清理头部即可
            SortItem item = items.get(0);
            if (item.getType() == SortItemType.PreWrite) {
                String xid = item.getXid();
                if (wantSkip(xid)) {
                    log.info("hit the whitelist, skip transaction with xid {}.", xid);
                    skipTranslogger.info("skip : " + xid);
                    if (waitTrans.remove(xid)) {
                        //释放一下 transaction
                        item.getTransaction().release();
                    }
                    transMap.remove(xid);
                }
            }
        }

        long startTime = System.currentTimeMillis();
        while (true) {
            if (items.size() == 0) {
                break;
            }

            SortItem item = items.get(0);
            if (item.getType() == SortItemType.PreWrite) {
                if (waitTrans.contains(item.getXid())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Transaction {} is not ready.", item.getXid());
                    }
                    break;
                } else {
                    items.remove(0);
                }
            } else {
                if (maxSortItem == null ||
                    item.getTransaction().getVirtualTSO().compareTo(maxSortItem.getTransaction().getVirtualTSO()) > 0) {
                    maxSortItem = item;
                }
                items.remove(0);
            }

            if (System.currentTimeMillis() - startTime > 500) {
                break;
            }
        }
        //  P1 P2 C1 P3 C2 C3
        TransNode t;
        while ((t = transQueue.peek()) != null && maxSortItem != null) {
            if (t.getTransaction().getVirtualTSO().compareTo(maxSortItem.getTransaction().getVirtualTSO()) <= 0) {
                t = transQueue.poll();
                transMap.remove(t.getTransaction().getXid());
                result.add(t.getTransaction());
                if (log.isDebugEnabled()) {
                    log.debug(t.getTransaction().toString());
                }
            } else {
                break;
            }
        }

        if (log.isDebugEnabled() && !result.isEmpty()) {
            log.debug("available trans result size is " + result.size());
            result.forEach(r -> {
                System.out.println();
                log.debug(r.toString());
            });
        }

        return result;
    }

    private boolean wantSkip(String xid) {
        String skipWhiteList = DynamicApplicationConfig.getString(TASK_TRANSACTION_SKIP_WHITELIST);
        if (StringUtils.isBlank(skipWhiteList)) {
            return false;
        }
        String[] array = StringUtils.split(skipWhiteList, "#");
        for (String skipXid : array) {
            if (xid.equals(skipXid)) {
                return true;
            }
        }
        return false;
    }

    public Transaction getTransByXid(String xid) {
        return transMap.get(xid);
    }

    public TransNode peekFirstNode() {
        return transQueue.peek();
    }

    public SortItem peekFirstSortItem() {
        return items.isEmpty() ? null : items.get(0);
    }

    public List<Transaction> getAllQueuedTransactions() {
        return Lists.newArrayList(transMap.values());
    }

    public void clear() {
        waitTrans.clear();
        items.clear();
        transMap.clear();
        maxSortItem = null;
    }

    public static class TransNode implements Comparable<TransNode> {
        private final Transaction transaction;

        public TransNode(Transaction trans) {
            this.transaction = trans;
        }

        @Override
        public int compareTo(TransNode o) {
            return transaction.compareTo(o.transaction);
        }

        public Transaction getTransaction() {
            return transaction;
        }
    }
}
