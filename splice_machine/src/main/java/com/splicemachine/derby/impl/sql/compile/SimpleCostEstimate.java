package com.splicemachine.derby.impl.sql.compile;

import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.compile.RowOrdering;

/**
 * @author Scott Fines
 *         Date: 3/13/15
 */
public class SimpleCostEstimate implements CostEstimate{
    private double localCost = Double.MAX_VALUE;
    private double remoteCost;
    private int numPartitions;
    private double numRows = Double.MAX_VALUE;
    private double singleScanRowCount = Double.MAX_VALUE;
    private RowOrdering rowOrdering;

    private CostEstimate baseCost;

    public SimpleCostEstimate(){ }

    public SimpleCostEstimate(double theCost,double theRowCount,double theSingleScanRowCount){
        this(theCost,0d,theRowCount,theSingleScanRowCount,1);
    }

    public SimpleCostEstimate(double localCost,double remoteCost,double numRows,double singleScanRowCount,int numPartitions){
        this.localCost=localCost;
        this.remoteCost=remoteCost;
        this.numRows=numRows;
        this.singleScanRowCount=singleScanRowCount;
        this.numPartitions=numPartitions;
    }

    public SimpleCostEstimate(double localCost,
                              double remoteCost,
                              double numRows,
                              double singleScanRowCount,
                              int numPartitions,
                              CostEstimate base){
        this(localCost, remoteCost, numRows, singleScanRowCount, numPartitions);
        this.baseCost = base;
    }


    @Override
    public void setCost(double cost,double rowCount,double singleScanRowCount){
        setCost(cost,rowCount,singleScanRowCount,1);
    }

    @Override
    public void setCost(double cost,double rowCount,double singleScanRowCount,int numPartitions){
        this.localCost = cost;
        this.numRows = rowCount;
        this.singleScanRowCount = singleScanRowCount;
        this.numPartitions = numPartitions;
    }

    @Override
    public void setCost(CostEstimate other){
        this.localCost = other.localCost();
        this.remoteCost = other.remoteCost();
        this.numPartitions =other.partitionCount();
        this.numRows = other.rowCount();
        this.singleScanRowCount = other.singleScanRowCount();

        CostEstimate base=other.getBase();
        if(base!=null)
            this.baseCost = base.cloneMe();
    }

    @Override public void setRemoteCost(double remoteCost){ this.remoteCost = remoteCost; }
    @Override public void setSingleScanRowCount(double singleRowScanCount){this.singleScanRowCount = singleRowScanCount;}
    @Override public void setNumPartitions(int numPartitions){ this.numPartitions = numPartitions; }
    @Override public double rowCount(){ return numRows; }
    @Override public double singleScanRowCount(){ return singleScanRowCount; }
    @Override public int partitionCount(){ return numPartitions; }
    @Override public double remoteCost(){ return remoteCost; }
    @Override public double localCost(){ return localCost; }
    @Override public RowOrdering getRowOrdering(){ return rowOrdering; }
    @Override public void setRowOrdering(RowOrdering rowOrdering){ this.rowOrdering = rowOrdering; }
    @Override public CostEstimate getBase(){ return baseCost==null?this:baseCost; }
    @Override public void setBase(CostEstimate baseCost){ this.baseCost = baseCost; }
    @Override public long getEstimatedRowCount(){ return (long)numRows; }
    @Override public void setEstimatedCost(double cost){ this.localCost = cost; }
    @Override public void setLocalCost(double remoteCost){ this.localCost = remoteCost; }

    @Override
    public void setEstimatedRowCount(long count){
        this.numRows = count;
        this.singleScanRowCount = count;
    }

    @Override
    public double getEstimatedCost(){
        return localCost + remoteCost;
    }

    @Override
    public CostEstimate cloneMe(){
        RowOrdering roClone = new SpliceRowOrderingImpl();
        if(this.rowOrdering!=null)
           this.rowOrdering.copy(roClone);
        SimpleCostEstimate clone=new SimpleCostEstimate(localCost,remoteCost,numRows,singleScanRowCount,numPartitions);
        clone.setRowOrdering(rowOrdering);
        return clone;
    }

    @Override
    public boolean isUninitialized(){
        return localCost == Double.MAX_VALUE &&
                numRows == Double.MAX_VALUE &&
                singleScanRowCount == Double.MAX_VALUE;
    }

    @Override
    public double compare(CostEstimate other){
        assert other!=null: "Cannot compare with a null CostEstimate";

        double thisCost=this.getEstimatedCost();
        double otherCost=other.getEstimatedCost();
        if((thisCost!=Double.POSITIVE_INFINITY) || (otherCost!= Double.POSITIVE_INFINITY)){
            return thisCost-otherCost;
        }

        if((this.numRows !=Double.POSITIVE_INFINITY)|| (other.rowCount()!=Double.POSITIVE_INFINITY)){
            return this.numRows-other.rowCount();
        }

        if((this.singleScanRowCount != Double.POSITIVE_INFINITY)||
                (other.singleScanRowCount() != Double.POSITIVE_INFINITY)){
            return this.singleScanRowCount - other.singleScanRowCount();
        }
        return 0.0d;
    }

    @Override
    public CostEstimate add(CostEstimate addend,CostEstimate retval){
        assert addend!=null: "Cannot add a null cost estimate";

        double sumLocalCost = addend.localCost()+localCost;
        double sumRemoteCost = addend.remoteCost()+remoteCost;
        double rowCount = addend.rowCount()+numRows;

        if(retval==null)
            retval = new SimpleCostEstimate();

        retval.setRemoteCost(sumRemoteCost);
        retval.setCost(sumLocalCost,rowCount,singleScanRowCount,numPartitions);
        return retval;
    }

    @Override
    public CostEstimate multiply(double multiplicand,CostEstimate retval){

        double multLocalCost = localCost*multiplicand;
        double multRemoteCost = remoteCost*multiplicand;
        double rowCount = numRows*multiplicand;

        if(retval==null)
            retval = new SimpleCostEstimate();

        retval.setRemoteCost(multRemoteCost);
        retval.setCost(multLocalCost,rowCount,singleScanRowCount,numPartitions);
        return retval;
    }

    @Override
    public CostEstimate divide(double divisor,CostEstimate retval){
        double multLocalCost = localCost/divisor;
        double multRemoteCost = remoteCost/divisor;
        double rowCount = numRows/divisor;

        if(retval==null)
            retval = new SimpleCostEstimate();

        retval.setRemoteCost(multRemoteCost);
        retval.setCost(multLocalCost,rowCount,singleScanRowCount,numPartitions);
        return retval;
    }

}
