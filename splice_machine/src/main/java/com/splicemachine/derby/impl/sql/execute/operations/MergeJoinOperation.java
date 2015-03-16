package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.derby.hbase.SpliceObserverInstructions;
import com.splicemachine.derby.iapi.sql.execute.*;
import com.splicemachine.derby.impl.spark.RDDUtils;
import com.splicemachine.derby.metrics.OperationMetric;
import com.splicemachine.derby.metrics.OperationRuntimeStats;
import com.splicemachine.derby.utils.*;
import com.splicemachine.derby.utils.marshall.BareKeyHash;
import com.splicemachine.derby.utils.marshall.DataHash;
import com.splicemachine.derby.utils.marshall.dvd.DescriptorSerializer;
import com.splicemachine.derby.utils.marshall.dvd.VersionedSerializers;
import com.splicemachine.metrics.TimeView;
import com.splicemachine.metrics.IOStats;
import com.splicemachine.mrio.api.serde.SpliceSplit;
import com.splicemachine.derby.utils.StandardIterators;
import com.splicemachine.derby.utils.StandardPushBackIterator;
import com.splicemachine.derby.utils.StandardSupplier;
import com.splicemachine.mrio.api.serde.SpliceSplit;
import com.splicemachine.pipeline.exception.Exceptions;
import com.splicemachine.utils.IntArrays;
import com.splicemachine.utils.SpliceLogUtils;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.execute.ExecRow;

import org.apache.hadoop.hbase.mapreduce.TableSplit;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.log4j.Logger;
import org.apache.spark.Partition;
import org.apache.spark.Partitioner;
import org.apache.spark.api.java.JavaNewHadoopRDD;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction2;
import org.apache.spark.rdd.NewHadoopPartition;
import org.apache.spark.rdd.NewHadoopRDD;

import scala.Function1;
import scala.Function2;
import scala.Tuple2;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

/**
 * @author P Trolard
 *         Date: 18/11/2013
 */
public class MergeJoinOperation extends JoinOperation {

    private static final Logger LOG = Logger.getLogger(MergeJoinOperation.class);

    static List<NodeType> nodeTypes = Arrays.asList(NodeType.MAP);
    private int leftHashKeyItem;
    private int rightHashKeyItem;
    int[] leftHashKeys;
    int[] rightHashKeys;
    Joiner joiner;
    private OperationResultSet ors;


    // for overriding
    protected boolean wasRightOuterJoin = false;
    private IOStandardIterator<ExecRow> rightRows;

    protected static final String NAME = MergeJoinOperation.class.getSimpleName().replaceAll("Operation","");

	@Override
	public String getName() {
			return NAME;
	}

    
    public MergeJoinOperation() {
        super();
    }

    public MergeJoinOperation(SpliceOperation leftResultSet,
                              int leftNumCols,
                              SpliceOperation rightResultSet,
                              int rightNumCols,
                              int leftHashKeyItem,
                              int rightHashKeyItem,
                              Activation activation,
                              GeneratedMethod restriction,
                              int resultSetNumber,
                              boolean oneRowRightSide,
                              boolean notExistsRightSide,
                              double optimizerEstimatedRowCount,
                              double optimizerEstimatedCost,
                              String userSuppliedOptimizerOverrides)
            throws StandardException {
        super(leftResultSet, leftNumCols, rightResultSet, rightNumCols,
                 activation, restriction, resultSetNumber, oneRowRightSide,
                 notExistsRightSide, optimizerEstimatedRowCount,
                 optimizerEstimatedCost, userSuppliedOptimizerOverrides);
        this.leftHashKeyItem = leftHashKeyItem;
        this.rightHashKeyItem = rightHashKeyItem;
        try {
            init(SpliceOperationContext.newContext(activation));
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
    }

    @Override
    public List<NodeType> getNodeTypes() {
        return nodeTypes;
    }
    
    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
    	super.init(context);
        leftHashKeys = generateHashKeys(leftHashKeyItem);
        rightHashKeys = generateHashKeys(rightHashKeyItem);
    	if (LOG.isDebugEnabled()) {
    		SpliceLogUtils.debug(LOG,"left hash keys {%s}",Arrays.toString(leftHashKeys));
    		SpliceLogUtils.debug(LOG,"right hash keys {%s}",Arrays.toString(rightHashKeys));
    	}
        startExecutionTime = System.currentTimeMillis();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        out.writeInt(leftHashKeyItem);
        out.writeInt(rightHashKeyItem);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        leftHashKeyItem = in.readInt();
        rightHashKeyItem = in.readInt();
    }

    @Override
    public ExecRow nextRow(SpliceRuntimeContext ctx) throws StandardException, IOException {
        if (joiner == null) {
            // Upon first call, init up the joined rows source
            joiner = initJoiner(ctx);
            timer = ctx.newTimer();
            timer.startTiming();
        }

        ExecRow next = joiner.nextRow(ctx);
        setCurrentRow(next);
        if (next == null) {
            timer.tick(joiner.getLeftRowsSeen());
            ors.close();
            removeFromOperationChain();
            joiner.close();
            stopExecutionTime = System.currentTimeMillis();
        }
        return next;
    }

    private Joiner initJoiner(final SpliceRuntimeContext<ExecRow> spliceRuntimeContext)
            throws StandardException, IOException {
        StandardPushBackIterator<ExecRow> leftPushBack =
                new StandardPushBackIterator<ExecRow>(StandardIterators.wrap(leftResultSet));
        ExecRow firstLeft = leftPushBack.next(spliceRuntimeContext);
        SpliceRuntimeContext<ExecRow> ctxWithOverride = spliceRuntimeContext.copy();
        ctxWithOverride.unMarkAsSink();
        if (firstLeft != null) {
            firstLeft = firstLeft.getClone();
            ctxWithOverride.addScanStartOverride(getKeyRow(firstLeft, leftHashKeys));
            leftPushBack.pushBack(firstLeft);
        }

        if (shouldRecordStats()) {
            addToOperationChain(spliceRuntimeContext, null, rightResultSet.getUniqueSequenceID());
        }
        ors = new OperationResultSet(activation,rightResultSet);
        ors.sinkOpen(spliceRuntimeContext.getTxn(),true);
        ors.executeScan(false,ctxWithOverride);
        SpliceNoPutResultSet resultSet = ors.getDelegate();
        rightRows = StandardIterators.ioIterator(resultSet);
        rightRows.open();
        IJoinRowsIterator<ExecRow> mergedRowSource = new MergeJoinRows(leftPushBack, rightRows, leftHashKeys, rightHashKeys);
        StandardSupplier<ExecRow> emptyRowSupplier = new StandardSupplier<ExecRow>() {
            @Override
            public ExecRow get() throws StandardException {
                return getEmptyRow();
            }
        };

        return new Joiner(mergedRowSource, getExecRowDefinition(), getRestriction(),
                             isOuterJoin, wasRightOuterJoin, leftNumCols, rightNumCols,
                             oneRowRightSide, notExistsRightSide, emptyRowSupplier,spliceRuntimeContext);
    }

    @Override
    protected void updateStats(OperationRuntimeStats stats) {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "updateStats");
        if (joiner != null) {
            long leftRowsSeen = joiner.getLeftRowsSeen();
            stats.addMetric(OperationMetric.INPUT_ROWS, leftRowsSeen);
            TimeView time = timer.getTime();
            stats.addMetric(OperationMetric.OUTPUT_ROWS, timer.getNumEvents());
            stats.addMetric(OperationMetric.TOTAL_WALL_TIME,time.getWallClockTime());
            stats.addMetric(OperationMetric.TOTAL_CPU_TIME,time.getCpuTime());
            stats.addMetric(OperationMetric.TOTAL_USER_TIME, time.getUserTime());
            stats.addMetric(OperationMetric.FILTERED_ROWS, joiner.getRowsFiltered());
        }

        if (rightRows != null) {
            IOStats rightSideStats = rightRows.getStats();
            TimeView remoteView = rightSideStats.getTime();
            stats.addMetric(OperationMetric.REMOTE_SCAN_WALL_TIME,remoteView.getWallClockTime());
            stats.addMetric(OperationMetric.REMOTE_SCAN_CPU_TIME,remoteView.getCpuTime());
            stats.addMetric(OperationMetric.REMOTE_SCAN_USER_TIME,remoteView.getUserTime());
            stats.addMetric(OperationMetric.REMOTE_SCAN_ROWS,rightSideStats.elementsSeen());
            stats.addMetric(OperationMetric.REMOTE_SCAN_BYTES,rightSideStats.bytesSeen());
        }
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "leftRows %d, rightRows %d, rowsFiltered=%d",joiner.getLeftRowsSeen(), joiner.getRightRowsSeen(),joiner.getRowsFiltered());

        super.updateStats(stats);
    }

    private ExecRow getKeyRow(ExecRow row, int[] keyIndexes) throws StandardException {
        ExecRow keyRow = activation.getExecutionFactory().getValueRow(keyIndexes.length);
        for (int i = 0; i < keyIndexes.length; i++) {
            keyRow.setColumn(i + 1, row.getColumn(keyIndexes[i] + 1));
        }
        return keyRow;
    }

    @Override
    public void close() throws StandardException, IOException {
        super.close();
        if (joiner != null) joiner.close();
    }

    @Override
    public boolean providesRDD() {
        return leftResultSet.providesRDD() && rightResultSet.providesRDD();
    }

    @Override
    public JavaRDD<ExecRow> getRDD(SpliceRuntimeContext spliceRuntimeContext, SpliceOperation top) throws StandardException {


        JavaPairRDD<ExecRow, ExecRow> leftRDD = RDDUtils.getKeyedRDD(leftResultSet.getRDD(spliceRuntimeContext, leftResultSet), leftHashKeys);
        // right is the one we are just reading, we have TableSplits for it.
        JavaRDD<ExecRow> rightRDD = rightResultSet.getRDD(spliceRuntimeContext, rightResultSet);

        Partition[] partitions = rightRDD.rdd().partitions();
        List<byte[]> splits = new ArrayList<>();
        for (Partition p : partitions) {
            assert p instanceof NewHadoopPartition;
            NewHadoopPartition nhp = (NewHadoopPartition) p;
            InputSplit is = nhp.serializableHadoopSplit().value();
            assert is instanceof SpliceSplit;
            SpliceSplit ss = (SpliceSplit) is;
            splits.add(ss.getSplit().getEndRow());
        }
        Collections.sort(splits, BytesUtil.endComparator);

        int[] formatIds = SpliceUtils.getFormatIds(RDDUtils.getKey(this.rightResultSet.getExecRowDefinition(), this.rightHashKeys).getRowArray());
        Partitioner partitioner = new CustomPartitioner(splits, formatIds);

        final SpliceObserverInstructions soi = SpliceObserverInstructions.create(activation, this, spliceRuntimeContext);
        return leftRDD.partitionBy(partitioner).zipPartitions(rightRDD, new SparkJoiner(this, soi, true));
    }

    @Override
    public boolean pushedToServer() {
        return leftResultSet.pushedToServer() && rightResultSet.pushedToServer();
    }

    private static class CustomPartitioner extends Partitioner {
        List<byte[]> splits;
        int[] formatIds;
        private transient ThreadLocal<DataHash> encoder = new ThreadLocal<DataHash>() {
            @Override
            protected DataHash initialValue() {
                int[] rowColumns = IntArrays.count(formatIds.length);
                DescriptorSerializer[] serializers = VersionedSerializers.latestVersion(false).getSerializers(formatIds);
                return BareKeyHash.encoder(rowColumns, null, serializers);
            }
        };

        public CustomPartitioner(List<byte[]> splits, int[] formatIds) {
            this.splits = splits;
            this.formatIds = formatIds;
        }

        @Override
        public int numPartitions() {
            return splits.size();
        }

        @Override
        public int getPartition(Object key) {
            ExecRow row = (ExecRow) key;
            DataHash enc = encoder.get();
            enc.setRow(row);
            byte[] result;
            try {
                result = enc.encode();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (int i = 0; i<splits.size(); ++i) {
                if (BytesUtil.endComparator.compare(result, splits.get(i)) < 0) {
                    return i;
                }
            }
            return 0;
        }
    }


    private static final class SparkJoiner extends SparkFlatMap2Operation<MergeJoinOperation, Iterator<Tuple2<ExecRow, ExecRow>>, Iterator<ExecRow>, ExecRow> {
        boolean outer;
        private Joiner joiner;

        public SparkJoiner() {
        }

        public SparkJoiner(MergeJoinOperation spliceOperation, SpliceObserverInstructions soi, boolean outer) {
            super(spliceOperation, soi);
            this.outer = outer;
        }

        private Joiner initJoiner(final Iterator<Tuple2<ExecRow, ExecRow>> left, Iterator<ExecRow> right) throws StandardException {
            StandardIterator<ExecRow> leftIterator = new StandardIterator<ExecRow>() {
                @Override
                public void open() throws StandardException, IOException {
                    // no-op
                }

                @Override
                public ExecRow next(SpliceRuntimeContext spliceRuntimeContext) throws StandardException, IOException {
                    if (!left.hasNext())
                        return null;
                    return left.next()._2();
                }

                @Override
                public void close() throws StandardException, IOException {
                    // no-op
                }
            };
            IJoinRowsIterator<ExecRow> mergedRowSource = new MergeJoinRows(leftIterator, StandardIterators.wrap(right), op.leftHashKeys, op.rightHashKeys);
            StandardSupplier<ExecRow> emptyRowSupplier = new StandardSupplier<ExecRow>() {
                @Override
                public ExecRow get() throws StandardException {
                    return op.getEmptyRow();
                }
            };

            return new Joiner(mergedRowSource, op.getExecRowDefinition(), op.getRestriction(),
                    op.isOuterJoin, op.wasRightOuterJoin, op.leftNumCols, op.rightNumCols,
                    op.oneRowRightSide, op.notExistsRightSide, emptyRowSupplier, soi.getSpliceRuntimeContext());
        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            super.writeExternal(out);
            out.writeBoolean(outer);
        }

        @Override
        public void readExternalInContext(ObjectInput in) throws IOException, ClassNotFoundException {
            outer = in.readBoolean();
        }

        @Override
        public Iterable<ExecRow> call(Iterator<Tuple2<ExecRow, ExecRow>> left, Iterator<ExecRow> right) throws Exception {
            if (joiner == null) {
                joiner = initJoiner(left, right);
                joiner.open();
            }
            return new JoinerIterator();
        }

        private class JoinerIterator implements Iterator<ExecRow>, Iterable<ExecRow> {
            ExecRow next = null;
            boolean consumed = false;

            @Override
            public Iterator<ExecRow> iterator() {
                return this;
            }

            @Override
            public boolean hasNext() {
                if (consumed) return false;
                try {
                    if (next == null) {
                        next = joiner.nextRow(soi.getSpliceRuntimeContext());
                    }
                    if (next == null) {
                        consumed = true;
                        joiner.close();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return next != null;
            }

            @Override
            public ExecRow next() {
                if (!hasNext())
                    return null;
                ExecRow result = next.getClone();
                next = null;
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Can't remove elements from this iterator");
            }
        }
    }
}
