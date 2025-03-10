// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.iceberg;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.Table;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.profile.Timer;
import com.starrocks.common.profile.Tracers;
import com.starrocks.connector.ConnectorMetadata;
import com.starrocks.connector.HdfsEnvironment;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.connector.RemoteFileDesc;
import com.starrocks.connector.RemoteFileInfo;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.cost.IcebergMetricsReporter;
import com.starrocks.connector.iceberg.cost.IcebergStatisticProvider;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.statistics.Statistics;
import com.starrocks.thrift.TIcebergDataFile;
import com.starrocks.thrift.TSinkCommitInfo;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.BaseFileScanTask;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ResidualEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.TableScanUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.starrocks.common.profile.Tracers.Module.EXTERNAL;
import static com.starrocks.connector.ColumnTypeConverter.fromIcebergType;
import static com.starrocks.connector.PartitionUtil.convertIcebergPartitionToPartitionName;
import static com.starrocks.connector.PartitionUtil.createPartitionKeyWithType;
import static com.starrocks.connector.iceberg.IcebergApiConverter.parsePartitionFields;
import static com.starrocks.connector.iceberg.IcebergApiConverter.toIcebergApiSchema;
import static com.starrocks.connector.iceberg.IcebergCatalogType.GLUE_CATALOG;
import static com.starrocks.connector.iceberg.IcebergCatalogType.HIVE_CATALOG;
import static com.starrocks.connector.iceberg.IcebergCatalogType.REST_CATALOG;
import static com.starrocks.connector.iceberg.hive.IcebergHiveCatalog.LOCATION_PROPERTY;

public class IcebergMetadata implements ConnectorMetadata {

    private static final Logger LOG = LogManager.getLogger(IcebergMetadata.class);
    private final String catalogName;
    private final HdfsEnvironment hdfsEnvironment;
    private final IcebergCatalog icebergCatalog;
    private final IcebergStatisticProvider statisticProvider = new IcebergStatisticProvider();

    private final Map<TableIdentifier, Table> tables = new ConcurrentHashMap<>();
    private final Map<String, Database> databases = new ConcurrentHashMap<>();
    private final Map<IcebergFilter, List<FileScanTask>> splitTasks = new ConcurrentHashMap<>();
    private final Set<IcebergFilter> scannedTables = new HashSet<>();

    public IcebergMetadata(String catalogName, HdfsEnvironment hdfsEnvironment, IcebergCatalog icebergCatalog) {
        this.catalogName = catalogName;
        this.hdfsEnvironment = hdfsEnvironment;
        this.icebergCatalog = icebergCatalog;
        new IcebergMetricsReporter().setThreadLocalReporter();
    }

    @Override
    public List<String> listDbNames() {
        return icebergCatalog.listAllDatabases();
    }

    @Override
    public void createDb(String dbName, Map<String, String> properties) throws AlreadyExistsException {
        if (dbExists(dbName)) {
            throw new AlreadyExistsException("Database Already Exists");
        }

        icebergCatalog.createDb(dbName, properties);
    }

    @Override
    public void dropDb(String dbName, boolean isForceDrop) throws MetaNotFoundException {
        if (listTableNames(dbName).size() != 0) {
            throw new StarRocksConnectorException("Database %s not empty", dbName);
        }

        icebergCatalog.dropDb(dbName);
        databases.remove(dbName);
    }

    @Override
    public Database getDb(String dbName) {
        if (databases.containsKey(dbName)) {
            return databases.get(dbName);
        }
        Database db;
        try {
            db = icebergCatalog.getDB(dbName);
        } catch (NoSuchNamespaceException e) {
            LOG.error("Database {} not found", dbName, e);
            return null;
        }

        databases.put(dbName, db);
        return db;
    }

    @Override
    public List<String> listTableNames(String dbName) {
        return icebergCatalog.listTables(dbName);
    }

    @Override
    public boolean createTable(CreateTableStmt stmt) throws DdlException {
        String dbName = stmt.getDbName();
        String tableName = stmt.getTableName();

        Schema schema = toIcebergApiSchema(stmt.getColumns());
        PartitionDesc partitionDesc = stmt.getPartitionDesc();
        List<String> partitionColNames = partitionDesc == null ? Lists.newArrayList() :
                ((ListPartitionDesc) partitionDesc).getPartitionColNames();
        PartitionSpec partitionSpec = parsePartitionFields(schema, partitionColNames);
        Map<String, String> properties = stmt.getProperties() == null ? new HashMap<>() : stmt.getProperties();
        String tableLocation = properties.get(LOCATION_PROPERTY);
        Map<String, String> createTableProperties = IcebergApiConverter.rebuildCreateTableProperties(properties);

        return icebergCatalog.createTable(dbName, tableName, schema, partitionSpec, tableLocation, createTableProperties);
    }

    @Override
    public void dropTable(DropTableStmt stmt) {
        icebergCatalog.dropTable(stmt.getDbName(), stmt.getTableName(), stmt.isForceDrop());
        tables.remove(TableIdentifier.of(stmt.getDbName(), stmt.getTableName()));
    }

    @Override
    public Table getTable(String dbName, String tblName) {
        TableIdentifier identifier = TableIdentifier.of(dbName, tblName);
        if (tables.containsKey(identifier)) {
            return tables.get(identifier);
        }

        try {
            IcebergCatalogType catalogType = icebergCatalog.getIcebergCatalogType();
            org.apache.iceberg.Table icebergTable = icebergCatalog.getTable(dbName, tblName);
            Table table = IcebergApiConverter.toIcebergTable(icebergTable, catalogName, dbName, tblName, catalogType.name());
            tables.put(identifier, table);
            return table;

        } catch (StarRocksConnectorException | NoSuchTableException e) {
            LOG.error("Failed to get iceberg table {}", identifier, e);
            return null;
        }
    }

    @Override
    public List<String> listPartitionNames(String dbName, String tblName) {
        org.apache.iceberg.Table icebergTable = icebergCatalog.getTable(dbName, tblName);
        IcebergCatalogType nativeType = icebergCatalog.getIcebergCatalogType();

        if (nativeType != HIVE_CATALOG && nativeType != REST_CATALOG && nativeType != GLUE_CATALOG) {
            throw new StarRocksConnectorException(
                    "Do not support get partitions from catalog type: " + nativeType);
        }

        List<String> partitionNames = Lists.newArrayList();
        // all partitions specs are unpartitioned
        if (icebergTable.specs().values().stream().allMatch(PartitionSpec::isUnpartitioned)) {
            return partitionNames;
        }

        TableScan tableScan = icebergTable.newScan();
        List<FileScanTask> tasks = Lists.newArrayList(tableScan.planFiles());

        for (FileScanTask fileScanTask : tasks) {
            StructLike partition = fileScanTask.file().partition();
            partitionNames.add(convertIcebergPartitionToPartitionName(fileScanTask.spec(), partition));
        }
        return partitionNames;
    }

    @Override
    public List<RemoteFileInfo> getRemoteFileInfos(Table table, List<PartitionKey> partitionKeys,
                                                   long snapshotId, ScalarOperator predicate,
                                                   List<String> fieldNames, long limit) {
        return getRemoteFileInfos((IcebergTable) table, snapshotId, predicate, limit);
    }

    private List<RemoteFileInfo> getRemoteFileInfos(IcebergTable table, long snapshotId,
                                                    ScalarOperator predicate, long limit) {
        RemoteFileInfo remoteFileInfo = new RemoteFileInfo();
        String dbName = table.getRemoteDbName();
        String tableName = table.getRemoteTableName();

        IcebergFilter key = IcebergFilter.of(dbName, tableName, snapshotId, predicate);
        triggerIcebergPlanFilesIfNeeded(key, table, predicate, limit);

        List<FileScanTask> icebergScanTasks = splitTasks.get(key);
        if (icebergScanTasks == null) {
            throw new StarRocksConnectorException("Missing iceberg split task for table:[{}.{}]. predicate:[{}]",
                    dbName, tableName, predicate);
        }

        List<RemoteFileDesc> remoteFileDescs = Lists.newArrayList(RemoteFileDesc.createIcebergRemoteFileDesc(icebergScanTasks));
        remoteFileInfo.setFiles(remoteFileDescs);

        return Lists.newArrayList(remoteFileInfo);
    }

    private void triggerIcebergPlanFilesIfNeeded(IcebergFilter key, IcebergTable table, ScalarOperator predicate, long limit) {
        if (!scannedTables.contains(key)) {
            try (Timer ignored = Tracers.watchScope(EXTERNAL, "ICEBERG.processSplit." + key)) {
                collectTableStatisticsAndCacheIcebergSplit(table, predicate, limit);
            }
        }
    }

    public List<PartitionKey> getPrunedPartitions(Table table, ScalarOperator predicate, long limit) {
        IcebergTable icebergTable = (IcebergTable) table;
        String dbName = icebergTable.getRemoteDbName();
        String tableName = icebergTable.getRemoteTableName();
        Optional<Snapshot> snapshot = icebergTable.getSnapshot();
        if (!snapshot.isPresent()) {
            return new ArrayList<>();
        }

        IcebergFilter key = IcebergFilter.of(dbName, tableName, snapshot.get().snapshotId(), predicate);
        triggerIcebergPlanFilesIfNeeded(key, icebergTable, predicate, limit);

        List<PartitionKey> partitionKeys = new ArrayList<>();
        List<FileScanTask> icebergSplitTasks = splitTasks.get(key);
        if (icebergSplitTasks == null) {
            throw new StarRocksConnectorException("Missing iceberg split task for table:[{}.{}]. predicate:[{}]",
                    dbName, tableName, predicate);
        }

        Set<List<String>> scannedPartitions = new HashSet<>();
        PartitionSpec spec = icebergTable.getNativeTable().spec();
        List<Column> partitionColumns = icebergTable.getPartitionColumnsIncludeTransformed();
        for (FileScanTask fileScanTask : icebergSplitTasks) {
            org.apache.iceberg.PartitionData partitionData = (org.apache.iceberg.PartitionData) fileScanTask.file().partition();
            List<String> values = PartitionUtil.getIcebergPartitionValues(spec, partitionData);

            if (values.size() != partitionColumns.size()) {
                // ban partition evolution and non-identify column.
                continue;
            }

            if (scannedPartitions.contains(values)) {
                continue;
            } else {
                scannedPartitions.add(values);
            }

            try {
                List<com.starrocks.catalog.Type> srTypes = new ArrayList<>();
                for (PartitionField partitionField : spec.fields()) {
                    if (partitionField.transform().isVoid()) {
                        continue;
                    }

                    if (!partitionField.transform().isIdentity()) {
                        Type sourceType = spec.schema().findType(partitionField.sourceId());
                        Type resultType = partitionField.transform().getResultType(sourceType);
                        if (resultType == Types.DateType.get()) {
                            resultType = Types.IntegerType.get();
                        }
                        srTypes.add(fromIcebergType(resultType));
                        continue;
                    }

                    srTypes.add(icebergTable.getColumn(partitionField.name()).getType());
                }

                if (icebergTable.hasPartitionTransformedEvolution()) {
                    srTypes = partitionColumns.stream()
                            .map(Column::getType)
                            .collect(Collectors.toList());
                }

                partitionKeys.add(createPartitionKeyWithType(values, srTypes, table.getType()));
            } catch (Exception e) {
                LOG.error("create partition key failed.", e);
                throw new StarRocksConnectorException(e.getMessage());
            }
        }

        return partitionKeys;
    }

    private void collectTableStatisticsAndCacheIcebergSplit(Table table, ScalarOperator predicate, long limit) {
        IcebergTable icebergTable = (IcebergTable) table;
        Optional<Snapshot> snapshot = icebergTable.getSnapshot();
        // empty table
        if (!snapshot.isPresent()) {
            return;
        }

        long snapshotId = snapshot.get().snapshotId();
        String dbName = icebergTable.getRemoteDbName();
        String tableName = icebergTable.getRemoteTableName();
        IcebergFilter key = IcebergFilter.of(dbName, tableName, snapshotId, predicate);

        org.apache.iceberg.Table nativeTbl = icebergTable.getNativeTable();
        Types.StructType schema = nativeTbl.schema().asStruct();

        List<ScalarOperator> scalarOperators = Utils.extractConjuncts(predicate);
        ScalarOperatorToIcebergExpr.IcebergContext icebergContext = new ScalarOperatorToIcebergExpr.IcebergContext(schema);
        Expression icebergPredicate = new ScalarOperatorToIcebergExpr().convert(scalarOperators, icebergContext);

        TableScan scan = nativeTbl.newScan().useSnapshot(snapshotId);
        if (enableCollectColumnStatistics()) {
            scan = scan.includeColumnStats();
        }

        if (icebergPredicate.op() != Expression.Operation.TRUE) {
            scan = scan.filter(icebergPredicate);
        }

        CloseableIterable<FileScanTask> fileScanTaskIterable = TableScanUtil.splitFiles(
                scan.planFiles(), scan.targetSplitSize());
        CloseableIterator<FileScanTask> fileScanTaskIterator = fileScanTaskIterable.iterator();
        Iterator<FileScanTask> fileScanTasks;

        // Under the condition of ensuring that the data is correct, we disabled the limit optimization when table has
        // partition evolution because this may cause data diff.
        boolean canPruneManifests = limit != -1 && !icebergTable.isV2Format() && onlyHasPartitionPredicate(table, predicate)
                && limit < Integer.MAX_VALUE && nativeTbl.spec().specId() == 0 && enablePruneManifest();

        if (canPruneManifests) {
            // After iceberg uses partition predicate plan files, each manifests entry must have at least one row of data.
            fileScanTasks = Iterators.limit(fileScanTaskIterator, (int) limit);
        } else {
            fileScanTasks = fileScanTaskIterator;
        }

        List<Types.NestedField> fullColumns = nativeTbl.schema().columns();
        Map<Integer, Type.PrimitiveType> idToTypeMapping = fullColumns.stream()
                .filter(column -> column.type().isPrimitiveType())
                .collect(Collectors.toMap(Types.NestedField::fieldId, column -> column.type().asPrimitiveType()));

        Set<Integer> identityPartitionIds = nativeTbl.spec().fields().stream()
                .filter(x -> x.transform().isIdentity())
                .map(PartitionField::sourceId)
                .collect(Collectors.toSet());

        List<Types.NestedField> nonPartitionPrimitiveColumns = fullColumns.stream()
                .filter(column -> !identityPartitionIds.contains(column.fieldId()) &&
                        column.type().isPrimitiveType())
                .collect(toImmutableList());

        List<FileScanTask> icebergScanTasks = Lists.newArrayList();
        long totalReadCount = 0;

        // FileScanTask are splits of file. Avoid calculating statistics for a file multiple times.
        Set<String> filePaths = new HashSet<>();
        while (fileScanTasks.hasNext()) {
            FileScanTask scanTask = fileScanTaskIterator.next();
            statisticProvider.updateIcebergFileStats(
                    icebergTable, scanTask, idToTypeMapping, nonPartitionPrimitiveColumns, key);

            FileScanTask icebergSplitScanTask = scanTask;
            if (enableCollectColumnStatistics()) {
                icebergSplitScanTask = buildIcebergSplitScanTask(scanTask, icebergPredicate);
            }
            icebergScanTasks.add(icebergSplitScanTask);

            String filePath = icebergSplitScanTask.file().path().toString();
            if (!filePaths.contains(filePath)) {
                filePaths.add(filePath);
                totalReadCount += scanTask.file().recordCount();
            }

            if (canPruneManifests && totalReadCount >= limit) {
                break;
            }
        }

        try {
            fileScanTaskIterable.close();
            fileScanTaskIterator.close();
        } catch (IOException e) {
            // Ignored
        }

        IcebergMetricsReporter.lastReport().ifPresent(scanReportWithCounter ->
                Tracers.record(Tracers.Module.EXTERNAL, "ICEBERG.ScanMetrics." +
                                scanReportWithCounter.getScanReport().tableName() + " / No_" +
                                scanReportWithCounter.getCount(),
                        scanReportWithCounter.getScanReport().scanMetrics().toString()));

        splitTasks.put(key, icebergScanTasks);
        scannedTables.add(key);
    }

    @Override
    public Statistics getTableStatistics(OptimizerContext session,
                                         Table table,
                                         Map<ColumnRefOperator, Column> columns,
                                         List<PartitionKey> partitionKeys,
                                         ScalarOperator predicate,
                                         long limit) {
        IcebergTable icebergTable = (IcebergTable) table;
        Optional<Snapshot> snapshot = icebergTable.getSnapshot();
        long snapshotId;
        if (snapshot.isPresent()) {
            snapshotId = snapshot.get().snapshotId();
        } else {
            Statistics.Builder statisticsBuilder = Statistics.builder();
            statisticsBuilder.setOutputRowCount(1);
            statisticsBuilder.addColumnStatistics(statisticProvider.buildUnknownColumnStatistics(columns.keySet()));
            return statisticsBuilder.build();
        }

        IcebergFilter key = IcebergFilter.of(
                icebergTable.getRemoteDbName(), icebergTable.getRemoteTableName(), snapshotId, predicate);

        triggerIcebergPlanFilesIfNeeded(key, icebergTable, predicate, limit);

        return statisticProvider.getTableStatistics(icebergTable, columns, session, predicate);
    }

    private IcebergSplitScanTask buildIcebergSplitScanTask(FileScanTask fileScanTask, Expression icebergPredicate) {
        long offset = fileScanTask.start();
        long length = fileScanTask.length();
        DataFile dataFileWithoutStats = fileScanTask.file().copyWithoutStats();
        DeleteFile[] deleteFiles = fileScanTask.deletes().stream()
                .map(DeleteFile::copyWithoutStats)
                .toArray(DeleteFile[]::new);
        String schemaString = SchemaParser.toJson(fileScanTask.spec().schema());
        String partitionString = PartitionSpecParser.toJson(fileScanTask.spec());
        ResidualEvaluator residualEvaluator = ResidualEvaluator.of(fileScanTask.spec(), icebergPredicate, true);

        BaseFileScanTask baseFileScanTask = new BaseFileScanTask(
                dataFileWithoutStats,
                deleteFiles,
                schemaString,
                partitionString,
                residualEvaluator);
        return new IcebergSplitScanTask(offset, length, baseFileScanTask);
    }

    @Override
    public void refreshTable(String srDbName, Table table, List<String> partitionNames, boolean onlyCachedPartitions) {
        IcebergTable icebergTable = (IcebergTable) table;
        org.apache.iceberg.Table nativeTable = icebergTable.getNativeTable();
        try {
            if (nativeTable instanceof BaseTable) {
                BaseTable baseTable = (BaseTable) nativeTable;
                if (baseTable.operations().refresh() == null) {
                    // If table is loaded successfully, current table metadata will never be null.
                    // So when we get a null metadata after refresh, it indicates the table has been dropped.
                    // See: https://github.com/StarRocks/starrocks/issues/3076
                    throw new NoSuchTableException("No such table: %s", nativeTable.name());
                }
            } else {
                // table loaded by GlobalStateMgr should be a base table
                throw new StarRocksConnectorException("Invalid table type of %s, it should be a BaseTable!", nativeTable.name());
            }
        } catch (NoSuchTableException e) {
            throw new StarRocksConnectorException("No such table  %s", nativeTable.name());
        } catch (IllegalStateException ei) {
            throw new StarRocksConnectorException("Refresh table %s with failure, the table under hood" +
                    " may have been dropped. You should re-create the external table. cause %s",
                    nativeTable.name(), ei.getMessage());
        }

        icebergTable.resetSnapshot();
    }

    @Override
    public void finishSink(String dbName, String tableName, List<TSinkCommitInfo> commitInfos) {
        boolean isOverwrite = false;
        if (!commitInfos.isEmpty()) {
            TSinkCommitInfo sinkCommitInfo = commitInfos.get(0);
            if (sinkCommitInfo.isSetIs_overwrite()) {
                isOverwrite = sinkCommitInfo.is_overwrite;
            }
        }

        List<TIcebergDataFile> dataFiles = commitInfos.stream()
                .map(TSinkCommitInfo::getIceberg_data_file).collect(Collectors.toList());

        IcebergTable table = (IcebergTable) getTable(dbName, tableName);
        org.apache.iceberg.Table nativeTbl = table.getNativeTable();
        Transaction transaction = nativeTbl.newTransaction();
        BatchWrite batchWrite = getBatchWrite(transaction, isOverwrite);

        PartitionSpec partitionSpec = nativeTbl.spec();
        for (TIcebergDataFile dataFile : dataFiles) {
            Metrics metrics = IcebergApiConverter.buildDataFileMetrics(dataFile);
            DataFiles.Builder builder =
                    DataFiles.builder(partitionSpec)
                            .withMetrics(metrics)
                            .withPath(dataFile.path)
                            .withFormat(dataFile.format)
                            .withRecordCount(dataFile.record_count)
                            .withFileSizeInBytes(dataFile.file_size_in_bytes)
                            .withSplitOffsets(dataFile.split_offsets);

            if (partitionSpec.isPartitioned()) {
                String relativePartitionLocation = getIcebergRelativePartitionPath(
                        nativeTbl.location(), dataFile.partition_path);

                PartitionData partitionData = partitionDataFromPath(
                        relativePartitionLocation, partitionSpec);
                builder.withPartition(partitionData);
            }
            batchWrite.addFile(builder.build());
        }

        try {
            batchWrite.commit();
            transaction.commitTransaction();
        } catch (Exception e) {
            List<String> toDeleteFiles = dataFiles.stream()
                    .map(TIcebergDataFile::getPath)
                    .collect(Collectors.toList());
            icebergCatalog.deleteUncommittedDataFiles(toDeleteFiles);
            LOG.error("Failed to commit iceberg transaction on {}.{}", dbName, tableName, e);
            throw new StarRocksConnectorException(e.getMessage());
        }
    }

    public BatchWrite getBatchWrite(Transaction transaction, boolean isOverwrite) {
        return isOverwrite ? new DynamicOverwrite(transaction) : new Append(transaction);
    }

    public static PartitionData partitionDataFromPath(String relativePartitionPath, PartitionSpec spec) {
        PartitionData data = new PartitionData(spec.fields().size());
        String[] partitions = relativePartitionPath.split("/", -1);
        List<PartitionField> partitionFields = spec.fields();

        for (int i = 0; i < partitions.length; i++) {
            PartitionField field = partitionFields.get(i);
            String[] parts = partitions[i].split("=", 2);
            Preconditions.checkArgument(parts.length == 2 && parts[0] != null &&
                    field.name().equals(parts[0]), "Invalid partition: %s", partitions[i]);

            org.apache.iceberg.types.Type sourceType = spec.partitionType().fields().get(i).type();
            data.set(i, Conversions.fromPartitionString(sourceType, parts[1]));
        }
        return data;
    }

    public static String getIcebergRelativePartitionPath(String tableLocation, String partitionLocation) {
        tableLocation = tableLocation.endsWith("/") ? tableLocation.substring(0, tableLocation.length() - 1) : tableLocation;
        String tableLocationWithData = tableLocation + "/data/";
        String path = PartitionUtil.getSuffixName(tableLocationWithData, partitionLocation);
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public static boolean onlyHasPartitionPredicate(Table table, ScalarOperator predicate) {
        if (predicate == null) {
            return true;
        }

        List<ColumnRefOperator> columnRefOperators = predicate.getColumnRefs();
        List<String> partitionColNames = table.getPartitionColumnNames();
        for (ColumnRefOperator c : columnRefOperators) {
            if (!partitionColNames.contains(c.getName())) {
                return false;
            }
        }

        return true;
    }

    private boolean enablePruneManifest() {
        if (ConnectContext.get() == null) {
            return false;
        }

        if (ConnectContext.get().getSessionVariable() == null) {
            return false;
        }

        return ConnectContext.get().getSessionVariable().isEnablePruneIcebergManifest();
    }

    private boolean enableCollectColumnStatistics() {
        if (ConnectContext.get() == null) {
            return false;
        }

        if (ConnectContext.get().getSessionVariable() == null) {
            return false;
        }

        return ConnectContext.get().getSessionVariable().enableIcebergColumnStatistics();
    }

    @Override
    public void clear() {
        splitTasks.clear();
        tables.clear();
        databases.clear();
        scannedTables.clear();
        IcebergMetricsReporter.remove();
    }

    interface BatchWrite {
        void addFile(DataFile file);

        void commit();
    }

    static class Append implements BatchWrite {
        private final AppendFiles append;

        public Append(Transaction txn) {
            append = txn.newAppend();
        }

        @Override
        public void addFile(DataFile file) {
            append.appendFile(file);
        }

        @Override
        public void commit() {
            append.commit();
        }
    }

    static class DynamicOverwrite implements BatchWrite {
        private final ReplacePartitions replace;

        public DynamicOverwrite(Transaction txn) {
            replace = txn.newReplacePartitions();
        }

        @Override
        public void addFile(DataFile file) {
            replace.addFile(file);
        }

        @Override
        public void commit() {
            replace.commit();
        }
    }

    public static class PartitionData implements StructLike {
        private final Object[] values;

        private PartitionData(int size) {
            this.values = new Object[size];
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public <T> T get(int pos, Class<T> javaClass) {
            return javaClass.cast(values[pos]);
        }

        @Override
        public <T> void set(int pos, T value) {
            if (value instanceof ByteBuffer) {
                ByteBuffer buffer = (ByteBuffer) value;
                byte[] bytes = new byte[buffer.remaining()];
                buffer.duplicate().get(bytes);
                values[pos] = bytes;
            } else {
                values[pos] = value;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }

            PartitionData that = (PartitionData) other;
            return Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    @Override
    public CloudConfiguration getCloudConfiguration() {
        return hdfsEnvironment.getCloudConfiguration();
    }
}
