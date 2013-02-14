package com.splicemachine.si.utils;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import com.splicemachine.constants.HBaseConstants;
import com.splicemachine.constants.SchemaConstants;
import com.splicemachine.constants.TxnConstants.TableEnv;
import com.splicemachine.constants.environment.EnvUtils;
import com.splicemachine.utils.SpliceLogUtils;

public class SIUtils extends SIConstants {	
	private static Logger LOG = Logger.getLogger(SIUtils.class);
	protected static HTablePool pool = new HTablePool();
	
	public static HTableDescriptor generateSIHtableDescriptor(String tableName) {
		HTableDescriptor descriptor = new HTableDescriptor(tableName);
		HColumnDescriptor snapShot = new HColumnDescriptor(SNAPSHOT_ISOLATION_FAMILY_BYTES,
				Integer.MAX_VALUE,
				HBaseConstants.DEFAULT_COMPRESSION,
				HBaseConstants.DEFAULT_IN_MEMORY,
				true,
				HBaseConstants.DEFAULT_TTL,
				HBaseConstants.DEFAULT_BLOOMFILTER);
		HColumnDescriptor attributes = new HColumnDescriptor(HBaseConstants.DEFAULT_FAMILY.getBytes(),
				Integer.MAX_VALUE,
				HBaseConstants.DEFAULT_COMPRESSION,
				HBaseConstants.DEFAULT_IN_MEMORY,
				HBaseConstants.DEFAULT_BLOCKCACHE,
				HBaseConstants.DEFAULT_TTL,
				HBaseConstants.DEFAULT_BLOOMFILTER);
		descriptor.addFamily(snapShot);		
		descriptor.addFamily(attributes);
		return descriptor;
	}
	
	public static long createIncreasingTimestamp(String transactionPath, RecoverableZooKeeper rzk) {
		SpliceLogUtils.trace(LOG,"Begin transaction at server and create znode for %s",transactionPath);
		String id = null;
		try {
			id = rzk.create(transactionPath + "/txn-", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
			SpliceLogUtils.debug(LOG,"Begin transaction at server and create znode for transId="+id);
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return Long.parseLong(id.substring(id.length()-10, id.length()));
	}
	
	
	public static void createWithParents(RecoverableZooKeeper rzk, String znode)
			throws KeeperException {
		try {
			if(znode == null) {
				return;
			}
			rzk.create(znode, new byte[0], Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT);
		} catch(KeeperException.NodeExistsException nee) {
			SpliceLogUtils.debug(LOG,"znode exists during createWithParents: "+znode);
			return;
		} catch(KeeperException.NoNodeException nne) {
			createWithParents(rzk, getParent(znode));
			createWithParents(rzk, znode);
		} catch(InterruptedException ie) {
			SpliceLogUtils.error(LOG,ie);
		}
	}

	/**
	 * Returns the full path of the immediate parent of the specified node.
	 * @param node path to get parent of
	 * @return parent of path, null if passed the root node or an invalid node
	 */
	public static String getParent(String node) {
		int idx = node.lastIndexOf(SchemaConstants.PATH_DELIMITER);
		return idx <= 0 ? null : node.substring(0, idx);
	}
	
	public static HTableInterface pushTransactionTable() {
		SpliceLogUtils.trace(LOG,"pushTransactionTable");
		return pool.getTable(TRANSACTION_TABLE_BYTES);
	}
	public static TableEnv getTableEnv(RegionCoprocessorEnvironment e) {
		return EnvUtils.getTableEnv(e.getRegion().getTableDesc().getNameAsString());
	}
}
