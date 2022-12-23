/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.common.engine.impl.interceptor;

import java.lang.reflect.UndeclaredThrowableException;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.flowable.common.engine.impl.cfg.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Guillaume Nodet
 */
public class JtaTransactionInterceptor extends AbstractCommandInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(JtaTransactionInterceptor.class);

    private final TransactionManager transactionManager;

    public JtaTransactionInterceptor(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public <T> T execute(CommandConfig config, Command<T> command, CommandExecutor commandExecutor) {
        LOGGER.debug("Running command with propagation {}", config.getTransactionPropagation());

        if (config.getTransactionPropagation() == TransactionPropagation.NOT_SUPPORTED) {
            return next.execute(config, command, commandExecutor);
        }

        boolean requiresNew = config.getTransactionPropagation() == TransactionPropagation.REQUIRES_NEW;
        Transaction oldTx = null;
        try {
            boolean existing = isExisting();
            boolean isNew = !existing || requiresNew;
            if (existing && requiresNew) {
                oldTx = doSuspend();
            }
            if (isNew) {
                doBegin();
            }
            T result;
            try {
                result = next.execute(config, command, commandExecutor);
            } catch (RuntimeException | Error ex) {
                doRollback(isNew, ex);
                throw ex;
            } catch (Exception ex) {
                doRollback(isNew, ex);
                throw new UndeclaredThrowableException(ex, "TransactionCallback threw undeclared checked exception");
            }
            if (isNew) {
                doCommit();
            }
            return result;
        } finally {
            doResume(oldTx);
        }
    }

    private void doBegin() {
        try {
            transactionManager.begin();
        } catch (NotSupportedException | SystemException e) {
            throw new TransactionException("Unable to begin transaction", e);
        }
    }

    private boolean isExisting() {
        try {
            return transactionManager.getStatus() != Status.STATUS_NO_TRANSACTION;
        } catch (SystemException e) {
            throw new TransactionException("Unable to retrieve transaction status", e);
        }
    }

    private Transaction doSuspend() {
        try {
            return transactionManager.suspend();
        } catch (SystemException e) {
            throw new TransactionException("Unable to suspend transaction", e);
        }
    }

    private void doResume(Transaction tx) {
        if (tx != null) {
            try {
                transactionManager.resume(tx);
            } catch (SystemException | InvalidTransactionException e) {
                throw new TransactionException("Unable to resume transaction", e);
            }
        }
    }

    private void doCommit() {
        try {
            transactionManager.commit();
        } catch (HeuristicMixedException | SystemException | RollbackException | HeuristicRollbackException e) {
            throw new TransactionException("Unable to commit transaction", e);
        } catch (RuntimeException | Error e) {
            doRollback(true, e);
            throw e;
        }
    }

    private void doRollback(boolean isNew, Throwable originalException) {
        Throwable rollbackEx = null;
        try {
            if (isNew) {
                transactionManager.rollback();
            } else {
                transactionManager.setRollbackOnly();
            }
        } catch (SystemException e) {
            LOGGER.debug("Error when rolling back transaction", e);
        } catch (RuntimeException | Error e) {
            rollbackEx = e;
            throw e;
        } finally {
            if (rollbackEx != null && originalException != null) {
                LOGGER.error("Error when rolling back transaction, original exception was:", originalException);
            }
        }
    }

    private static class TransactionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private TransactionException() {
        }

        private TransactionException(String s) {
            super(s);
        }

        private TransactionException(String s, Throwable throwable) {
            super(s, throwable);
        }

        private TransactionException(Throwable throwable) {
            super(throwable);
        }
    }
}
