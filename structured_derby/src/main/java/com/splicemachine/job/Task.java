package com.splicemachine.job;

import com.splicemachine.derby.impl.job.coprocessor.TaskStatus;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * @author Scott Fines
 *         Created on: 4/3/13
 */
public interface Task {

    void markStarted() throws ExecutionException, CancellationException;

    void markCompleted() throws ExecutionException;

    void markFailed(Throwable error) throws ExecutionException;

    void markCancelled() throws ExecutionException;

    void execute() throws ExecutionException,InterruptedException;

    boolean isCancelled() throws ExecutionException;

    String getTaskId();

    TaskStatus getTaskStatus();
}
