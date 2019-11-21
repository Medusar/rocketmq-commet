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
 * <p>
 * $Id: BrokerData.java 1835 2013-05-16 02:00:50Z shijia.wxr $
 * <p>
 * $Id: BrokerData.java 1835 2013-05-16 02:00:50Z shijia.wxr $
 */

/**
 * $Id: BrokerData.java 1835 2013-05-16 02:00:50Z shijia.wxr $
 */
package com.alibaba.rocketmq.common.protocol.route;

import com.alibaba.rocketmq.common.MixAll;

import java.util.HashMap;


/**
 * 集群-->BrokerData-->Broker
 *
 * BrokerData表示集群中的某个BrokerName下的机器信息
 *
 * @author shijia.wxr
 */
public class BrokerData implements Comparable<BrokerData> {
    //所属集群名字
    private String cluster;

    private String brokerName;

    /**
     * BrokerID与Broker地址对应关系
     * BrokerID=0表示当前机器为Master
     * 同一个brokerName中的多台机器通过不同BrokerID来区分，brokerID同时用于标记主节点还是从节点
     */
    private HashMap<Long/* brokerId */, String/* broker address */> brokerAddrs;

    public String selectBrokerAddr() {
        String value = this.brokerAddrs.get(MixAll.MASTER_ID);
        if (null == value) {
            for (Long key : this.brokerAddrs.keySet()) {
                return this.brokerAddrs.get(key);
            }
        }

        return value;
    }


    public String getBrokerName() {
        return brokerName;
    }


    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }


    public HashMap<Long, String> getBrokerAddrs() {
        return brokerAddrs;
    }


    public void setBrokerAddrs(HashMap<Long, String> brokerAddrs) {
        this.brokerAddrs = brokerAddrs;
    }


    public String getCluster() {
        return cluster;
    }


    public void setCluster(String cluster) {
        this.cluster = cluster;
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((brokerAddrs == null) ? 0 : brokerAddrs.hashCode());
        result = prime * result + ((brokerName == null) ? 0 : brokerName.hashCode());
        return result;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        BrokerData other = (BrokerData) obj;
        if (brokerAddrs == null) {
            if (other.brokerAddrs != null)
                return false;
        } else if (!brokerAddrs.equals(other.brokerAddrs))
            return false;
        if (brokerName == null) {
            if (other.brokerName != null)
                return false;
        } else if (!brokerName.equals(other.brokerName))
            return false;
        return true;
    }


    @Override
    public String toString() {
        return "BrokerData [brokerName=" + brokerName + ", brokerAddrs=" + brokerAddrs + "]";
    }


    @Override
    public int compareTo(BrokerData o) {
        return this.brokerName.compareTo(o.getBrokerName());
    }
}
