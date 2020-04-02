package com.aliyun.odps.datacarrier.taskscheduler;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;


public class MockHiveMetaSource implements MetaSource {

  public static final String DB_NAME = "test";
  public static final String TBL_NON_PARTITIONED = "test_non_partitioned";
  public static final String TBL_PARTITIONED = "test_partitioned";
  public static final List<String> TBL_PARTITIONED_PARTITION_VALUES =
      Collections.singletonList("hello_world");
  public static final Map<String, TableMetaModel> TABLE_NAME_2_TABLE_META_MODEL = new HashMap<>();
  static {
    TABLE_NAME_2_TABLE_META_MODEL.put(TBL_NON_PARTITIONED,
                                      getTestNonPartitionedTableMetaModel());
    TABLE_NAME_2_TABLE_META_MODEL.put(TBL_PARTITIONED, getTestPartitionedTableMetaModel());
  }

  private static TableMetaModel getTestNonPartitionedTableMetaModel() {
    TableMetaModel testNonPartitioned = new TableMetaModel();
    testNonPartitioned.databaseName = DB_NAME;
    testNonPartitioned.odpsProjectName = DB_NAME;
    testNonPartitioned.tableName = TBL_NON_PARTITIONED;
    testNonPartitioned.odpsTableName = TBL_NON_PARTITIONED;

    ColumnMetaModel c = new ColumnMetaModel();
    c.columnName = "foo";
    c.odpsColumnName = "foo";
    c.type = "string";
    c.odpsType = "string";
    testNonPartitioned.columns.add(c);

    return testNonPartitioned;
  }

  private static TableMetaModel getTestPartitionedTableMetaModel() {
    TableMetaModel testPartitioned = new TableMetaModel();
    testPartitioned.databaseName = DB_NAME;
    testPartitioned.odpsProjectName = DB_NAME;
    testPartitioned.tableName = TBL_PARTITIONED;
    testPartitioned.odpsTableName = TBL_PARTITIONED;

    ColumnMetaModel c = new ColumnMetaModel();
    c.columnName = "foo";
    c.odpsColumnName = "foo";
    c.type = "string";
    c.odpsType = "string";
    testPartitioned.columns.add(c);

    ColumnMetaModel pc = new ColumnMetaModel();
    pc.columnName = "bar";
    pc.odpsColumnName = "bar";
    pc.type = "string";
    pc.odpsType = "string";
    testPartitioned.partitionColumns.add(pc);

    PartitionMetaModel partitionMetaModel = new PartitionMetaModel();
    partitionMetaModel.partitionValues.addAll(TBL_PARTITIONED_PARTITION_VALUES);
    testPartitioned.partitions.add(partitionMetaModel);

    return testPartitioned;
  }

  @Override
  public TableMetaModel getTableMeta(String databaseName, String tableName) throws Exception {
    if (!DB_NAME.equals(databaseName)) {
      throw new IllegalArgumentException("database doesn't exist");
    }

    if (!TABLE_NAME_2_TABLE_META_MODEL.containsKey(tableName)) {
      throw new IllegalArgumentException("table doesn't exist");
    }

    return TABLE_NAME_2_TABLE_META_MODEL.get(tableName);
  }

  @Override
  public TableMetaModel getTableMetaWithoutPartitionMeta(String databaseName, String tableName)
      throws Exception {
    if (!DB_NAME.equals(databaseName)) {
      throw new IllegalArgumentException("database doesn't exist");
    }

    if (!TABLE_NAME_2_TABLE_META_MODEL.containsKey(tableName)) {
      throw new IllegalArgumentException("table doesn't exist");
    }

    TableMetaModel tableMetaModel;
    if (TBL_NON_PARTITIONED.equals(tableName)) {
      tableMetaModel = getTestNonPartitionedTableMetaModel();
    } else {
      tableMetaModel = getTestPartitionedTableMetaModel();
    }

    tableMetaModel.partitions.clear();
    return tableMetaModel;
  }

  @Override
  public PartitionMetaModel getPartitionMeta(String databaseName, String tableName,
                                             List<String> partitionValues) throws Exception {
    if (!DB_NAME.equals(databaseName)) {
     throw new IllegalArgumentException("database doesn't exist");
    }

    if (!TABLE_NAME_2_TABLE_META_MODEL.containsKey(tableName)) {
      throw new IllegalArgumentException("table doesn't exist");
    }

    TableMetaModel tableMetaModel = TABLE_NAME_2_TABLE_META_MODEL.get(tableName);
    for (PartitionMetaModel partitionMetaModel : tableMetaModel.partitions) {
      if (partitionValues.equals(partitionMetaModel.partitionValues)) {
        return partitionMetaModel;
      }
    }

    throw new IllegalArgumentException("partition doesn't exist");
  }

  @Override
  public List<List<String>> listPartitions(String databaseName, String tableName) throws Exception {
    List<List<String>> ret = new LinkedList<>();

    for (PartitionMetaModel pt : getTableMeta(databaseName, tableName).partitions) {
      ret.add(pt.partitionValues);
    }
    return ret;
  }

  @Override
  public boolean hasTable(String databaseName, String tableName) throws Exception {
    return DB_NAME.equalsIgnoreCase(databaseName)
           && (TABLE_NAME_2_TABLE_META_MODEL.containsKey(tableName.toLowerCase()));
  }

  @Override
  public boolean hasDatabase(String databaseName) throws Exception {
    return DB_NAME.equalsIgnoreCase(databaseName);
  }

  @Override
  public List<String> listTables(String databaseName) throws Exception {
    return Lists.newArrayList(TABLE_NAME_2_TABLE_META_MODEL.keySet());
  }

  @Override
  public List<String> listDatabases() throws Exception {
    List<String> ret = new LinkedList<>();
    ret.add(DB_NAME);
    return ret;
  }

  @Override
  public void shutdown() {
    // Ignore
  }
}
