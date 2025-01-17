package hwp.sqlte;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author Zero
 * Created by Zero on 2017/6/4 0004.
 */
class SqlConnectionImpl implements SqlConnection {

    private static final Logger logger = LoggerFactory.getLogger(SqlConnection.class);

    private final Connection conn;

    SqlConnectionImpl(Connection conn) {
        this.conn = conn;
    }

    static SqlConnection use(Connection conn) {
        return new SqlConnectionImpl(conn);
    }

    @Override
    public SqlConnection cacheable() {
        return new SqlConnectionCacheWrapper(this, Config.getConfig().getCache());
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
        try (PreparedStatement stat = connection().prepareStatement(toSql(sql))) {
            consumer.accept(stat);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public SqlResultSet query(String sql) throws UncheckedSQLException {
        try (Statement stat = conn.createStatement()) {
            sql = toSql(sql);
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}", sql);
            }
            try (java.sql.ResultSet rs = stat.executeQuery(sql)) {
                return Helper.convert(rs);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public SqlResultSet query(String sql, Object... args) throws UncheckedSQLException {
        sql = toSql(sql);
        try (PreparedStatement stat = conn.prepareStatement(sql)) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, Arrays.toString(args));
            }
            try (java.sql.ResultSet rs = stat.executeQuery()) {
                return Helper.convert(rs);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public <T> T tryGet(Supplier<T> supplier, Object id) throws UncheckedSQLException {
        T bean = supplier.get();
        ClassInfo info = ClassInfo.getClassInfo(bean.getClass());
        String pkColumn = info.getPKColumn();
        Row first = query("SELECT * FROM " + info.getTableName() + " WHERE " + pkColumn + "=?", id).first();
        if (first == null) {
            return null;
        }
        return first.copyTo(bean);
    }

    @Override
    public <T> T tryGet(Class<T> clazz, Object id) throws SqlteException {
        ClassInfo info = ClassInfo.getClassInfo(clazz);
        String pkColumn = info.getPKColumn();
        Row first = query("SELECT * FROM " + info.getTableName() + " WHERE " + pkColumn + "=?", id).first();
        if (first == null) {
            return null;
        }
        try {
            T obj = clazz.getDeclaredConstructor().newInstance();
            return first.copyTo(obj);
        } catch (ReflectiveOperationException e) {
            throw new SqlteException(e);
        }
    }

    @Override
    public <T> T tryGet(Class<T> clazz, Consumer<Map<String, Object>> consumer) throws UncheckedSQLException {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(consumer);
        ClassInfo info = ClassInfo.getClassInfo(clazz);
        String pkColumn = info.getPKColumn();
        Map<String, Object> map = new HashMap<>();
        consumer.accept(map);
        if (map.isEmpty()) {
            throw new IllegalArgumentException("map is empty");
        }
        List<T> list = query(sql -> {
            sql.from(info.getTableName()).where(where -> {
                map.forEach((name, value) -> {
                    where.and(name + " =?", value);
                });
            }).limit(2);
        }).list(clazz);
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        throw new UncheckedSQLException("Only one record was expected to be returned, but multiple records were returned");
    }

    @Override
    public <T> T reload(T bean) throws UncheckedSQLException {
        try {
            ClassInfo info = ClassInfo.getClassInfo(bean.getClass());
            String[] pkColumns = info.getPkColumns();
            Where where = new Where();
            for (String k : pkColumns) {
                where.and(k + "=?", info.getField(k).get(bean));
            }
            Row first = query(sql -> sql.from(info.getTableName()).where(where)).first();
            if (first == null) {
                return null;
            }
            return first.copyTo(bean);
        } catch (IllegalAccessException e) {
            return bean;
        }
    }


    @Override
    public void query(Sql sql, ResultSetHandler rowHandler) throws UncheckedSQLException {
        try (PreparedStatement stat = createQueryStatement(sql.sql())) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, Arrays.toString(sql.args()));
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


    @Override
    public void query(Sql sql, RowHandler rowHandler) throws UncheckedSQLException {
        String _sql = toSql(sql.sql());
        try (PreparedStatement stat = createQueryStatement(_sql)) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", _sql, Arrays.toString(sql.args()));
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
            sql = toSql(sql);
            PreparedStatement stat = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
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
    public int insert(String table, String columns, Object... args) throws UncheckedSQLException {
        return executeUpdate(Helper.makeInsertSql(table, columns), args);
    }

    @Override
    public void insert(Sql sql, ResultSetHandler resultHandler) throws UncheckedSQLException {
        String _sql = toSql(sql.sql());
        try (PreparedStatement stat = conn.prepareStatement(_sql, Statement.RETURN_GENERATED_KEYS)) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", _sql, Arrays.toString(sql.args()));
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
        sql = toSql(sql);
        try (PreparedStatement stat = conn.prepareStatement(sql, new String[]{idColumn})) {
            if (args.length > 0) {
                Helper.fillStatement(stat, args);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, Arrays.toString(args));
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
    public void insert(Object bean, String table) throws UncheckedSQLException {
        this.insert(null, bean, table);
    }

    @Override
    public void replace(Object bean, String table) throws UncheckedSQLException {
        this.insert("REPLACE INTO", bean, table);
    }

    @Override
    public void insertIgnore(Object bean, String table) throws UncheckedSQLException {
        this.insert("INSERT IGNORE INTO", bean, table);
    }

    private void insert(String insert, Object bean, String table) throws UncheckedSQLException {
        ClassInfo info = ClassInfo.getClassInfo(bean.getClass());
        if (table == null) {
            table = info.getTableName();
        }
        Map<String, Field> columnFieldMap = info.getColumnFieldMap();
        if (columnFieldMap.size() == 0) {
            throw new IllegalArgumentException("The bean must contain public fields");
        }
        List<String> columns = new ArrayList<>(columnFieldMap.size());
        List<Object> values = new ArrayList<>(columnFieldMap.size());
        try {
            for (Map.Entry<String, Field> entry : columnFieldMap.entrySet()) {
                Field field = entry.getValue();
                Object v = Helper.getSerializedValue(bean, field);
                if (v != null) {
                    columns.add(entry.getKey());
                    values.add(v);//enum->int
                }
            }
        } catch (Exception e) {
            throw new SqlteException(e);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The bean must contain public fields and value is not null");
        }

        String[] returnColumns = info.getAutoGenerateColumns();

        String sql = Helper.makeInsertSql(insert, table, columns.toArray(new String[0]));
        //Statement.RETURN_GENERATED_KEYS
        try (PreparedStatement stat = returnColumns == null || returnColumns.length == 0 ? conn.prepareStatement(sql)
                : conn.prepareStatement(sql, returnColumns)) {// new String[]{"id"}
            Helper.fillStatement(stat, values.toArray(new Object[0]));
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, values);
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
//                    ConversionService conversion = Config.getConfig().getConversionService();
                    for (int i = 1; i <= cols; i++) {
                        String name = metaData.getColumnLabel(i);
//                    System.out.println(name + " --> " + keys.getObject(name));
                        //SQLite:last_insert_rowid()
                        //MySQL:GENERATED_KEY
                        String driverName = conn.getMetaData().getDriverName().toLowerCase();
                        if (driverName.contains("sqlite") || driverName.contains("mysql")) {
                            String idColumn = returnColumns[0];
                            Field f = info.getField(idColumn);
                            if (f != null) {
                                Object id = keys.getObject(1, f.getType());//bug: MySQL driver 5.1.6 is not support
                                f.set(bean, id);
                                break;
                            }
                            break;
                        }
                        for (String column : info.getColumns()) {
                            if (column.equalsIgnoreCase(name)) {
                                Field f = info.getField(column);
                                if (f != null) {
                                    Object id = keys.getObject(i, f.getType());
                                    f.set(bean, id);
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (IllegalAccessException e) {
                throw new SqlteException(e);
            }
        } catch (SQLException e) {
//            e.printStackTrace();
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public BatchUpdateResult batchInsert(List<?> beans, String table) throws UncheckedSQLException {
        return batchInsert(beans, table, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public BatchUpdateResult batchInsert(List<?> beans, String table, Function<String, String> sqlProcessor) throws UncheckedSQLException {
        if (beans.isEmpty()) {
            return BatchUpdateResult.EMPTY;
        }
        Object first = beans.get(0);
        for (Object o : beans) {
            if (o.getClass() != first.getClass()) {
                throw new IllegalArgumentException("The object type in the collection must be consistent");
            }
        }
        return batchInsert(consumer -> beans.forEach(consumer::accept), (Class) first.getClass(), table, sqlProcessor);
    }

    @Override
    public <T> BatchUpdateResult batchInsert(Consumer<Consumer<T>> consumer, Class<T> clazz, String table) throws UncheckedSQLException {
        return this.batchInsert(consumer, clazz, table, null);
    }

    @Override
    public <T> BatchUpdateResult batchInsert(Consumer<Consumer<T>> consumer, Class<T> clazz, String table, Function<String, String> sqlProcessor) throws UncheckedSQLException {
//        ClassInfo info = ClassInfo.getClassInfo(clazz);
//        boolean hasGks = info.getAutoGenerateColumns().length > 0;
        //返回的stat.getGeneratedKeys(): MySQL 设置RETURN_GENERATED_KEYS是可滚动的, PGSQL是不可滚动的
        return batchInsert(consumer, clazz, table, sqlProcessor, null);
    }

    @Override
    public <T> BatchUpdateResult batchInsert(Consumer<Consumer<T>> consumer, Class<T> clazz, String table, Function<String, String> sqlProcessor, BiConsumer<PreparedStatement, int[]> psConsumer) throws UncheckedSQLException {
        ClassInfo info = ClassInfo.getClassInfo(clazz);
        if (table == null) {
            table = info.getTableName();
        }
        String[] columns = info.getInsertColumns();
        String sql = sqlProcessor == null ? Helper.makeInsertSql(table, columns) : sqlProcessor.apply(Helper.makeInsertSql(table, columns));
        try (PreparedStatement stat = conn.prepareStatement(sql, info.getAutoGenerateColumns())) {
            return batchUpdate(stat, 500, executor -> {
                AtomicBoolean b = new AtomicBoolean(true);
                consumer.accept(bean -> {
                    try {
                        Object[] args = new Object[columns.length];
                        for (int i = 0; i < columns.length; i++) {
                            Field field = info.getField(columns[i]);
                            args[i] = Helper.getSerializedValue(bean, field);
                        }
                        executor.exec(args);
                    } catch (IllegalArgumentException | IllegalAccessException e) {
                        if (b.get()) {
                            logger.error("batchUpdate error: {} \t sql: {}", e.getMessage(), sql);
                            b.set(false);
                        }
                    }
                });
            }, psConsumer);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public int insertMap(String table, Map<String, Object> row) throws UncheckedSQLException {
        return insertMap(table, row, (String[]) null);
    }

    @Override
    public int insertMap(String table, Consumer<Row> row) throws UncheckedSQLException {
        Row _map = new Row();
        row.accept(_map);
        return this.insertMap(table, _map);
    }

    @Override
    public int insertMap(String table, Map<String, Object> row, String... returnColumns) throws UncheckedSQLException {
        return this.insertMap(null, table, row, returnColumns);
    }

    @Override
    public int replaceMap(String table, Map<String, Object> row, String... returnColumns) {
        return this.insertMap("REPLACE INTO", table, row, returnColumns);
    }

    @Override
    public int insertIgnoreMap(String table, Map<String, Object> row, String... returnColumns) {
        return this.insertMap("INSERT IGNORE INTO", table, row, returnColumns);
    }

    private int insertMap(String insert, String table, Map<String, Object> row, String... returnColumns) throws UncheckedSQLException {
        String sql = Helper.makeInsertSql(insert, table, row.keySet().toArray(new String[0]));
//      insert(sql, row.values().toArray());
        try (PreparedStatement stat = (returnColumns == null ? conn.prepareStatement(sql)
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
            return uc;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    @Override
    public int executeUpdate(String sql, Object... args) throws UncheckedSQLException {
        sql = toSql(sql);
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", sql, Arrays.toString(args));
            }
            Helper.fillStatement(statement, args);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public int update(Consumer<SqlBuilder> consumer) throws UncheckedSQLException {
        SqlBuilder builder = new SqlBuilder();
        consumer.accept(builder);
        String sql = toSql(builder.sql());
        if (logger.isDebugEnabled()) {
            logger.debug("sql: {}\t args: {}", sql, Arrays.toString(builder.args()));
        }
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            Helper.fillStatement(statement, builder.args());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public boolean update(Object bean, String table, String columns, boolean ignoreNullValue, Consumer<Where> where) throws UncheckedSQLException {
        try {
            ClassInfo info = ClassInfo.getClassInfo(bean.getClass());

            String[] _columns;
            if (columns == null) {
                _columns = info.getUpdateColumns();
            } else {
                _columns = columns.trim().split("\\s*,\\s*");
            }

            if (_columns.length == 0) {
                throw new IllegalArgumentException("No fields to modify: " + columns);
            }

            Object[] args = new Object[_columns.length];
            int nullCount = 0;
            for (int i = 0; i < _columns.length; i++) {
                String column = _columns[i];
                Field field = info.getField(column);
                if (field == null) {
                    throw new IllegalArgumentException("No field mapping: " + column);
                }
                args[i] = Helper.getSerializedValue(bean, field);
                if (args[i] == null) {
                    nullCount++;
                }
            }
            if (ignoreNullValue && nullCount > 0) {
                int updateColumnCount = _columns.length - nullCount;
                if (updateColumnCount < 1) {
//                    throw new UncheckedException("No fields to update");
                    return false;
                }
                String[] newColumns = new String[updateColumnCount];
                Object[] newArgs = new Object[updateColumnCount];
                for (int i = 0, ci = 0; i < args.length; i++) {
                    Object v = args[i];
                    if (v != null) {
                        newColumns[ci] = _columns[i];
                        newArgs[ci] = v;
                        ci++;
                    }
                }
                args = newArgs;
                _columns = newColumns;
            }
            if (table == null) {
                table = info.getTableName();
            }
            String sql = Helper.makeUpdateSql(table, _columns);
            SqlBuilder builder = new SqlBuilder();
            builder.append(sql, args);

            Where where0 = new Where();
            if (where == null) {
                String[] pkColumns = info.getPkColumns();
                if (pkColumns.length == 0) {
                    throw new IllegalArgumentException("No key field mapping for " + bean.getClass().getName());
                }
                for (String k : pkColumns) {
                    Field field = info.getField(k);
                    Object idValue = field.get(bean);
                    if (idValue == null) {
                        throw new IllegalArgumentException("Key field value is null: " + field.getName());
                    }
                    where0.and(k + "=?", idValue);
                }
            } else {
                where.accept(where0);
            }
            builder.where(where0);
            return executeUpdate(builder.sql(), builder.args()) == 1;
        } catch (IllegalAccessException e) {
            //Never happen
            return false;
        }
    }


    @Override
    public <T> void batchUpdate(String sql, Iterable<T> it, BiConsumer<BatchExecutor, T> consumer)
            throws UncheckedSQLException {
        this.batchUpdate(sql, 1000, it, consumer);
    }

    @Override
    public <T> BatchUpdateResult batchUpdate(String sql, int batchSize, Iterable<
            T> it, BiConsumer<BatchExecutor, T> consumer) throws UncheckedSQLException {
        return batchUpdate(sql, batchSize, executor -> it.forEach(t -> consumer.accept(executor, t)));
    }

    //分批导入大量数据
    @Override
    public BatchUpdateResult batchUpdate(String sql, Consumer<BatchExecutor> consumer)
            throws UncheckedSQLException {
        return this.batchUpdate(sql, 1000, consumer);
    }

    @Override
    public BatchUpdateResult batchUpdate(String table, String columns, Consumer<Where> whereConsumer, Consumer<BatchExecutor> consumer)
            throws UncheckedSQLException {
        String sql = Helper.makeUpdateSql(table, StringUtils.splitToArray(columns));
        Where where = new Where();
        whereConsumer.accept(where);
        if (where.isEmpty()) {
            throw new SqlteException("必须设置更新条件");
        }
        sql = sql + " WHERE " + where.sql();
        return this.batchUpdate(sql, consumer);
    }

    @Override
    public BatchUpdateResult batchInsert(String table, String columns, Consumer<BatchExecutor> consumer)
            throws UncheckedSQLException {
        String sql = Helper.makeInsertSql(table, columns);
        return this.batchUpdate(sql, consumer);
    }

    @Override
    public BatchUpdateResult batchUpdate(String sql, int batchSize, Consumer<BatchExecutor> consumer) throws
            UncheckedSQLException {
        sql = toSql(sql);
        if (logger.isDebugEnabled()) {
            logger.debug("sql: {}", sql);
        }
        try (PreparedStatement statement = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            return batchUpdate(statement, batchSize, consumer, null);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Override
    public BatchUpdateResult batchUpdate(PreparedStatement statement, int batchSize, Consumer<BatchExecutor> consumer,
                                         BiConsumer<PreparedStatement, int[]> psConsumer) throws UncheckedSQLException {
        try {
            boolean autoCommit = conn.getAutoCommit();
            if (autoCommit) {
                conn.setAutoCommit(false);
            }
            Savepoint savepoint = conn.setSavepoint("batchUpdate");
            BatchUpdateResult result = new BatchUpdateResult();
            Counter count = new Counter();
            BatchExecutor executor = args -> {
                try {
                    Helper.fillStatement(statement, args);
                    statement.addBatch();
                    if (count.add(1) >= batchSize) {
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
            throw new UncheckedSQLException(e);
        }

    }

    @Override
    public void batchUpdate(List<?> beans) throws UncheckedSQLException {
        for (Object bean : beans) {
            this.update(bean, null, false);
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

/*    public boolean update(Object bean, String table, Consumer<Where> where) throws UncheckedSQLException {
        try {
            ClassInfo info = ClassInfo.getClassInfo(bean.getClass());
            if (table == null) {
                table = info.getTableName();
            }
            SqlBuilder builder = new SqlBuilder();
            builder.add("UPDATE ").add(table).add(" SET ");

            int updateColumnCount = 0;
            for (Map.Entry<String, Field> entry : info.getColumnFieldMap().entrySet()) {
                Object value = entry.getValue().get(bean);
                if (value != null) {
                    if (updateColumnCount > 0) {
                        builder.add(", ");
                    }
                    builder.sql(entry.getKey()).add("=?", value);
                    updateColumnCount++;
                }
            }
            builder.where(where);
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", builder.sql(), Arrays.toString(builder.args()));
            }
            return update(builder.sql(), builder.args()) == 1;
        } catch (IllegalAccessException e) {
            throw new UncheckedSQLException(e);
        }
    }*/

    @Override
    public boolean delete(Object bean, String table) throws UncheckedSQLException {
        try {
            ClassInfo info = ClassInfo.getClassInfo(bean.getClass());
            if (table == null) {
                table = info.getTableName();
            }
            String[] pkColumns = info.getPkColumns();
            if (pkColumns.length == 0) {
                throw new IllegalArgumentException("The class unspecified ID field: " + bean.getClass().getName());
            }
            SqlBuilder builder = new SqlBuilder();
            builder.append("DELETE FROM ").append(table);

            Where where = new Where();
            for (String pkColumn : pkColumns) {
                Field field = info.getField(pkColumn);
                Object value = Helper.getSerializedValue(bean, field);
                Objects.requireNonNull(value, "ID field value is NULL: " + bean.getClass().getName() + "." + field.getName());
                where.and(pkColumn + "=?", value);
            }
            builder.where(where);
            return executeUpdate(builder.sql(), builder.args()) == 1;
        } catch (IllegalAccessException e) {
            throw new UncheckedSQLException(e);
        }
    }


    @Override
    public int update(String table, Map<String, Object> map, Where where) throws UncheckedSQLException {
        SqlBuilder builder = new SqlBuilder();
        builder.append("UPDATE ").append(table).append(" SET ");
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            builder.append(entry.getKey()).append("=?", entry.getValue());
            if (it.hasNext()) {
                builder.append(", ");
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
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * @param table table name
     * @param map   data
     * @param ids   default name is "id"
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    public int updateByPks(String table, Map<String, Object> map, String... ids) throws UncheckedSQLException {
        return update(table, new HashMap<>(map), where -> {
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

    /*
    <T> void update2(Class<T> clazz, Sql sql, EFunction<T, Boolean> function) throws Exception {
        String _sql = toSql(sql.sql());
        try (PreparedStatement stat = createQueryStatement(_sql)) {
            if (sql.args().length > 0) {
                Helper.fillStatement(stat, sql.args());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("sql: {}\t args: {}", _sql, Arrays.toString(sql.args()));
            }
            try (java.sql.ResultSet rs = stat.executeQuery()) {
                while (rs.next()) {
                    T obj = Row.from(rs).map(clazz);
                    // 如果对象被修改了, 则更新对象
                    if (function.apply(obj)) {
                        update(obj);
                    }
                }
            }
        }
    }*/


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
            conn.setAutoCommit(true);
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
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws UncheckedSQLException {
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

    private String toSql(String sql) {
        if (sql.charAt(0) == '#') {
            return Config.getConfig().getSqlProvider().getSql(sql.substring(1));
        }
        return sql;
    }

    private boolean isMySQL() throws SQLException {
        String driverName = conn.getMetaData().getDriverName().toLowerCase();
        return driverName.contains("mysql");
    }


}
