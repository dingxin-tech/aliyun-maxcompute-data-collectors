package com.aliyun.odps.datacarrier.taskscheduler;

import static com.aliyun.odps.datacarrier.taskscheduler.MmaMetaManagerDbImplUtils.MigrationJobInfo;
import static com.aliyun.odps.datacarrier.taskscheduler.MmaMetaManagerDbImplUtils.MigrationJobPtInfo;
import static com.aliyun.odps.datacarrier.taskscheduler.MmaMetaManagerDbImplUtils.mergeIntoMmaPartitionMeta;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


/**
 * This class implements {@link MmaMetaManager} using a H2 embedded database.
 */

public class MmaMetaManagerDbImpl implements MmaMetaManager {

  private static final Logger LOG = LogManager.getLogger(MmaMetaManagerDbImpl.class);

  private HikariDataSource ds;
  private MetaSource metaSource;

  public MmaMetaManagerDbImpl(Path parentDir, MetaSource metaSource) throws MmaException {
    if (parentDir == null) {
      // Ensure MMA_HOME is set
      String mmaHome = System.getenv("MMA_HOME");
      if (mmaHome == null) {
        throw new IllegalStateException("Environment variable 'MMA_HOME' not set");
      }
      parentDir = Paths.get(mmaHome);
    }

    this.metaSource = metaSource;

    LOG.info("Initialize MmaMetaManagerDbImpl");
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException e) {
      LOG.error("H2 JDBC driver not found");
      throw new IllegalStateException("Class not found: org.h2.Driver");
    }

    LOG.info("Create connection pool");
    String connectionUrl =
        "jdbc:h2:file:" + Paths.get(parentDir.toString(), Constants.DB_FILE_NAME).toAbsolutePath() +
        ";AUTO_SERVER=TRUE";
    setupDatasource(connectionUrl);
    LOG.info("JDBC connection URL: {}", connectionUrl);

    LOG.info("Create connection pool done");

    LOG.info("Setup database");
    try (Connection conn = ds.getConnection()) {
      MmaMetaManagerDbImplUtils.createMmaTableMeta(conn);
      conn.commit();
    } catch (Throwable e) {
      throw new MmaException("Setting up database failed", e);
    }
    LOG.info("Setup database done");

    LOG.info("Recover");
    try {
      recover();
    } catch (Throwable e) {
      throw new IllegalStateException("Recover failed", e);
    }
    LOG.info("Recover done");
    LOG.info("Initialize MmaMetaManagerDbImpl done");
  }

  private void setupDatasource(String connectionUrl) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(connectionUrl);
    hikariConfig.setUsername("mma");
    hikariConfig.setPassword("mma");
    hikariConfig.setAutoCommit(false);
    hikariConfig.setMaximumPoolSize(10);
    hikariConfig.setMinimumIdle(1);
    hikariConfig.setTransactionIsolation("TRANSACTION_SERIALIZABLE");
    ds = new HikariDataSource(hikariConfig);
  }

  public void shutdown() {
    metaSource.shutdown();
    ds.close();
  }

  private void recover() throws SQLException {
    // Set running to pending
  }

  @Override
  public synchronized void addMigrationJob(MmaConfig.TableMigrationConfig config)
      throws MmaException {

    LOG.info("Enter addMigrationJob");

    if (config == null) {
      throw new IllegalArgumentException("'config' cannot be null");
    }

    String db = config.getSourceDataBaseName().toLowerCase();
    String tbl = config.getSourceTableName().toLowerCase();
    LOG.info("Add migration job, db: {}, tbl: {}", db, tbl);

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo != null) {
          if (MigrationStatus.PENDING.equals(jobInfo.getStatus())) {
            // Restart running job is not allowed
            MmaException e = MmaExceptionFactory.getRunningMigrationJobExistsException(db, tbl);
            LOG.error(e);
            throw e;
          }
        }

        MetaSource.TableMetaModel tableMetaModel =
            metaSource.getTableMetaWithoutPartitionMeta(db, tbl);
        boolean isPartitioned = tableMetaModel.partitionColumns.size() > 0;

        // Create or update mma table meta
        jobInfo = new MigrationJobInfo(db,
                                       tbl,
                                       isPartitioned,
                                       config,
                                       MigrationStatus.PENDING,
                                       Constants.MMA_TBL_META_INIT_ATTEMPT_TIMES,
                                       Constants.MMA_TBL_META_INIT_LAST_SUCC_TIMESTAMP);

        MmaMetaManagerDbImplUtils.mergeIntoMmaTableMeta(conn, jobInfo);

        // Create or update mma partition meta
        if (isPartitioned) {
          MmaMetaManagerDbImplUtils.createMmaPartitionMetaSchema(conn, db);
          MmaMetaManagerDbImplUtils.createMmaPartitionMeta(conn, db, tbl);

          // If partitions not specified, get the latest metadata from HMS
          List<List<String>> newPartitionValuesList;
          if (config.getPartitionValuesList() != null) {
            newPartitionValuesList = config.getPartitionValuesList();
          } else {
            newPartitionValuesList = metaSource.listPartitions(db, tbl);
          }

          newPartitionValuesList = MmaMetaManagerDbImplUtils.filterOutExistingPartitions(
              conn, db, tbl, newPartitionValuesList);

          List<MigrationJobPtInfo> migrationJobPtInfos = newPartitionValuesList
              .stream()
              .map(ptv -> new MigrationJobPtInfo(ptv,
                                                 MigrationStatus.PENDING,
                                                 Constants.MMA_PT_META_INIT_ATTEMPT_TIMES,
                                                 Constants.MMA_PT_META_INIT_LAST_SUCC_TIMESTAMP))
              .collect(Collectors.toList());
          MmaMetaManagerDbImplUtils.mergeIntoMmaPartitionMeta(conn, db, tbl, migrationJobPtInfos);
        }

        conn.commit();
        LOG.info("Leave addMigrationJob");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Add migration job rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToAddMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void removeMigrationJob(String db, String tbl) throws MmaException {

    LOG.info("Enter removeMigrationJob");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          return;
        } else {
          if (MigrationStatus.PENDING.equals(jobInfo.getStatus())) {
            // Restart running job is not allowed
            MmaException e = MmaExceptionFactory.getRunningMigrationJobExistsException(db, tbl);
            LOG.error(e);
            throw e;
          }
        }

        if (jobInfo.isPartitioned()) {
          MmaMetaManagerDbImplUtils.dropMmaPartitionMeta(conn, db, tbl);
        }
        MmaMetaManagerDbImplUtils.deleteFromMmaMeta(conn, db, tbl);

        conn.commit();
        LOG.info("Leave removeMigrationJob");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Remove migration job rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToRemoveMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized boolean hasMigrationJob(String db, String tbl) throws MmaException {
    LOG.info("Enter hasMigrationJob");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        return MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl) != null;
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized List<MmaConfig.TableMigrationConfig> listMigrationJobs(int limit)
      throws MmaException {

    return listMigrationJobsInternal(null, limit);
  }

  @Override
  public synchronized List<MmaConfig.TableMigrationConfig> listMigrationJobs(MigrationStatus status,
                                                                             int limit)
      throws MmaException {

    return listMigrationJobsInternal(status, limit);
  }

  private List<MmaConfig.TableMigrationConfig> listMigrationJobsInternal(MigrationStatus status,
                                                                         int limit)
      throws MmaException {

    try (Connection conn = ds.getConnection()) {
      try {
        return MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, status, limit)
            .stream().map(MigrationJobInfo::getMigrationConfig)
            .collect(Collectors.toList());
      } catch (Throwable e) {
        MmaException mmaException = MmaExceptionFactory.getFailedToListMigrationJobsException(e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void updateStatus(String db, String tbl, MigrationStatus status)
      throws MmaException {
    LOG.info("Enter updateStatus");

    if (db == null || tbl == null || status == null) {
      throw new IllegalArgumentException("'db' or 'tbl' or 'status' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        MigrationJobInfo newJobInfo;
        int attemptTimes = jobInfo.getAttemptTimes();
        attemptTimes += 1;
        int retryTimesLimit = jobInfo
            .getMigrationConfig()
            .getAdditionalTableConfig()
            .getRetryTimesLimit();
        long lastSuccTimestamp = jobInfo.getLastSuccTimestamp();

        if (MigrationStatus.FAILED.equals(status)) {
          lastSuccTimestamp = -1;
          if (attemptTimes <= retryTimesLimit) {
            status = MigrationStatus.PENDING;
          }
        } else if (MigrationStatus.SUCCEEDED.equals(status)) {
          lastSuccTimestamp = System.currentTimeMillis();
        }

        newJobInfo = new MigrationJobInfo(db,
                                          tbl,
                                          jobInfo.isPartitioned(),
                                          jobInfo.getMigrationConfig(),
                                          status,
                                          attemptTimes,
                                          lastSuccTimestamp);
        MmaMetaManagerDbImplUtils.mergeIntoMmaTableMeta(conn, newJobInfo);

        conn.commit();
        LOG.info("Leave updateStatus");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Update migration job rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToUpdateMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void updateStatus(String db, String tbl, List<List<String>> partitionValuesList,
                           MigrationStatus status) throws MmaException {
    LOG.info("Enter updateStatus");

    if (db == null || tbl == null || partitionValuesList == null || status == null) {
      throw new IllegalArgumentException(
          "'db' or 'tbl' or 'partitionValuesList' or 'status' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        for (List<String> partitionValues : partitionValuesList) {
          MigrationJobPtInfo jobPtInfo =
              MmaMetaManagerDbImplUtils.selectFromMmaPartitionMeta(conn, db, tbl, partitionValues);
          if (jobPtInfo == null) {
            throw MmaExceptionFactory.getMigrationJobPtNotExistedException(db, tbl, partitionValues);
          }

          int attemptTimes = jobPtInfo.getAttemptTimes();
          attemptTimes += 1;
          long lastSuccTimestamp = jobPtInfo.getLastSuccTimestamp();
          if (MigrationStatus.FAILED.equals(status)) {
            lastSuccTimestamp = -1;
          } else if (MigrationStatus.SUCCEEDED.equals(status)) {
            lastSuccTimestamp = System.currentTimeMillis();
          }

          MigrationJobPtInfo newJobPtInfo =
              new MigrationJobPtInfo(jobPtInfo.getPartitionValues(),
                                     status,
                                     attemptTimes,
                                     lastSuccTimestamp);
          mergeIntoMmaPartitionMeta(conn, db, tbl, Collections.singletonList(newJobPtInfo));
        }

        conn.commit();
        LOG.info("Leave updateStatus");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Update migration job pt rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToUpdateMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationStatus getStatus(String db, String tbl) throws MmaException {
    LOG.info("Enter getStatus");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }
        return jobInfo.getStatus();
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationStatus getStatus(String db, String tbl, List<String> partitionValues)
      throws MmaException {
    LOG.info("Enter getStatus");

    if (db == null || tbl == null || partitionValues == null) {
      throw new IllegalArgumentException("'db' or 'tbl' or 'partitionValues' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobPtInfo jobPtInfo =
            MmaMetaManagerDbImplUtils.selectFromMmaPartitionMeta(conn, db, tbl, partitionValues);
        if (jobPtInfo == null) {
          throw MmaExceptionFactory.getMigrationJobPtNotExistedException(db, tbl, partitionValues);
        }
        return jobPtInfo.getStatus();
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobPtException(db, tbl, partitionValues);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationProgress getProgress(String db, String tbl) throws MmaException {
    LOG.info("Enter getProgress");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        if (!jobInfo.isPartitioned()) {
          return null;
        }

        try (Statement stmt = conn.createStatement()) {
          String sql = "SELECT status, COUNT(1) FROM " +
                       Constants.MMA_TBL_META_TBL_NAME + " GROUP BY status";
          try (ResultSet rs = stmt.executeQuery(sql)) {
            int numPendingPartitions = 0;
            int numRunningPartitions = 0;
            int numSucceededPartitions = 0;
            int numFailedPartitions = 0;
            while (rs.next()) {
              switch (MigrationStatus.valueOf(rs.getString(1))) {
                case PENDING:
                  numPendingPartitions += 1;
                  break;
                case RUNNING:
                  numRunningPartitions += 1;
                  break;
                case SUCCEEDED:
                  numSucceededPartitions += 1;
                  break;
                case FAILED:
                  numFailedPartitions += 1;
                  break;
              }
            }
            return new MigrationProgress(numPendingPartitions,
                                         numRunningPartitions,
                                         numSucceededPartitions,
                                         numFailedPartitions);
          }
        }
      } catch (Throwable e) {
        return null;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MmaConfig.TableMigrationConfig getConfig(String db, String tbl)
      throws MmaException {
    LOG.info("Enter getConfig");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobInfo jobInfo = MmaMetaManagerDbImplUtils.selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }
        return jobInfo.getMigrationConfig();
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(ExceptionUtils.getStackTrace(e));
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public List<MetaSource.TableMetaModel> getPendingTables() throws MmaException {
    LOG.info("Enter getPendingTables");

    try (Connection conn = ds.getConnection()) {
      try {
        try (Statement stmt = conn.createStatement()) {
          String sql = String.format("SELECT %s, %s, %s FROM %s WHERE %s='%s'",
                                     Constants.MMA_TBL_META_COL_DB_NAME,
                                     Constants.MMA_TBL_META_COL_TBL_NAME,
                                     Constants.MMA_TBL_META_COL_MIGRATION_CONF,
                                     Constants.MMA_TBL_META_TBL_NAME,
                                     Constants.MMA_TBL_META_COL_STATUS,
                                     MigrationStatus.PENDING);
          List<MetaSource.TableMetaModel> ret = new LinkedList<>();
          try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
              String db = rs.getString(1);
              String tbl = rs.getString(2);
              MmaConfig.TableMigrationConfig config =
                  MmaConfig.TableMigrationConfig.fromJson(rs.getString(3));

              MetaSource.TableMetaModel tableMetaModel =
                  metaSource.getTableMetaWithoutPartitionMeta(db, tbl);

              if (tableMetaModel.partitionColumns.size() > 0) {
                List<MigrationJobPtInfo> jobPtInfos =
                    MmaMetaManagerDbImplUtils.selectFromMmaPartitionMeta(conn,
                                                                         db,
                                                                         tbl,
                                                                         MigrationStatus.PENDING,
                                                                         -1);
                List<MetaSource.PartitionMetaModel> partitionMetaModels = new LinkedList<>();

                for (MigrationJobPtInfo jobPtInfo : jobPtInfos) {
                  partitionMetaModels.add(
                      metaSource.getPartitionMeta(db, tbl, jobPtInfo.getPartitionValues()));
                }

                tableMetaModel.partitions = partitionMetaModels;
              }

              config.apply(tableMetaModel);
              ret.add(tableMetaModel);
            }

            return ret;
          }
        }
      } catch (Throwable e) {
        MmaException mmaException = MmaExceptionFactory.getFailedToGetPendingJobsException(e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public MetaSource.TableMetaModel getNextPendingTable() {
    throw new UnsupportedOperationException();
  }
}
