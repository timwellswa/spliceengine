/*
 * Copyright (c) 2012 - 2017 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.storage;

import com.splicemachine.access.hbase.HBaseTableInfoFactory;
import org.apache.hadoop.hbase.TableName;

/**
 * @author Scott Fines
 *         Date: 12/29/15
 */
public class H98PartitionInfoCache implements PartitionInfoCache{

    public void invalidate(TableName tableName){
        throw new UnsupportedOperationException("IMPLEMENT");
    }

    @Override
    public void invalidate(String tableName){
        invalidate(HBaseTableInfoFactory.getInstance().getTableInfo(tableName));
    }

    @Override
    public void invalidate(byte[] tableNameBytes){
        invalidate(HBaseTableInfoFactory.getInstance().getTableInfo(tableNameBytes));
    }
}