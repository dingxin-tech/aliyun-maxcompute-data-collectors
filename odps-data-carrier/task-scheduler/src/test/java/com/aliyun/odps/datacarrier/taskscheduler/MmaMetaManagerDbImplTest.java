package com.aliyun.odps.datacarrier.taskscheduler;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


@FixMethodOrder (MethodSorters.NAME_ASCENDING)
public class MmaMetaManagerDbImplTest {
  public static final Path DEFAULT_MMA_PARENT_DIR =
      Paths.get(System.getProperty("user.dir")).toAbsolutePath();
  private static final String DEFAULT_CONN_URL =
      "jdbc:h2:file:" + Paths.get(DEFAULT_MMA_PARENT_DIR.toString(), Constants.DB_FILE_NAME);

  private static final MmaConfig.AdditionalTableConfig DEFAULT_ADDITIONAL_TABLE_CONF =
      new MmaConfig.AdditionalTableConfig(
          null,
          null,
          10,
          1);
  private static final MmaConfig.TableMigrationConfig TABLE_MIGRATION_CONFIG_PARTITIONED =
      new MmaConfig.TableMigrationConfig(
          MockHiveMetaSource.DB_NAME,
          MockHiveMetaSource.TBL_PARTITIONED,
          MockHiveMetaSource.DB_NAME,
          MockHiveMetaSource.TBL_PARTITIONED,
          DEFAULT_ADDITIONAL_TABLE_CONF);
  private static final MmaConfig.TableMigrationConfig TABLE_MIGRATION_CONFIG_NON_PARTITIONED =
      new MmaConfig.TableMigrationConfig(
          MockHiveMetaSource.DB_NAME,
          MockHiveMetaSource.TBL_NON_PARTITIONED,
          MockHiveMetaSource.DB_NAME,
          MockHiveMetaSource.TBL_NON_PARTITIONED,
          DEFAULT_ADDITIONAL_TABLE_CONF);
  private static Connection conn;

  private static MetaSource metaSource = new MockHiveMetaSource();
  private static MmaMetaManager mmaMetaManager;

  @BeforeClass
  public static void beforeClass() throws MmaException, SQLException {
    mmaMetaManager = new MmaMetaManagerDbImpl(DEFAULT_MMA_PARENT_DIR, metaSource);
    conn = DriverManager.getConnection(DEFAULT_CONN_URL, "mma", "");
  }

  @AfterClass
  public static void afterClass() throws MmaException {
    mmaMetaManager.shutdown();
    File[] dbFiles = DEFAULT_MMA_PARENT_DIR
        .toFile()
        .listFiles(f -> f.getName().startsWith(Constants.DB_FILE_NAME));

    assert dbFiles != null;
    for (File f : dbFiles) {
      assert f.delete();
    }
  }

  @Before
  public void setup() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE " + Constants.MMA_TBL_META_TBL_NAME);
    }

    try (Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SHOW SCHEMAS")) {
        while (!rs.isClosed() && rs.next()) {
          if (rs.getString(1).startsWith(Constants.MMA_PT_META_SCHEMA_NAME_PREFIX)) {
            stmt.execute("DROP SCHEMA " + rs.getString(1) + " CASCADE");
          }
        }
      }
    }
  }

  @Test
  public void testDbFileExists() {
    Path dbFilePath = Paths.get(DEFAULT_MMA_PARENT_DIR.toString(),
                                Constants.DB_FILE_NAME + ".mv.db");
    Assert.assertTrue(dbFilePath.toFile().exists());
  }

  @Test
  public void testAddNonExistingMigrationJob() throws Exception {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);

    try (Statement stmt = conn.createStatement()) {
      String sql = String.format("SELECT * FROM %s ORDER BY %s DESC",
                                 Constants.MMA_TBL_META_TBL_NAME,
                                 Constants.MMA_TBL_META_COL_TBL_NAME);
      try (ResultSet rs = stmt.executeQuery(sql)) {
        // test.test_partitioned
        Assert.assertTrue(rs.next());
        Assert.assertEquals(MockHiveMetaSource.DB_NAME, rs.getString(1));
        Assert.assertEquals(MockHiveMetaSource.TBL_PARTITIONED, rs.getString(2));
        Assert.assertTrue(rs.getBoolean(3));
        Assert.assertEquals(MmaConfig.TableMigrationConfig.toJson(TABLE_MIGRATION_CONFIG_PARTITIONED),
                            rs.getString(4));
        Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING.toString(),
                            rs.getString(5));
        Assert.assertEquals(0, rs.getInt(6));
        Assert.assertEquals(-1L, rs.getLong(7));

        // test.test_non_partitioned
        Assert.assertTrue(rs.next());
        Assert.assertEquals(MockHiveMetaSource.DB_NAME, rs.getString(1));
        Assert.assertEquals(MockHiveMetaSource.TBL_NON_PARTITIONED, rs.getString(2));
        Assert.assertFalse(rs.getBoolean(3));
        Assert.assertEquals(MmaConfig.TableMigrationConfig.toJson(TABLE_MIGRATION_CONFIG_NON_PARTITIONED),
                            rs.getString(4));
        Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING.toString(),
                            rs.getString(5));
        Assert.assertEquals(0, rs.getInt(6));
        Assert.assertEquals(-1L, rs.getLong(7));
      }

      // check mma_meta_pt_test_test_partitioned
      String schemaName = String.format(Constants.MMA_PT_META_SCHEMA_NAME_FMT,
                                        MockHiveMetaSource.DB_NAME);
      String tableName = String.format(Constants.MMA_PT_META_TBL_NAME_FMT,
                                       MockHiveMetaSource.TBL_PARTITIONED);
      sql = "SELECT  * FROM " + schemaName + "." + tableName;
      try (ResultSet rs = stmt.executeQuery(sql)) {
        Assert.assertTrue(rs.next());
        Assert.assertEquals(GsonUtils.getFullConfigGson().toJson(MockHiveMetaSource.TBL_PARTITIONED_PARTITION_VALUES),
                            rs.getString(1));
        Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING.toString(),
                            rs.getString(2));
        Assert.assertEquals(0, rs.getInt(3));
        Assert.assertEquals(-1L, rs.getInt(4));
      }
    }
  }

  @Test
  public void testAddExistingRunningMigrationJob() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);

    try {
      mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
    } catch (MmaException e) {
      Assert.assertTrue(ExceptionUtils.getStackTrace(e).contains("Running migration job exists"));
    }
  }

  @Test
  public void testAddExistingTerminatedMigrationJob() throws MmaException {

  }

  @Test
  public void testHasMigrationJob() throws MmaException {
    String db = TABLE_MIGRATION_CONFIG_PARTITIONED.getSourceDataBaseName();
    String tbl = TABLE_MIGRATION_CONFIG_PARTITIONED.getSourceTableName();
    Assert.assertFalse(mmaMetaManager.hasMigrationJob(db, tbl));
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
    Assert.assertTrue(mmaMetaManager.hasMigrationJob(db,tbl));
  }

  @Test
  public void testListMigrationJobs() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);

    List<MmaConfig.TableMigrationConfig> configs = mmaMetaManager.listMigrationJobs(-1);
    Assert.assertEquals(2, configs.size());

    Assert.assertEquals(MmaConfig.TableMigrationConfig.toJson(TABLE_MIGRATION_CONFIG_PARTITIONED),
                        MmaConfig.TableMigrationConfig.toJson(configs.get(0)));
    Assert.assertEquals(MmaConfig.TableMigrationConfig.toJson(TABLE_MIGRATION_CONFIG_NON_PARTITIONED),
                        MmaConfig.TableMigrationConfig.toJson(configs.get(1)));
  }

  @Test
  public void testRemoveExistingTerminatedMigrationJob() throws MmaException {
//    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
//    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
//
//    mmaMetaManager.updateStatus(TABLE_MIGRATION_CONFIG_NON_PARTITIONED.getSourceDataBaseName(),
//                                TABLE_MIGRATION_CONFIG_NON_PARTITIONED.getSourceTableName(),
//                                MmaMetaManager.MigrationStatus.SUCCEEDED);
//
//    mmaMetaManager.removeMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED.getSourceDataBaseName(),
//                                      TABLE_MIGRATION_CONFIG_NON_PARTITIONED.getSourceTableName());
//    mmaMetaManager.remo
  }

  @Test
  public void testRemoveExistingRunningMigrationJob() throws MmaException {

  }

  @Test
  public void testRemoveNonExistingMigrationJob() {

  }

  @Test
  public void testGetExistingMigrationJobStatus() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
    MmaMetaManager.MigrationStatus status =
        mmaMetaManager.getStatus(MockHiveMetaSource.DB_NAME,
                                 MockHiveMetaSource.TBL_NON_PARTITIONED);
    Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING, status);
  }

  @Test
  public void testGetExistingMigrationJobPtStatus() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
    MmaMetaManager.MigrationStatus status =
        mmaMetaManager.getStatus(MockHiveMetaSource.DB_NAME,
                                 MockHiveMetaSource.TBL_PARTITIONED,
                                 MockHiveMetaSource.TBL_PARTITIONED_PARTITION_VALUES);

    Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING, status);
  }

  @Test
  public void testUpdateExistingMigrationJobToSucceeded() throws Exception {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);
    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_NON_PARTITIONED,
                                MmaMetaManager.MigrationStatus.SUCCEEDED);

    String sql = "SELECT status FROM " + Constants.MMA_TBL_META_TBL_NAME;
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      Assert.assertTrue(rs.next());
      Assert.assertEquals(MmaMetaManager.MigrationStatus.SUCCEEDED.toString(),
                          rs.getString(1));
    }
  }

  @Test
  public void testUpdateExistingMigrationJobToFailed() throws MmaException, SQLException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);

    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_NON_PARTITIONED,
                                MmaMetaManager.MigrationStatus.FAILED);

    String sql = "SELECT status, attempt_times FROM " + Constants.MMA_TBL_META_TBL_NAME;
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      Assert.assertTrue(rs.next());
      Assert.assertEquals(MmaMetaManager.MigrationStatus.PENDING.toString(),
                          rs.getString(1));
      Assert.assertEquals(1, rs.getInt(2));
    }

    // Retry fails, status is set to FAILED since max retry times is 1
    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_NON_PARTITIONED,
                                MmaMetaManager.MigrationStatus.FAILED);
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      Assert.assertTrue(rs.next());
      Assert.assertEquals(MmaMetaManager.MigrationStatus.FAILED.toString(),
                          rs.getString(1));
      Assert.assertEquals(2, rs.getInt(2));
    }
  }

  @Test
  public void testUpdateNonExistingMigrationJob() {
    try {
      mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                  MockHiveMetaSource.TBL_NON_PARTITIONED,
                                  MmaMetaManager.MigrationStatus.FAILED);
      Assert.fail();
    } catch (MmaException e) {
      Assert.assertTrue(ExceptionUtils.getStackTrace(e).contains("Failed to update migration job"));
    }
  }

  @Test
  public void testUpdateExistingMigrationJobPt() throws MmaException, SQLException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);

    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_PARTITIONED,
                                Collections.singletonList(MockHiveMetaSource.TBL_PARTITIONED_PARTITION_VALUES),
                                MmaMetaManager.MigrationStatus.SUCCEEDED);

    String schemaName = String.format(Constants.MMA_PT_META_SCHEMA_NAME_FMT,
                                      MockHiveMetaSource.DB_NAME);
    String tableName = String.format(Constants.MMA_PT_META_TBL_NAME_FMT,
                                     MockHiveMetaSource.TBL_PARTITIONED);
    String sql = "SELECT status FROM " + schemaName + "." + tableName;
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      Assert.assertTrue(rs.next());
      Assert.assertEquals(MmaMetaManager.MigrationStatus.SUCCEEDED.toString(),
                          rs.getString(1));
    }
  }

  @Test
  public void testGetProgressExistingMigrationJob() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
//    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_NON_PARTITIONED);

    MmaMetaManager.MigrationProgress progress =
        mmaMetaManager.getProgress(MockHiveMetaSource.DB_NAME, MockHiveMetaSource.TBL_PARTITIONED);
    Assert.assertEquals(1, progress.getNumPendingPartitions());
    Assert.assertEquals(0, progress.getNumRunningPartitions());
    Assert.assertEquals(0, progress.getNumSucceededPartitions());
    Assert.assertEquals(0, progress.getNumFailedPartitions());

//    Assert.assertNull(mmaMetaManager.getProgress(MockHiveMetaSource.DB_NAME,
//                                                 MockHiveMetaSource.TBL_NON_PARTITIONED));
  }

  @Test
  public void testGetProgressNonExistingMigrationJob() throws MmaException {
    Assert.assertNull(mmaMetaManager.getProgress(MockHiveMetaSource.DB_NAME,
                               MockHiveMetaSource.TBL_NON_PARTITIONED));

  }

  @Test
  public void testGetConfigExistingMigrationJob() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);

    MmaConfig.TableMigrationConfig config =
        mmaMetaManager.getConfig(MockHiveMetaSource.DB_NAME, MockHiveMetaSource.TBL_PARTITIONED);
    Assert.assertEquals(MmaConfig.TableMigrationConfig.toJson(TABLE_MIGRATION_CONFIG_PARTITIONED),
                        MmaConfig.TableMigrationConfig.toJson(config));
  }

  @Test
  public void testGetConfigNonExistingMigrationJob() {
    try {
      mmaMetaManager.getConfig(MockHiveMetaSource.DB_NAME, MockHiveMetaSource.TBL_PARTITIONED);
      Assert.fail();
    } catch (MmaException e) {
      Assert.assertTrue(ExceptionUtils.getStackTrace(e).contains("Migration job not existed"));
    }
  }

  @Test
  public void testGetPendingTables() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);

    List<MetaSource.TableMetaModel> tableMetaModels = mmaMetaManager.getPendingTables();

    Assert.assertEquals(1, tableMetaModels.size());
    MetaSource.TableMetaModel expected =
        MockHiveMetaSource.TABLE_NAME_2_TABLE_META_MODEL.get(MockHiveMetaSource.TBL_PARTITIONED);
    MetaSource.TableMetaModel actual = tableMetaModels.get(0);
    Assert.assertEquals(expected.databaseName, actual.databaseName);
    Assert.assertEquals(expected.tableName, actual.tableName);
    Assert.assertEquals(1, actual.partitions.size());
    Assert.assertEquals(MockHiveMetaSource.TBL_PARTITIONED_PARTITION_VALUES,
                        actual.partitions.get(0).partitionValues);
  }

  @Test
  public void testGetPendingTablesAfterMigrationJobSucceeded() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_PARTITIONED,
                                MmaMetaManager.MigrationStatus.SUCCEEDED);

    List<MetaSource.TableMetaModel> tableMetaModels = mmaMetaManager.getPendingTables();

    Assert.assertEquals(0, tableMetaModels.size());
  }

  @Test
  public void testGetPendingTablesAfterMigrationJobPtSucceeded() throws MmaException {
    mmaMetaManager.addMigrationJob(TABLE_MIGRATION_CONFIG_PARTITIONED);
    mmaMetaManager.updateStatus(MockHiveMetaSource.DB_NAME,
                                MockHiveMetaSource.TBL_PARTITIONED,
                                Collections.singletonList(MockHiveMetaSource.TBL_PARTITIONED_PARTITION_VALUES),
                                MmaMetaManager.MigrationStatus.SUCCEEDED);

    List<MetaSource.TableMetaModel> tableMetaModels = mmaMetaManager.getPendingTables();

    Assert.assertEquals(1, tableMetaModels.size());
    MetaSource.TableMetaModel expected =
        MockHiveMetaSource.TABLE_NAME_2_TABLE_META_MODEL.get(MockHiveMetaSource.TBL_PARTITIONED);
    MetaSource.TableMetaModel actual = tableMetaModels.get(0);
    Assert.assertEquals(expected.databaseName, actual.databaseName);
    Assert.assertEquals(expected.tableName, actual.tableName);
    Assert.assertEquals(0, actual.partitions.size());
  }
}
