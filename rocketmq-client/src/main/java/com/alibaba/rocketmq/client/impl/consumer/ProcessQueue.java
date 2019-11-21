/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.client.impl.consumer;

import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.protocol.body.ProcessQueueInfo;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Queue consumption snapshot
 *
 * @author shijia.wxr
 */
public class ProcessQueue {
    public final static long RebalanceLockMaxLiveTime = Long.parseLong(System.getProperty(
            "rocketmq.client.rebalance.lockMaxLiveTime", "30000"));
    public final static long RebalanceLockInterval = Long.parseLong(System.getProperty(
            "rocketmq.client.rebalance.lockInterval", "20000"));

    private final Logger log = ClientLogger.getLog();
    private final ReadWriteLock lockTreeMap = new ReentrantReadWriteLock();
    private final TreeMap<Long, MessageExt> msgTreeMap = new TreeMap<Long, MessageExt>();

    /**
     * TODO:
     */
    private volatile long queueOffsetMax = 0L;

    /**
     * 当前队列中还没有处理完的消息数量
     */
    private final AtomicLong msgCount = new AtomicLong();

    private volatile boolean dropped = false;
    private volatile long lastPullTimestamp = System.currentTimeMillis();
    private final static long PullMaxIdleTime = Long.parseLong(System.getProperty(
            "rocketmq.client.pull.pullMaxIdleTime", "120000"));

    private volatile long lastConsumeTimestamp = System.currentTimeMillis();

    private final Lock lockConsume = new ReentrantLock();

    private volatile boolean locked = false;
    private volatile long lastLockTimestamp = System.currentTimeMillis();
    private volatile boolean consuming = false;
    private final TreeMap<Long, MessageExt> msgTreeMapTemp = new TreeMap<Long, MessageExt>();
    private final AtomicLong tryUnlockTimes = new AtomicLong(0);

    private volatile long msgAccCnt = 0;


    public boolean isLockExpired() {
        boolean result = (System.currentTimeMillis() - this.lastLockTimestamp) > RebalanceLockMaxLiveTime;
        return result;
    }


    public boolean isPullExpired() {
        boolean result = (System.currentTimeMillis() - this.lastPullTimestamp) > PullMaxIdleTime;
        return result;
    }

    /**
     * 放入消息到ProcessQueue
     *
     * @param msgs
     * @return
     */
    public boolean putMessage(final List<MessageExt> msgs) {
        boolean dispatchToConsume = false;
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                /**
                 * 把消息放入treemap，根据offset值去除重复消息，统计有效消息数量，更新Offset
                 *
                 * 消息以Offset为Key放入了treemap，则默认会按照offset的升序排序
                 *
                 */
                int validMsgCnt = 0;
                for (MessageExt msg : msgs) {
                    MessageExt old = msgTreeMap.put(msg.getQueueOffset(), msg);
                    if (null == old) {
                        validMsgCnt++;
                        this.queueOffsetMax = msg.getQueueOffset();
                    }
                }
                msgCount.addAndGet(validMsgCnt);

                if (!msgTreeMap.isEmpty() && !this.consuming) {
                    dispatchToConsume = true;
                    this.consuming = true;
                }

                if (!msgs.isEmpty()) {
                    //获取最后一条消息的MaxOffSet
                    MessageExt messageExt = msgs.get(msgs.size() - 1);
                    String property = messageExt.getProperty(MessageConst.PROPERTY_MAX_OFFSET);
                    if (property != null) {
                        /**
                         * commitlog offset
                         * 减去
                         * 消息对应MessageQueue的Offset
                         *
                         */
                        long accTotal = Long.parseLong(property) - messageExt.getQueueOffset();
                        if (accTotal > 0) {
                            this.msgAccCnt = accTotal;
                        }
                    }
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("putMessage exception", e);
        }

        return dispatchToConsume;
    }


    /**
     * 获取当前队列中最后一条消息和第一条消息的Offset差值
     *
     * @return
     */
    public long getMaxSpan() {
        try {
            this.lockTreeMap.readLock().lockInterruptibly();
            try {
                if (!this.msgTreeMap.isEmpty()) {
                    return this.msgTreeMap.lastKey() - this.msgTreeMap.firstKey();
                }
            } finally {
                this.lockTreeMap.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("getMaxSpan exception", e);
        }

        return 0;
    }

    /**
     * 消息处理之前通过putMessage方法放入Queue
     * 处理之后移除
     * 移除处理过的消息
     *
     * @param msgs
     * @return 返回待处理的消息的新的Offset
     */
    public long removeMessage(final List<MessageExt> msgs) {
        long result = -1;

        final long now = System.currentTimeMillis();

        try {
            this.lockTreeMap.writeLock().lockInterruptibly();

            this.lastConsumeTimestamp = now;

            try {
                if (!msgTreeMap.isEmpty()) {

                    /**
                     * TODO: +1 ??
                     */
                    result = this.queueOffsetMax + 1;

                    /**
                     * 移除处理过的消息
                     */
                    int removedCnt = 0;
                    for (MessageExt msg : msgs) {
                        MessageExt prev = msgTreeMap.remove(msg.getQueueOffset());
                        if (prev != null) {
                            removedCnt--;
                        }
                    }

                    /**
                     * 更新现有消息数量
                     */
                    msgCount.addAndGet(removedCnt);


                    /**
                     * 如果还有消息，则把当前队列中剩余的第一条消息的Offset返回
                     */
                    if (!msgTreeMap.isEmpty()) {
                        result = msgTreeMap.firstKey();
                    }
                }

            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (Throwable t) {
            log.error("removeMessage exception", t);
        }

        return result;
    }


    public TreeMap<Long, MessageExt> getMsgTreeMap() {
        return msgTreeMap;
    }


    public AtomicLong getMsgCount() {
        return msgCount;
    }


    public boolean isDropped() {
        return dropped;
    }


    public void setDropped(boolean dropped) {
        this.dropped = dropped;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }


    public boolean isLocked() {
        return locked;
    }


    public void rollback() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                this.msgTreeMap.putAll(this.msgTreeMapTemp);
                this.msgTreeMapTemp.clear();
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("rollback exception", e);
        }
    }


    public long commit() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                Long offset = this.msgTreeMapTemp.lastKey();
                msgCount.addAndGet(this.msgTreeMapTemp.size() * (-1));
                this.msgTreeMapTemp.clear();
                if (offset != null) {
                    return offset + 1;
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("commit exception", e);
        }

        return -1;
    }


    public void makeMessageToCosumeAgain(List<MessageExt> msgs) {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                for (MessageExt msg : msgs) {
                    this.msgTreeMapTemp.remove(msg.getQueueOffset());
                    this.msgTreeMap.put(msg.getQueueOffset(), msg);
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("makeMessageToCosumeAgain exception", e);
        }
    }

    public List<MessageExt> takeMessags(final int batchSize) {
        List<MessageExt> result = new ArrayList<MessageExt>(batchSize);
        final long now = System.currentTimeMillis();
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            this.lastConsumeTimestamp = now;
            try {
                if (!this.msgTreeMap.isEmpty()) {
                    for (int i = 0; i < batchSize; i++) {
                        Map.Entry<Long, MessageExt> entry = this.msgTreeMap.pollFirstEntry();
                        if (entry != null) {
                            result.add(entry.getValue());
                            msgTreeMapTemp.put(entry.getKey(), entry.getValue());
                        } else {
                            break;
                        }
                    }
                }

                if (result.isEmpty()) {
                    consuming = false;
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("take Messages exception", e);
        }

        return result;
    }


    public void clear() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                this.msgTreeMap.clear();
                this.msgTreeMapTemp.clear();
                this.msgCount.set(0);
                this.queueOffsetMax = 0L;
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("rollback exception", e);
        }
    }


    public long getLastLockTimestamp() {
        return lastLockTimestamp;
    }


    public void setLastLockTimestamp(long lastLockTimestamp) {
        this.lastLockTimestamp = lastLockTimestamp;
    }


    public Lock getLockConsume() {
        return lockConsume;
    }


    public long getLastPullTimestamp() {
        return lastPullTimestamp;
    }


    public void setLastPullTimestamp(long lastPullTimestamp) {
        this.lastPullTimestamp = lastPullTimestamp;
    }


    public long getMsgAccCnt() {
        return msgAccCnt;
    }


    public void setMsgAccCnt(long msgAccCnt) {
        this.msgAccCnt = msgAccCnt;
    }


    public long getTryUnlockTimes() {
        return this.tryUnlockTimes.get();
    }


    public void incTryUnlockTimes() {
        this.tryUnlockTimes.incrementAndGet();
    }


    public void fillProcessQueueInfo(final ProcessQueueInfo info) {
        try {
            this.lockTreeMap.readLock().lockInterruptibly();

            if (!this.msgTreeMap.isEmpty()) {
                info.setCachedMsgMinOffset(this.msgTreeMap.firstKey());
                info.setCachedMsgMaxOffset(this.msgTreeMap.lastKey());
                info.setCachedMsgCount(this.msgTreeMap.size());
            }

            if (!this.msgTreeMapTemp.isEmpty()) {
                info.setTransactionMsgMinOffset(this.msgTreeMapTemp.firstKey());
                info.setTransactionMsgMaxOffset(this.msgTreeMapTemp.lastKey());
                info.setTransactionMsgCount(this.msgTreeMapTemp.size());
            }

            info.setLocked(this.locked);
            info.setTryUnlockTimes(this.tryUnlockTimes.get());
            info.setLastLockTimestamp(this.lastLockTimestamp);

            info.setDroped(this.dropped);
            info.setLastPullTimestamp(this.lastPullTimestamp);
            info.setLastConsumeTimestamp(this.lastConsumeTimestamp);
        } catch (Exception e) {
        } finally {
            this.lockTreeMap.readLock().unlock();
        }
    }


    public long getLastConsumeTimestamp() {
        return lastConsumeTimestamp;
    }


    public void setLastConsumeTimestamp(long lastConsumeTimestamp) {
        this.lastConsumeTimestamp = lastConsumeTimestamp;
    }
}
