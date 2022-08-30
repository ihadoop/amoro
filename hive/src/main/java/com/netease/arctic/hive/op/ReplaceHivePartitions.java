/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.op;

import com.netease.arctic.hive.HMSClient;
import com.netease.arctic.hive.HiveTableProperties;
import com.netease.arctic.hive.exceptions.CannotAlterHiveLocationException;
import com.netease.arctic.hive.table.UnkeyedHiveTable;
import com.netease.arctic.hive.utils.HivePartitionUtil;
import com.netease.arctic.hive.utils.HiveTableUtil;
import com.netease.arctic.op.UpdatePartitionProperties;
import com.netease.arctic.utils.FileUtil;
import com.netease.arctic.utils.TablePropertyUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Types;
import org.apache.thrift.TException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ReplaceHivePartitions implements ReplacePartitions {

  private final Transaction transaction;
  private final boolean insideTransaction;
  private final ReplacePartitions delegate;

  private final HMSClient hmsClient;
  private final HMSClient transactionalHMSClient;

  private final UnkeyedHiveTable table;
  private final List<DataFile> addFiles = Lists.newArrayList();
  private final String db;
  private final String tableName;
  private final Table hiveTable;

  private final Map<StructLike, Partition> rewritePartitions = Maps.newHashMap();
  private final Map<StructLike, Partition> newPartitions = Maps.newHashMap();
  private String unpartitionTableLocation;
  private int commitTimestamp; // in seconds

  public ReplaceHivePartitions(
      Transaction transaction,
      boolean insideTransaction,
      UnkeyedHiveTable table,
      HMSClient client,
      HMSClient transactionalClient) {
    this.transaction = transaction;
    this.insideTransaction = insideTransaction;
    this.delegate = transaction.newReplacePartitions();
    this.hmsClient = client;
    this.transactionalHMSClient = transactionalClient;
    this.table = table;
    this.db = table.id().getDatabase();
    this.tableName = table.id().getTableName();
    try {
      this.hiveTable = client.run(c -> c.getTable(db, tableName));
    } catch (TException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ReplacePartitions addFile(DataFile file) {
    delegate.addFile(file);
    String tableLocation = table.hiveLocation();
    String dataFileLocation = file.path().toString();
    if (dataFileLocation.toLowerCase().contains(tableLocation.toLowerCase())) {
      // only handle file in hive location
      this.addFiles.add(file);
    }
    return this;
  }

  @Override
  public ReplacePartitions validateAppendOnly() {
    delegate.validateAppendOnly();
    return this;
  }

  @Override
  public ReplacePartitions set(String property, String value) {
    delegate.set(property, value);
    return this;
  }

  @Override
  public ReplacePartitions deleteWith(Consumer<String> deleteFunc) {
    delegate.deleteWith(deleteFunc);
    return this;
  }

  @Override
  public ReplacePartitions stageOnly() {
    delegate.stageOnly();
    return this;
  }

  @Override
  public Snapshot apply() {
    return delegate.apply();
  }

  @Override
  public void commit() {
    if (!addFiles.isEmpty()) {
      commitTimestamp = (int) (System.currentTimeMillis() / 1000);
      if (table.spec().isUnpartitioned()) {
        generateUnpartitionTableLocation();
      } else {
        applyHivePartitions();
      }

      delegate.commit();
      commitPartitionProperties();
      if (!insideTransaction) {
        transaction.commitTransaction();
      }

      if (table.spec().isUnpartitioned()) {
        commitUnPartitionedTable();
      } else {
        commitPartitionedTable();
      }
    }
  }

  private void commitPartitionProperties() {
    UpdatePartitionProperties updatePartitionProperties = table.updatePartitionProperties(transaction);
    if (table.spec().isUnpartitioned() && unpartitionTableLocation != null) {
      updatePartitionProperties.set(TablePropertyUtil.EMPTY_STRUCT,
          HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION, unpartitionTableLocation);
      updatePartitionProperties.set(TablePropertyUtil.EMPTY_STRUCT,
          HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME, commitTimestamp + "");
    } else {
      rewritePartitions.forEach((partitionData, partition) -> {
        updatePartitionProperties.set(partitionData, HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION,
            partition.getSd().getLocation());
        updatePartitionProperties.set(partitionData, HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME,
            commitTimestamp + "");
      });
      newPartitions.forEach((partitionData, partition) -> {
        updatePartitionProperties.set(partitionData, HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION,
            partition.getSd().getLocation());
        updatePartitionProperties.set(partitionData, HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME,
            commitTimestamp + "");
      });
    }
    updatePartitionProperties.commit();
  }

  @Override
  public Object updateEvent() {
    return delegate.updateEvent();
  }

  private void applyHivePartitions() {
    Types.StructType partitionSchema = table.spec().partitionType();

    // partitionValue -> partitionLocation.
    Map<String, String> partitionLocationMap = Maps.newHashMap();
    Map<String, List<DataFile>> partitionDataFileMap = Maps.newHashMap();
    Map<String, List<String>> partitionValueMap = Maps.newHashMap();

    for (DataFile d : addFiles) {
      List<String> partitionValues = HivePartitionUtil.partitionValuesAsList(d.partition(), partitionSchema);
      String value = Joiner.on("/").join(partitionValues);
      String location = FileUtil.getFileDir(d.path().toString());
      partitionLocationMap.put(value, location);
      if (!partitionDataFileMap.containsKey(value)) {
        partitionDataFileMap.put(value, Lists.newArrayList());
      }
      partitionDataFileMap.get(value).add(d);
      partitionValueMap.put(value, partitionValues);
    }
    partitionLocationMap.forEach((k, v) -> checkDataFileInSameLocation(v, partitionDataFileMap.get(k)));

    for (String val : partitionValueMap.keySet()) {
      List<String> values = partitionValueMap.get(val);
      String location = partitionLocationMap.get(val);
      List<DataFile> dataFiles = partitionDataFileMap.get(val);

      try {
        Partition partition = hmsClient.run(c -> c.getPartition(db, tableName, values));
        HivePartitionUtil.rewriteHivePartitions(partition, location, dataFiles, commitTimestamp);
        rewritePartitions.put(dataFiles.get(0).partition(), partition);
      } catch (NoSuchObjectException e) {
        Partition p = HivePartitionUtil.newPartition(hiveTable, values, location, dataFiles, commitTimestamp);
        newPartitions.put(dataFiles.get(0).partition(), p);
      } catch (TException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void commitUnPartitionedTable() {
    if (!addFiles.isEmpty()) {
      final String newDataLocation = FileUtil.getFileDir(addFiles.get(0).path().toString());
      try {
        transactionalHMSClient.run(c -> {
          Table tbl = c.getTable(db, tableName);
          tbl.getSd().setLocation(newDataLocation);
          HiveTableUtil.generateTableProperties(commitTimestamp, addFiles)
              .forEach((key, value) -> hiveTable.getParameters().put(key, value));
          c.alter_table(db, tableName, tbl);
          return 0;
        });
      } catch (TException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void commitPartitionedTable() {
    try {
      transactionalHMSClient.run(c -> {
        if (!rewritePartitions.isEmpty()) {
          c.alter_partitions(db, tableName, Lists.newArrayList(rewritePartitions.values()), null);
        }
        if (!newPartitions.isEmpty()) {
          c.add_partitions(Lists.newArrayList(newPartitions.values()));
        }
        return 0;
      });
    } catch (TException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void checkDataFileInSameLocation(String partitionLocation, List<DataFile> files) {
    Path partitionPath = new Path(partitionLocation);
    for (DataFile df : files) {
      String fileDir = FileUtil.getFileDir(df.path().toString());
      Path dirPath = new Path(fileDir);
      if (!partitionPath.equals(dirPath)) {
        throw new CannotAlterHiveLocationException(
            "can't create new hive location: " + partitionLocation + " for data file: " + df.path().toString() +
                " is not under partition location path"
        );
      }
    }
  }

  private void generateUnpartitionTableLocation() {
    unpartitionTableLocation = FileUtil.getFileDir(this.addFiles.get(0).path().toString());
  }
}