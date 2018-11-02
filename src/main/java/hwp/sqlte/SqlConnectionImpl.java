package hwp.sqlte;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * @author Zero
 * Created by Zero on 2017/6/4 0004.
 */
class SqlConnectionImpl implements SqlConnection {

    private static final Logger logger = LoggerFactory.getLogger("sql");

    private final static Map<Connection, SqlConnection> cache = new WeakHashMap<>();

    private final Connection conn;

    private SqlConnectionImpl(Connection conn) {
        this.conn = conn;
    }

    static SqlConnection use(Connection conn) {
        SqlConnection c = SqlConnectionImpl.cache.get(conn);
        if (c == null) {
            c = new SqlConnectionImpl(conn);
            SqlConnectionImpl.cache.put(conn, c);
        }
        return new SqlConnectionImpl(conn);
    }

    @Override
    public void executeSqlScript(Reader reader, boolean ignoreError) {
        ScriptRunner runner = new ScriptRunner(!ignoreError, getAutoCommit());
        runner.runScript(connection(), reader);
    }

    @Override
    public void statement(Consumer<Statement> consumer) throws UncheckedSQLException {
        try (Statement stat = connection().createStatement()) {
            consumer.accept(stat);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void prepareStatement(String sql, Consumer<PreparedStatement> consumer) throws UncheckedSQLException {
        try (PreparedStatement stat = connection().prepareStatement(sql)) {
            consumer.accept(stat);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public SqlResultSet query(String sql, Object... args) throws UncheckedSQLException {
        try (PreparedStatement stat = conn.prepareStatement(toSql(sql))) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("query: {}\t args: {}", sql, Arrays.toString(args));
            }
            try (java.sql.ResultSet rs = stat.executeQuery()) {
                return Helper.convert(rs);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public SqlResultSet query(Sql sql) throws UncheckedSQLException {
        return query(sql.sql(), sql.args());
    }

    @Override
    public SqlResultSet query(Consumer<SqlBuilder> consumer) throws UncheckedSQLException {
        SqlBuilder sb = new SqlBuilder();
        consumer.accept(sb);
        return query(sb.sql(), sb.args());
    }

    @Override
    public void query(Sql sql, Consumer<ResultSet> rowHandler) throws UncheckedSQLException {
        query(sql.sql(), rowHandler, sql.args());
    }

    @Override
    public void query(Consumer<SqlBuilder> consumer, Consumer<ResultSet> rowHandler) throws UncheckedSQLException {
        SqlBuilder sb = new SqlBuilder();
        consumer.accept(sb);
        query(sb.sql(), rowHandler, sb.args());
    }

    @Override
    public void query(String sql, Consumer<ResultSet> rowHandler, Object... args) throws UncheckedSQLException {
        try (PreparedStatement stat = createQueryStatement(sql)) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("query: {}\t args: {}", sql, Arrays.toString(args));
            }
            try (java.sql.ResultSet rs = stat.executeQuery()) {
                while (rs.next()) {
                    rowHandler.accept(rs);
                }
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    /**
     * @param sql        sql
     * @param rowHandler Stop if it returns false
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    public void query(Sql sql, RowHandler rowHandler) throws UncheckedSQLException {
        try (PreparedStatement stat = createQueryStatement(sql.sql())) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("query: {}\t args: {}", sql, Arrays.toString(sql.args()));
            }
            try (java.sql.ResultSet rs = stat.executeQuery()) {
                while (rs.next() && rowHandler.handle(Row.from(rs))) {

                }
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    private PreparedStatement createQueryStatement(String sql) throws UncheckedSQLException {
        try {
            PreparedStatement stat = conn.prepareStatement(toSql(sql), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            if (isMySQL()) {
                //jdbc规范: rows >= 0. MySQL有个例外, 可以是Integer.MIN_VALUE
                stat.setFetchSize(Integer.MIN_VALUE);//防止查询大数据时MySQL OOM
            }
            stat.setFetchDirection(ResultSet.FETCH_FORWARD);
            return stat;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public int insert(Sql sql) throws UncheckedSQLException {
        return this.insert(sql.sql(), sql.args());
    }

    @Override
    public int insert(String sql, Object... args) throws UncheckedSQLException {
        try (PreparedStatement stat = conn.prepareStatement(toSql(sql))) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("insert: {}\t args: {}", sql, Arrays.toString(args));
            }
            return stat.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public int insert(String table, String columns, Object... args) throws UncheckedSQLException {
        return insert(Helper.makeInsertSql(table, columns), args);
    }

    @Override
    public void insert(Sql sql, Consumer<ResultSet> resultHandler) throws UncheckedSQLException {
        try (PreparedStatement stat = conn.prepareStatement(toSql(sql.sql()), Statement.RETURN_GENERATED_KEYS)) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("insert: {}\t args: {}", sql, Arrays.toString(sql.args()));
            }
            stat.executeUpdate();
            try (ResultSet rs = stat.getGeneratedKeys()) {
                if (rs != null && rs.next()) {
                    resultHandler.accept(rs);
                }
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public Long insertAndReturnKey(String sql, String idColumn, Object... args) throws UncheckedSQLException {
        try (PreparedStatement stat = conn.prepareStatement(toSql(sql), new String[]{idColumn})) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("insert: {}\t args: {}", sql, Arrays.toString(args));
            }
            int i = stat.executeUpdate();
            if (i > 0) {
                try (ResultSet rs = stat.getGeneratedKeys()) {
                    if (rs != null && rs.next()) {
                        try {
                            return rs.getLong(idColumn);
                        } catch (SQLException e) {
                            return rs.getLong(1);
                        }
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void insertBean(Object bean) throws UncheckedSQLException {
        this.insertBean(bean, bean.getClass().getSimpleName().toLowerCase());
    }

    @Override
    public void insertBean(Object bean, String table) throws UncheckedSQLException {
        this.insertBean(bean, table, (String[]) null);
    }


    @Override
    public void insertBean(Object bean, String table, String... returnColumns) throws UncheckedSQLException {
        Field[] fields = bean.getClass().getFields();//只映射public字段，public字段必须有
        Field[] fs = new Field[fields.length];
        int count = 0;
        for (Field field : fields) {
            if (isPublicField(field)) {
                fs[count++] = field;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("The bean must contain public fields");
        }
        List<String> columns = new ArrayList<>(count);
        List<Object> values = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                Field field = fs[i];
                Object v = field.get(bean);
                if (v != null) {
                    columns.add(Helper.getColumnName(field));
                    values.add(field.get(bean));
                }
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            //Never happen
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The bean must contain public fields and value is not null");
        }
        if (table == null) {
            table = bean.getClass().getSimpleName().toLowerCase();
        }
        String sql = Helper.makeInsertSql(table, columns.toArray(new String[0]));
        //Statement.RETURN_GENERATED_KEYS
        try (PreparedStatement stat = returnColumns == null || returnColumns.length == 0 ? conn.prepareStatement(sql)
                : conn.prepareStatement(sql, returnColumns)) {// new String[]{"id"}
            Helper.fillStatement(stat, values.toArray(new Object[0]));
            if (logger.isDebugEnabled()) {
                logger.debug("insert: {}\t args: {}", sql, values);
            }
            int c = stat.executeUpdate();
            if (c == 0) {
                return;
            }
            if (returnColumns == null || returnColumns.length == 0) {
                return;
            }
            try (ResultSet keys = stat.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    //Field field = bean.getClass().getField(idName);/
                    //Modifier.isFinal(field.getModifiers())
                    //MySQL: BigInteger
                    ResultSetMetaData metaData = keys.getMetaData();
                    int cols = metaData.getColumnCount();
                    for (int i = 1; i <= cols; i++) {
                        String name = metaData.getColumnLabel(i);
//                    System.out.println(name + " --> " + keys.getObject(name));
                        //SQLite:last_insert_rowid()
                        //MySQL:GENERATED_KEY
                        String driverName = conn.getMetaData().getDriverName().toLowerCase();
                        if (driverName.contains("sqlite") || driverName.contains("mysql")) {
                            String idColumn = returnColumns[0];
                            Field f = Helper.getField(bean.getClass(), idColumn);
                            if (f != null) {
                                if (f.getType() == Long.TYPE || f.getType() == Long.class) {
                                    f.set(bean, keys.getLong(i));
                                } else {
                                    f.set(bean, keys.getInt(i));
                                }
                            }
                            break;
                        }
                        Field f = Helper.getField(bean.getClass(), name);
                        if (f != null) {
//                          f.set(bean, keys.getObject(i, f.getType()));
                            f.set(bean, keys.getObject(i));
                        }
                    }
                }
            } catch (IllegalAccessException e) {
                throw new UncheckedException(e);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public void batchInsert(List<?> beans, String table) throws UncheckedSQLException {
        if (beans.isEmpty()) {
            return;
        }
        Object first = beans.get(0);
        for (Object o : beans) {
            if (o.getClass() != first.getClass()) {
                throw new IllegalArgumentException("The object type in the collection must be consistent");
            }
        }
        if (table == null) {
            table = first.getClass().getSimpleName().toLowerCase();
        }
        //
        List<Field> fields = new ArrayList<>();
        for (Field field : first.getClass().getFields()) {
            if (isPublicField(field)) {
                fields.add(field);
            }
        }
        if (fields.size() == 0) {
            throw new IllegalArgumentException("The bean must contain public fields");
        }
        String[] columns = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            columns[i] = Helper.getColumnName(field);
        }

        String sql = Helper.makeInsertSql(table, columns);
        batchUpdate(sql, 100, executor -> {
            AtomicBoolean b = new AtomicBoolean(true);
            beans.forEach(obj -> {
                try {
                    Object[] args = new Object[columns.length];
                    for (int i = 0; i < columns.length; i++) {
                        args[i] = fields.get(i).get(obj);
                    }
                    executor.exec(args);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    if (b.get()) {
                        logger.error("batchUpdate error: {} \t sql: {}", e.getMessage(), sql);
                        b.set(false);
                    }
                }
            });
        });
    }


    @Override
    public int insertMap(String table, Map<String, Object> row) throws UncheckedSQLException {
        return insertMap(table, row, (String[]) null);
    }

    @Override
    public int insertMap(String table, Map<String, Object> row, String... returnColumns) throws UncheckedSQLException {
        String sql = Helper.makeInsertSql(table, row.keySet().toArray(new String[0]));
//      insert(sql, row.values().toArray());
        try (PreparedStatement stat = (returnColumns == null
                ? conn.prepareStatement(sql)
                : conn.prepareStatement(sql, returnColumns))) {//Statement.RETURN_GENERATED_KEYS
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, row.values());
            }
            Helper.fillStatement(stat, row.values().toArray());
            int uc = stat.executeUpdate();
            if (uc == 0) {
                return 0;
            }
            if (returnColumns != null && returnColumns.length > 0) {
                try (ResultSet keys = stat.getGeneratedKeys()) {
                    boolean isMysql = isMySQL();
                    if (keys != null && keys.next()) {
                        ResultSetMetaData metaData = keys.getMetaData();
                        int cols = metaData.getColumnCount();
                        for (int i = 1; i <= cols; i++) {
                            String name = metaData.getColumnLabel(i);
                            //mysql会返回GENERATED_KEY, 没有实现JDBC规范
                            if ("GENERATED_KEY".equals(name) && isMysql) {
                                row.put(returnColumns[0].toLowerCase(), keys.getObject(i));
                                break;
                            }
                            //pgsql如果设置了列名, 则返回指定列, RETURN_GENERATED_KEYS会返回所有列
                            row.put(name.toLowerCase(), keys.getObject(i));
                        }
                    }
                }
                return uc;
            }
            return 0;
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }
    }
    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public int update(String sql, Object... args) throws UncheckedSQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, Arrays.toString(args));
            }
            Helper.fillStatement(statement, args);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }
    }

    @Override
    public int update(Consumer<SqlBuilder> consumer) throws UncheckedSQLException {
        SqlBuilder builder = new SqlBuilder();
        consumer.accept(builder);
        if (logger.isDebugEnabled()) {
            logger.debug("sql: {}\t args: {}", builder.sql(), Arrays.toString(builder.args()));
        }
        try (PreparedStatement statement = conn.prepareStatement(builder.sql())) {
            Helper.fillStatement(statement, builder.args());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }
    }

    @Override
    public <T> void batchUpdate(String sql, Iterable<T> it, BiConsumer<BatchExecutor, T> consumer) throws
            UncheckedSQLException {
        this.batchUpdate(sql, 1000, it, consumer);
    }

    @Override
    public <T> BatchUpdateResult batchUpdate(String sql, int maxBatchSize, Iterable<
            T> it, BiConsumer<BatchExecutor, T> consumer) throws UncheckedSQLException {
        return batchUpdate(sql, maxBatchSize, executor -> it.forEach(t -> consumer.accept(executor, t)));
    }

    //分批导入大量数据
    @Override
    public BatchUpdateResult batchUpdate(String sql, Consumer<BatchExecutor> consumer) throws
            UncheckedSQLException {
        return this.batchUpdate(sql, 1000, consumer);
    }

    @Override
    public BatchUpdateResult batchUpdate(String table, String columns, Consumer<BatchExecutor> consumer) throws
            UncheckedSQLException {
        String sql = Helper.makeUpdateSql(table, columns);
        return this.batchUpdate(sql, consumer);
    }

    @Override
    public BatchUpdateResult batchInsert(String table, String columns, Consumer<BatchExecutor> consumer) throws
            UncheckedSQLException {
        String sql = Helper.makeInsertSql(table, columns);
        return this.batchUpdate(sql, consumer);
    }

    @Override
    public BatchUpdateResult batchUpdate(String sql, int maxBatchSize, Consumer<BatchExecutor> consumer) throws
            UncheckedSQLException {
        if (logger.isDebugEnabled()) {
            logger.debug("sql: {}", sql);
        }
        try (PreparedStatement statement = conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            return batchUpdate(statement, maxBatchSize, consumer, null);
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }
    }

    @Override
    public BatchUpdateResult batchUpdate(PreparedStatement statement, int maxBatchSize, Consumer<
            BatchExecutor> consumer, BiConsumer<PreparedStatement, int[]> psConsumer) throws UncheckedSQLException {
        try {
            boolean autoCommit = conn.getAutoCommit();
            if (autoCommit) {
                conn.setAutoCommit(false);
            }
            Savepoint savepoint = conn.setSavepoint("batchUpdate");
            BatchUpdateResult result = new BatchUpdateResult();
            UnsafeCount count = new UnsafeCount();
            BatchExecutor executor = args -> {
                try {
                    Helper.fillStatement(statement, args);
                    statement.addBatch();
                    if (count.add(1) >= maxBatchSize) {
                        int[] rs0 = statement.executeBatch();
                        updateBatchUpdateResult(result, rs0);
                        if (psConsumer != null) {
                            psConsumer.accept(statement, rs0);
                        }
                        count.reset();
                    }
                    statement.clearParameters();
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            };
            consumer.accept(executor);
            if (count.get() > 0) {
                int[] rs0 = statement.executeBatch();
                updateBatchUpdateResult(result, rs0);
                if (psConsumer != null) {
                    psConsumer.accept(statement, rs0);
                }
            }
            if (autoCommit) {
                try {
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback(savepoint);
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            return result;
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }

    }

    private void updateBatchUpdateResult(BatchUpdateResult result, int[] rs) {
        for (int r : rs) {
            if (r > 0) {
                result.affectedRows += r;
            } else if (r == Statement.SUCCESS_NO_INFO) {
                result.successNoInfoCount++;
            } else if (r == Statement.EXECUTE_FAILED) {
                result.failedCount++;
            }
        }
    }

    @Override
    public int update(Object bean, String table, Consumer<Where> where) throws UncheckedSQLException {
        try {
            SqlBuilder builder = new SqlBuilder();
            builder.add("UPDATE ").add(table).add(" SET ");
            Field[] fields = bean.getClass().getFields();
            int updateCount = 0;
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                if (isPublicField(field)) {
                    Object value = field.get(bean);
                    if (value != null) {
                        if (updateCount > 0) {
                            builder.add(",");
                        }
                        builder.sql(Helper.getColumnName(field)).add("=? ", value);
                        updateCount++;
                    }
                }
            }
            builder.where(where);
            if (logger.isDebugEnabled()) {
                logger.debug("update: {}\t args: {}", builder.sql(), Arrays.toString(builder.args()));
            }
            return update(builder.sql(), builder.args());
        } catch (IllegalAccessException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public int update(Map<String, Object> map, String table, Where where) throws UncheckedSQLException {
        SqlBuilder builder = new SqlBuilder();
        builder.add("UPDATE ").add(table).add(" SET ");
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            builder.add(entry.getKey()).add("=?", entry.getValue());
            if (it.hasNext()) {
                builder.add(", ");
            }
        }
        builder.where(where);
        try (PreparedStatement statement = conn.prepareStatement(builder.sql())) {
            if (logger.isDebugEnabled()) {
                logger.debug("update: {}\t args: {}", builder.sql(), Arrays.toString(builder.args()));
            }
            Helper.fillStatement(statement, builder.args());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedException(e);
        }
    }

    @Override
    public int update(Map<String, Object> map, String table, Consumer<Where> where) throws
            UncheckedSQLException {
        Where w = new Where();
        where.accept(w);
        return update(map, table, w);
    }

    /**
     * @param map   data
     * @param table table name
     * @param ids   default name is "id"
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    public int updateByPks(Map<String, Object> map, String table, String... ids) throws UncheckedSQLException {
        return update(new HashMap<>(map), table, where -> {
            if (ids.length == 0) {
                Object v = map.get("id");
                if (v == null) {
                    throw new IllegalArgumentException("id is required");
                }
                where.and("id=?", v);
            } else {
                for (String key : ids) {
                    Object v = map.get(key);
                    if (v == null) {
                        throw new IllegalArgumentException("Can't found not null value by key: " + key);
                    }
                    where.and(key + "=?", v);
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////
    // 委托方法
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setAutoCommit(boolean autoCommit) throws UncheckedSQLException {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public boolean getAutoCommit() throws UncheckedSQLException {
        try {
            return conn.getAutoCommit();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void commit() throws UncheckedSQLException {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void rollback() throws UncheckedSQLException {
        try {
            conn.rollback();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void close() throws UncheckedSQLException {
        try {
            conn.close();
            if (logger.isDebugEnabled()) {
                logger.debug("SqlConnection closed");
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public boolean isClosed() throws UncheckedSQLException {
        try {
            return conn.isClosed();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public void setReadOnly(boolean readOnly) throws UncheckedSQLException {
        try {
            conn.setReadOnly(readOnly);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public boolean isReadOnly() throws UncheckedSQLException {
        try {
            return conn.isReadOnly();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void setTransactionIsolation(int level) throws UncheckedSQLException {
        try {
            conn.setTransactionIsolation(level);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public int getTransactionIsolation() throws UncheckedSQLException {
        try {
            return conn.getTransactionIsolation();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public SqlConnection beginTransaction() throws UncheckedSQLException {
        try {
            conn.setAutoCommit(false);
            return this;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public SqlConnection beginTransaction(int level) throws UncheckedSQLException {
        try {
            conn.setTransactionIsolation(level);
            conn.setAutoCommit(false);
            return this;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public Savepoint setSavepoint() throws UncheckedSQLException {
        try {
            return conn.setSavepoint();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public Savepoint setSavepoint(String name) throws UncheckedSQLException {
        try {
            return conn.setSavepoint(name);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void rollback(Savepoint savepoint) throws UncheckedSQLException {
        try {
            conn.rollback(savepoint);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws UncheckedSQLException {
        try {
            conn.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws UncheckedSQLException {
        try {
            return conn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws UncheckedSQLException {
        try {
            return conn.prepareStatement(sql, autoGeneratedKeys);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws UncheckedSQLException {
        try {
            return conn.prepareStatement(sql, columnNames);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public boolean isValid(int timeout) throws UncheckedSQLException {
        try {
            return conn.isValid(timeout);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws UncheckedSQLException {
        try {
            return conn.prepareStatement(sql);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public CallableStatement prepareCall(String sql) throws UncheckedSQLException {
        try {
            return conn.prepareCall(sql);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws
            UncheckedSQLException {
        try {
            return conn.prepareCall(sql, resultSetType, resultSetConcurrency);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public Connection connection() {
        return this.conn;
    }

//

    private boolean isPublicField(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers());
    }

    private String toSql(String sql) {
        if (sql.startsWith("#")) {
            return Config.getConfig().getSqlProvider().getSql(sql.substring(1));
        }
        return sql;
    }

    private boolean isMySQL() throws SQLException {
        String driverName = conn.getMetaData().getDriverName().toLowerCase();
        return driverName.contains("mysql");
    }

}