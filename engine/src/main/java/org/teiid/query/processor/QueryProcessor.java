/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.processor;

import java.util.Arrays;
import java.util.List;

import org.teiid.common.buffer.BlockedException;
import org.teiid.common.buffer.BufferManager;
import org.teiid.common.buffer.BufferManager.BufferReserveMode;
import org.teiid.common.buffer.TupleBatch;
import org.teiid.common.buffer.TupleBuffer;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.util.Assertion;
import org.teiid.dqp.internal.process.PreparedPlan;
import org.teiid.dqp.internal.process.RequestWorkItem;
import org.teiid.dqp.internal.process.TupleSourceCache;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.logging.MessageLevel;
import org.teiid.query.QueryPlugin;
import org.teiid.query.metadata.QueryMetadataInterface;
import org.teiid.query.processor.BatchCollector.BatchProducer;
import org.teiid.query.util.CommandContext;

/**
 * Driver for plan processing.
 */
public class QueryProcessor implements BatchProducer {

	public static class ExpiredTimeSliceException extends BlockedException {
		private static final long serialVersionUID = 4585044674826578060L;
	}
	
	private static ExpiredTimeSliceException EXPIRED_TIME_SLICE = new ExpiredTimeSliceException();
	
	public interface ProcessorFactory {
		QueryProcessor createQueryProcessor(String query, String recursionGroup, CommandContext commandContext, Object... params) throws TeiidProcessingException, TeiidComponentException;
		PreparedPlan getPreparedPlan(String query, String recursionGroup,
				CommandContext commandContext, QueryMetadataInterface metadata)
				throws TeiidProcessingException, TeiidComponentException;
	}
	
    private CommandContext context;
	private ProcessorDataManager dataMgr;
	private BufferManager bufferMgr;
	private ProcessorPlan processPlan;
    private boolean initialized;
    private boolean open;
    private int reserved;
    /** Flag that marks whether the request has been canceled. */
    private volatile boolean requestCanceled;
    private static final int DEFAULT_WAIT = 50;       
    private boolean processorClosed;
    
    private boolean continuous;
    private String query; //used only in continuous mode
    private PreparedPlan plan;
    private int rowOffset = 1;
    
    /**
     * Construct a processor with all necessary information to process.
     * @param plan The plan to process
     * @param context The context that this plan is being processed in
     * @param bufferMgr The buffer manager that provides access to tuple sources
     * @param dataMgr The data manager that provides access to get data
     * @throws TeiidComponentException 
     */
    public QueryProcessor(ProcessorPlan plan, CommandContext context, BufferManager bufferMgr, final ProcessorDataManager dataMgr) {
        this.context = context;
        this.context.setTupleSourceCache(new TupleSourceCache());
        this.dataMgr = dataMgr;
		this.processPlan = plan;
		this.bufferMgr = bufferMgr;
    }
    
    public CommandContext getContext() {
		return context;
	}
    
    public ProcessorPlan getProcessorPlan() {
        return this.processPlan;
    }

	public TupleBatch nextBatch()
		throws BlockedException, TeiidProcessingException, TeiidComponentException {
	    while (true) {
	    	long wait = DEFAULT_WAIT;
	    	try {
	    		return nextBatchDirect();
	    	} catch (BlockedException e) {
	    		if (!this.context.isNonBlocking()) {
	    			throw e;
	    		}
		    	if (e == BlockedException.BLOCKED_ON_MEMORY_EXCEPTION) {
		    		continue; //TODO: pass the commandcontext into sortutility
		    	}
	    	}
    		try {
                Thread.sleep(wait);
            } catch (InterruptedException err) {
                 throw new TeiidComponentException(QueryPlugin.Event.TEIID30159, err);
            }
	    }
	}
	
	private TupleBatch nextBatchDirect()
		throws BlockedException, TeiidProcessingException, TeiidComponentException {
		
	    boolean done = false;
	    TupleBatch result = null;
		
	    try {
	    	init(); 
			long currentTime = System.currentTimeMillis();
			Assertion.assertTrue(!processorClosed);
			
			//TODO: see if there is pending work before preempting
			
	        while(currentTime < context.getTimeSliceEnd() || context.isNonBlocking()) {
	        	if (requestCanceled) {
	                 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30160, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30160, this.context.getRequestId()));
	            }
	        	if (currentTime > context.getTimeoutEnd()) {
	        		 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30161, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30161));
	        	}
	            result = processPlan.nextBatch();

	            if (continuous) {
	        		result.setRowOffset(rowOffset);
	        		
	        		if (result.getTerminationFlag()) {
	        			result.setTermination(TupleBatch.ITERATION_TERMINATED);
	        			List<Object> terminationTuple = Arrays.asList(new Object[this.getOutputElements().size()]);
	        			result.getTuples().add(terminationTuple);
	        			this.context.getTupleSourceCache().close();
		        		this.processPlan.close();
		        		this.processPlan.reset();
		        		this.context.incrementReuseCount();
		        		this.open = false;	
	        		}
	        		
	        		rowOffset = result.getEndRow() + 1;
	            }
        		
	        	if(result.getTermination() != TupleBatch.NOT_TERMINATED) {
	        		if (result.getTerminationFlag()) {
	        			done = true;
	        		}
	        		break;
	        	}
	        	
	        	if (result.getRowCount() > 0) {
	        		break;
	        	}
	        	
	        }
	    } catch (BlockedException e) {
	    	throw e;
	    } catch (TeiidException e) {
    		closeProcessing();
	    	if (e instanceof TeiidProcessingException) {
	    		throw (TeiidProcessingException)e;
	    	}
	    	if (e instanceof TeiidComponentException) {
	    		throw (TeiidComponentException)e;
	    	}
	    	 throw new TeiidComponentException(QueryPlugin.Event.TEIID30162, e);
	    }
		if(done) {
			closeProcessing();
		} 
	    if (result == null) {
	    	RequestWorkItem workItem = this.getContext().getWorkItem();
	    	if (workItem != null) {
	    		//if we have a workitem (non-test scenario) then before 
	    		//throwing exprired time slice we need to indicate there's more work
	    		workItem.moreWork();
	    	}
	    	throw EXPIRED_TIME_SLICE;
	    }
		return result;
	}

	public void init() throws TeiidComponentException, TeiidProcessingException {
		if (!open) {
			if (continuous && context.getReuseCount() > 0) {
				//validate the plan prior to the next run
	    		//ideally we would reuse the prepared plan from initial planning in the case of a prepared statement
	    		if (this.plan != null && !this.plan.validate()) {
					this.plan = null;
	    		}
	    		if (this.plan == null) {
	    			this.plan = context.getQueryProcessorFactory().getPreparedPlan(query, null, context, context.getMetadata());
	    			this.processPlan = this.plan.getPlan().clone();
	    			this.processPlan.initialize(context, dataMgr, bufferMgr);
	    		}
			}
    		
			// initialize if necessary
			if(!initialized) {
				reserved = this.bufferMgr.reserveBuffers(this.bufferMgr.getSchemaSize(this.getOutputElements()), BufferReserveMode.FORCE);
				this.processPlan.initialize(context, dataMgr, bufferMgr);
				initialized = true;
			}
		
			// Open the top node for reading
			processPlan.open();
			open = true;
		}
	}

    /**
     * Close processing and clean everything up.  Should only be called by the same thread that called process.
     */
    public void closeProcessing() {
    	if (processorClosed) {
    		return;
    	}
    	if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
    		LogManager.logDetail(LogConstants.CTX_DQP, "QueryProcessor: closing processor"); //$NON-NLS-1$
    	}
		this.context.getTupleSourceCache().close();
		this.bufferMgr.releaseBuffers(reserved);
		reserved = 0;
        processorClosed = true;
        if (initialized) {
	        try {
	        	processPlan.close();
			} catch (TeiidComponentException e1){
				LogManager.logDetail(LogConstants.CTX_DQP, e1, "Error closing processor"); //$NON-NLS-1$
			}
        }
    }

    @Override
    public List getOutputElements() {
    	return this.processPlan.getOutputElements();
    }

    public List<Exception> getAndClearWarnings() {
        return this.context.getAndClearWarnings();
    }
    
    /** 
     * Asynch shutdown of the QueryProcessor, which may trigger exceptions in the processing thread
     */
    public void requestCanceled() {
        this.requestCanceled = true;
    }
    
	public BatchCollector createBatchCollector() throws TeiidComponentException {
		return new BatchCollector(this, this.bufferMgr, this.context, false);
	}
	
	public void setNonBlocking(boolean nonBlocking) {
		this.context.setNonBlocking(nonBlocking);
	}

	@Override
	public TupleBuffer getFinalBuffer(int maxRows) throws BlockedException, TeiidComponentException, TeiidProcessingException {
		while (true) {
	    	long wait = DEFAULT_WAIT;
	    	try {
	    		init();
	    		return this.processPlan.getFinalBuffer(maxRows);
	    	} catch (BlockedException e) {
	    		if (!this.context.isNonBlocking()) {
	    			throw e;
	    		}
		    	if (e == BlockedException.BLOCKED_ON_MEMORY_EXCEPTION) {
		    		continue; //TODO: pass the commandcontext into sortutility
		    	}
	    	} catch (TeiidComponentException e) {
	    		closeProcessing();
	    		throw e;
	    	} catch (TeiidProcessingException e) {
	    		closeProcessing();
	    		throw e;
	    	}
    		try {
                Thread.sleep(wait);
            } catch (InterruptedException err) {
                 throw new TeiidComponentException(QueryPlugin.Event.TEIID30163, err);
            }
	    }
	}

	@Override
	public boolean hasFinalBuffer() {
		return !continuous && this.processPlan.hasFinalBuffer();
	}
	
	public BufferManager getBufferManager() {
		return bufferMgr;
	}
	
	public void setContinuous(boolean continuous, String sql) {
		this.continuous = continuous;
		if (this.continuous) {
			this.query = sql;
			this.context.setContinuous();
		}
	}
	
	@Override
	public void close() throws TeiidComponentException {
		closeProcessing();
	}

}
