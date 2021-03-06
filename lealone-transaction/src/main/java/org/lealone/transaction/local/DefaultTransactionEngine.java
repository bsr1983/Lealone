/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.transaction.local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.engine.Session;
import org.lealone.engine.TransactionEngine;
import org.lealone.mvstore.DataUtils;
import org.lealone.mvstore.MVMap;
import org.lealone.mvstore.MVStore;
import org.lealone.mvstore.type.DataType;
import org.lealone.mvstore.type.ObjectDataType;
import org.lealone.transaction.TransactionManager;
import org.lealone.transaction.TransactionStatusTable;
import org.lealone.transaction.TransactionValidator;
import org.lealone.util.New;

/**
 * The default transaction engine that supports concurrent MVCC read-committed transactions.
 */
public class DefaultTransactionEngine implements TransactionEngine {

    /**
     * The store.
     */
    public final MVStore store;

    /**
     * The persisted map of prepared transactions.
     * Key: transactionId, value: [ status, name ].
     */
    final MVMap<Integer, Object[]> preparedTransactions;

    /**
     * The undo log.
     * <p>
     * If the first entry for a transaction doesn't have a logId
     * of 0, then the transaction is partially committed (which means rollback
     * is not possible). Log entries are written before the data is changed
     * (write-ahead).
     * <p>
     * Key: opId, value: [ mapId, key, oldValue ].
     */
    final MVMap<Long, Object[]> undoLog;

    /**
     * The map of maps.
     */
    private final HashMap<Integer, MVMap<Object, VersionedValue>> maps = New.hashMap();

    private final DataType dataType;

    private boolean init;

    private final AtomicInteger lastTransactionId = new AtomicInteger();

    private int maxTransactionId = 0xffff;

    /**
     * The next id of a temporary map.
     */
    private int nextTempMapId;

    /**
     * Create a new transaction engine.
     *
     * @param store the store
     */
    public DefaultTransactionEngine(MVStore store) {
        this(store, new ObjectDataType());
    }

    /**
     * Create a new transaction engine.
     *
     * @param store the store
     * @param dataType the data type for map keys and values
     */
    public DefaultTransactionEngine(MVStore store, DataType dataType) {
        this.store = store;
        this.dataType = dataType;
        preparedTransactions = store.openMap("openTransactions", new MVMap.Builder<Integer, Object[]>());

        VersionedValueType oldValueType = new VersionedValueType(dataType);
        ArrayType undoLogValueType = new ArrayType(new DataType[] { new ObjectDataType(), dataType, oldValueType });
        MVMap.Builder<Long, Object[]> builder = new MVMap.Builder<Long, Object[]>().valueType(undoLogValueType);
        undoLog = store.openMap("undoLog", builder);
        if (undoLog.getValueType() != undoLogValueType) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_CORRUPT,
                    "Undo map open with a different value type");
        }
    }

    /**
     * Initialize the store. This is needed before a transaction can be opened.
     * If the transaction store is corrupt, this method can throw an exception,
     * in which case the store can only be used for reading.
     */
    public synchronized void init() {
        init = true;

        // remove all temporary maps
        for (String mapName : store.getMapNames()) {
            if (mapName.startsWith("temp.")) {
                MVMap<Object, Integer> temp = openTempMap(mapName);
                store.removeMap(temp);
            }
        }
        synchronized (undoLog) {
            if (undoLog.size() > 0) {
                Long key = undoLog.firstKey();
                lastTransactionId.set(getTransactionId(key));
            }
        }
    }

    /**
     * Set the maximum transaction id, after which ids are re-used. If the old
     * transaction is still in use when re-using an old id, the new transaction
     * fails.
     *
     * @param max the maximum id
     */
    public void setMaxTransactionId(int max) {
        this.maxTransactionId = max;
    }

    /**
     * Combine the transaction id and the log id to an operation id.
     *
     * @param transactionId the transaction id
     * @param logId the log id
     * @return the operation id
     */
    static long getOperationId(int transactionId, long logId) {
        DataUtils.checkArgument(transactionId >= 0 && transactionId < (1 << 24), "Transaction id out of range: {0}",
                transactionId);
        DataUtils.checkArgument(logId >= 0 && logId < (1L << 40), "Transaction log id out of range: {0}", logId);
        return ((long) transactionId << 40) | logId;
    }

    /**
     * Get the transaction id for the given operation id.
     *
     * @param operationId the operation id
     * @return the transaction id
     */
    static int getTransactionId(long operationId) {
        return (int) (operationId >>> 40);
    }

    /**
     * Get the log id for the given operation id.
     *
     * @param operationId the operation id
     * @return the log id
     */
    static long getLogId(long operationId) {
        return operationId & ((1L << 40) - 1);
    }

    /**
     * Get the list of unclosed transactions that have pending writes.
     *
     * @return the list of transactions (sorted by id)
     */
    public List<LocalTransaction> getOpenTransactions(Session session) {
        synchronized (undoLog) {
            ArrayList<LocalTransaction> list = New.arrayList();
            Long key = undoLog.firstKey();
            while (key != null) {
                int transactionId = getTransactionId(key);
                key = undoLog.lowerKey(getOperationId(transactionId + 1, 0));
                long logId = getLogId(key) + 1;
                Object[] data = preparedTransactions.get(transactionId);
                int status;
                String name;
                if (data == null) {
                    if (undoLog.containsKey(getOperationId(transactionId, 0))) {
                        status = LocalTransaction.STATUS_OPEN;
                    } else {
                        status = LocalTransaction.STATUS_COMMITTING;
                    }
                    name = null;
                } else {
                    status = (Integer) data[0];
                    name = (String) data[1];
                }
                LocalTransaction t = new LocalTransaction(session, this, transactionId, status, name, logId);
                list.add(t);
                key = undoLog.ceilingKey(getOperationId(transactionId + 1, 0));
            }
            return list;
        }
    }

    /**
     * Close the transaction store.
     */
    public synchronized void close() {
        store.commit();
    }

    private int nextTransactionId(Session session) {
        //分布式事务使用奇数的事务ID
        if (!session.isAutoCommit() && Session.isClusterMode()) {
            return nextOddTransactionId();
        }

        return nextEvenTransactionId();
    }

    private int nextOddTransactionId() {
        int oldLast;
        int last;
        int delta;
        do {
            oldLast = lastTransactionId.get();
            last = oldLast;
            if (last % 2 == 0)
                delta = 1;
            else
                delta = 2;

            last += delta;

            if (last >= maxTransactionId)
                last = 1;
        } while (!lastTransactionId.compareAndSet(oldLast, last));
        return last;
    }

    private int nextEvenTransactionId() {
        int oldLast;
        int last;
        int delta;
        do {
            oldLast = lastTransactionId.get();
            last = oldLast;
            if (last % 2 == 0)
                delta = 2;
            else
                delta = 1;

            last += delta;

            if (last >= maxTransactionId)
                last = 2;
        } while (!lastTransactionId.compareAndSet(oldLast, last));
        return last;
    }

    /**
     * Begin a new transaction.
     *
     * @return the transaction
     */
    @Override
    public LocalTransaction beginTransaction(Session session) {
        if (!init) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE, "Not initialized");
        }
        return new LocalTransaction(session, this, nextTransactionId(session), LocalTransaction.STATUS_OPEN, null, 0);
    }

    /**
     * Store a transaction.
     *
     * @param t the transaction
     */
    synchronized void storeTransaction(LocalTransaction t) {
        if (t.getStatus() == LocalTransaction.STATUS_PREPARED || t.getName() != null) {
            Object[] v = { t.getStatus(), t.getName() };
            preparedTransactions.put(t.getId(), v);
        }
    }

    /**
     * Log an entry.
     *
     * @param t the transaction
     * @param logId the log id
     * @param mapId the map id
     * @param key the key
     * @param oldValue the old value
     */
    void log(LocalTransaction t, long logId, int mapId, Object key, Object oldValue) {
        Long undoKey = getOperationId(t.getId(), logId);
        Object[] log = new Object[] { mapId, key, oldValue };
        synchronized (undoLog) {
            if (logId == 0) {
                if (undoLog.containsKey(undoKey)) {
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_STILL_OPEN,
                            "An old transaction with the same id " + "is still open: {0}", t.getId());
                }
            }
            undoLog.put(undoKey, log);
        }
    }

    /**
     * Remove a log entry.
     *
     * @param t the transaction
     * @param logId the log id
     */
    public void logUndo(LocalTransaction t, long logId) {
        Long undoKey = getOperationId(t.getId(), logId);
        synchronized (undoLog) {
            Object[] old = undoLog.remove(undoKey);
            if (old == null) {
                throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE,
                        "Transaction {0} was concurrently rolled back", t.getId());
            }
        }
    }

    /**
     * Remove the given map.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param map the map
     */
    public synchronized <K, V> void removeMap(TransactionMap<K, V> map) {
        maps.remove(map.mapId);
        store.removeMap(map.map);
    }

    /**
     * Commit a transaction.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     */
    void commit(LocalTransaction t, long maxLogId) {
        if (store.isClosed()) {
            return;
        }

        //分布式事务推迟删除undoLog
        if (t.transactionId % 2 == 0) {
            removeUndoLog(t.getId(), maxLogId);
        }

        endTransaction(t);
    }

    public void commitAfterValidate(int tid) {
        if (store.isClosed()) {
            return;
        }

        removeUndoLog(tid, Long.MAX_VALUE);
    }

    private void removeUndoLog(int tid, long maxLogId) {
        // TODO could synchronize on blocks (100 at a time or so)
        synchronized (undoLog) {
            for (long logId = 0; logId < maxLogId; logId++) {
                Long undoKey = getOperationId(tid, logId);
                Object[] op = undoLog.get(undoKey);
                if (op == null) {
                    // partially committed: load next
                    undoKey = undoLog.ceilingKey(undoKey);
                    if (undoKey == null || getTransactionId(undoKey) != tid) {
                        break;
                    }
                    logId = getLogId(undoKey) - 1;
                    continue;
                }
                int mapId = (Integer) op[0];
                MVMap<Object, VersionedValue> map = openMap(mapId);
                if (map == null) {
                    // map was later removed
                } else {
                    Object key = op[1];
                    VersionedValue value = map.get(key);
                    if (value == null) {
                        // nothing to do
                    } else if (value.value == null) {
                        // remove the value
                        map.remove(key);
                    } else {
                        VersionedValue v2 = new VersionedValue();
                        v2.value = value.value;
                        map.put(key, v2);
                    }
                }

                undoLog.remove(undoKey);
            }
        }
    }

    /**
     * Open the map with the given name.
     *
     * @param <K> the key type
     * @param name the map name
     * @param keyType the key type
     * @param valueType the value type
     * @return the map
     */
    synchronized <K> MVMap<K, VersionedValue> openMap(String name, DataType keyType, DataType valueType) {
        if (keyType == null) {
            keyType = new ObjectDataType();
        }
        if (valueType == null) {
            valueType = new ObjectDataType();
        }
        VersionedValueType vt = new VersionedValueType(valueType);
        MVMap<K, VersionedValue> map;
        MVMap.Builder<K, VersionedValue> builder = new MVMap.Builder<K, VersionedValue>().keyType(keyType)
                .valueType(vt);
        map = store.openMap(name, builder);
        @SuppressWarnings("unchecked")
        MVMap<Object, VersionedValue> m = (MVMap<Object, VersionedValue>) map;
        maps.put(map.getId(), m);
        return map;
    }

    /**
     * Open the map with the given id.
     *
     * @param mapId the id
     * @return the map
     */
    synchronized MVMap<Object, VersionedValue> openMap(int mapId) {
        MVMap<Object, VersionedValue> map = maps.get(mapId);
        if (map != null) {
            return map;
        }
        String mapName = store.getMapName(mapId);
        if (mapName == null) {
            // the map was removed later on
            return null;
        }
        VersionedValueType vt = new VersionedValueType(dataType);
        MVMap.Builder<Object, VersionedValue> mapBuilder = new MVMap.Builder<Object, VersionedValue>()
                .keyType(dataType).valueType(vt);
        map = store.openMap(mapName, mapBuilder);
        maps.put(mapId, map);
        return map;
    }

    /**
     * Create a temporary map. Such maps are removed when opening the store.
     *
     * @return the map
     */
    synchronized MVMap<Object, Integer> createTempMap() {
        String mapName = "temp." + nextTempMapId++;
        return openTempMap(mapName);
    }

    /**
     * Open a temporary map.
     *
     * @param mapName the map name
     * @return the map
     */
    MVMap<Object, Integer> openTempMap(String mapName) {
        MVMap.Builder<Object, Integer> mapBuilder = new MVMap.Builder<Object, Integer>().keyType(dataType);
        return store.openMap(mapName, mapBuilder);
    }

    /**
     * End this transaction
     *
     * @param t the transaction
     */
    synchronized void endTransaction(LocalTransaction t) {
        if (t.getStatus() == LocalTransaction.STATUS_PREPARED) {
            preparedTransactions.remove(t.getId());
        }
        t.setStatus(LocalTransaction.STATUS_CLOSED);
        if (store.getAutoCommitDelay() == 0) {
            store.commit();
            return;
        }
        // to avoid having to store the transaction log,
        // if there is no open transaction,
        // and if there have been many changes, store them now
        if (undoLog.isEmpty()) {
            int unsaved = store.getUnsavedMemory();
            int max = store.getAutoCommitMemory();
            // save at 3/4 capacity
            if (unsaved * 4 > max * 3) {
                store.commit();
            }
        }
    }

    /**
     * Rollback to an old savepoint.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     * @param toLogId the log id to roll back to
     */
    void rollbackTo(LocalTransaction t, long maxLogId, long toLogId) {
        // TODO could synchronize on blocks (100 at a time or so)
        synchronized (undoLog) {
            for (long logId = maxLogId - 1; logId >= toLogId; logId--) {
                Long undoKey = getOperationId(t.getId(), logId);
                Object[] op = undoLog.get(undoKey);
                if (op == null) {
                    // partially rolled back: load previous
                    undoKey = undoLog.floorKey(undoKey);
                    if (undoKey == null || getTransactionId(undoKey) != t.getId()) {
                        break;
                    }
                    logId = getLogId(undoKey) + 1;
                    continue;
                }
                int mapId = ((Integer) op[0]).intValue();
                MVMap<Object, VersionedValue> map = openMap(mapId);
                if (map != null) {
                    Object key = op[1];
                    VersionedValue oldValue = (VersionedValue) op[2];
                    if (oldValue == null) {
                        // this transaction added the value
                        map.remove(key);
                    } else {
                        // this transaction updated the value
                        map.put(key, oldValue);
                    }
                }
                undoLog.remove(undoKey);
            }
        }
    }

    /**
     * Get the changes of the given transaction, starting from the latest log id
     * back to the given log id.
     *
     * @param t the transaction
     * @param maxLogId the maximum log id
     * @param toLogId the minimum log id
     * @return the changes
     */
    Iterator<Change> getChanges(final LocalTransaction t, final long maxLogId, final long toLogId) {
        return new Iterator<Change>() {

            private long logId = maxLogId - 1;
            private Change current;

            {
                fetchNext();
            }

            private void fetchNext() {
                synchronized (undoLog) {
                    while (logId >= toLogId) {
                        Long undoKey = getOperationId(t.getId(), logId);
                        Object[] op = undoLog.get(undoKey);
                        logId--;
                        if (op == null) {
                            // partially rolled back: load previous
                            undoKey = undoLog.floorKey(undoKey);
                            if (undoKey == null || getTransactionId(undoKey) != t.getId()) {
                                break;
                            }
                            logId = getLogId(undoKey);
                            continue;
                        }
                        int mapId = ((Integer) op[0]).intValue();
                        MVMap<Object, VersionedValue> m = openMap(mapId);
                        if (m == null) {
                            // map was removed later on
                        } else {
                            current = new Change();
                            current.mapName = m.getName();
                            current.key = op[1];
                            VersionedValue oldValue = (VersionedValue) op[2];
                            current.value = oldValue == null ? null : oldValue.value;
                            return;
                        }
                    }
                }
                current = null;
            }

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public Change next() {
                if (current == null) {
                    throw DataUtils.newUnsupportedOperationException("no data");
                }
                Change result = current;
                fetchNext();
                return result;
            }

            @Override
            public void remove() {
                throw DataUtils.newUnsupportedOperationException("remove");
            }

        };
    }

    void commitTransactionStatusTable(LocalTransaction t, String allLocalTransactionNames) {
        t.setCommitTimestamp(nextOddTransactionId());
        TransactionStatusTable.commit(t, allLocalTransactionNames);

        Session s = t.getSession();
        TransactionValidator.getInstance().enqueue(s.getDatabase().getShortName(), this, t.getId(),
                s.getOriginalProperties(), allLocalTransactionNames);
    }

    boolean validateTransaction(Session session, int tid, LocalTransaction currentTransaction) {
        return TransactionStatusTable.isValid(session, TransactionManager.getHostAndPort(), tid, currentTransaction);
    }
}
