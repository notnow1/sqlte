package hwp.sqlte;

import hwp.sqlte.example.User;

import java.sql.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Zero
 *         Created on 2017/3/21.
 */
public class Insert {
    private StandardSql sql;
    private boolean autoGeneratedKeys;
    private Connection connection;
    private SqlResultSet resultSet;

    private PreparedStatement statement;


    public Insert(StandardSql sql) {
        this.sql = sql;
    }

    public Insert genKeys() {
        this.autoGeneratedKeys = true;
        return this;
    }


    public Insert handleResult(Consumer<SqlResultSet> rs) {
        if (this.autoGeneratedKeys) {
            rs.accept(this.resultSet);
        } else {
            throw new UnsupportedOperationException("当前insert操作未设置autoGeneratedKeys");
        }
        return this;
    }

/*
    public void asyncExecute(Consumer<SqlResultSet> consumer) throws SQLException {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                execute(autoGeneratedKeys).handleResult(consumer);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }
*/

    public Insert execute() throws SQLException {
        return execute(autoGeneratedKeys);
    }

    public Insert execute(boolean autoGeneratedKeys) throws SQLException {
        this.autoGeneratedKeys = autoGeneratedKeys;
        if (this.resultSet == null) {
            PreparedStatement statement = connection.prepareStatement(sql.sql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
            Helper.fillStatement(statement, sql.args());
            statement.executeUpdate();
            this.resultSet = Helper.convert(statement.getGeneratedKeys());

//            Savepoint savepoint = connection.setSavepoint("start");
/*            try {
                for (Object object : sql.args()) {
                    Object[] arg = new Object[sql.parameters().size()];
                    Map<String, Object> map = Helper.beanToArgs(object);
                    for (int i = 0; i < sql.parameters().size(); i++) {
                        NameParameter parameter = sql.parameters().get(i);
                        arg[i] = map.get(parameter.name);
                        this.args.add(arg);
                    }
                }
            } catch (Exception e) {

            }*/

/*            int batchSize = 1000;
            int size = 0;
            for (Object[] arg : args) {
                Helper.fillStatement(statement, arg);
                statement.addBatch();
                size++;
                if (size == batchSize) {
                    statement.executeBatch();
                }
            }
            if (size > 0) {
                statement.executeBatch();
            }
            if (autoGeneratedKeys) {
                this.resultSet = Helper.convert(statement.getGeneratedKeys());
            }*/
        }
        return this;
    }

    public static String make(String table, String columns) {
        return make(table, columns.split(","));
    }

    public static String make(String table, String... columns) {
        StringBuilder builder = new StringBuilder("INSERT INTO ").append(table);
        builder.append('(');
        int len = columns.length;
        for (int i = 0; i < len; ) {
            builder.append(columns[i].trim());
            if (++i < len) {
                builder.append(',');
            }
        }
        builder.append(") VALUES (");
        for (int i = 0; i < len; ) {
            builder.append('?');
            if (++i < len) {
                builder.append(',');
            }
        }
        builder.append(')');
        return builder.toString();
    }

    public static void exec(PreparedStatement stat, Object... args) throws SQLException {
        stat.clearParameters();
        Helper.fillStatement(stat, args);
        stat.execute();
    }


    public static void main(String[] args) throws SQLException {
/*        StandardSql standardSql = new StandardSql("insert into user(username,password) values(?,?)");
        standardSql.args("Zero", "123456");
        Insert insert = new Insert(standardSql);
        User user = new User();
        insert.execute(true).handleResult(rs -> {
            Row row = rs.firstRow();
            if (row != null) {
//              user.id= row.get("id");
            }
        });*/

        Pattern pattern = Pattern.compile(":(\\w+)");
        Matcher matcher = pattern.matcher("insert user values(:username,:password)");
        while (matcher.find()) {
            System.out.println(matcher.group(1));
        }
        System.out.println("INSERT INTO user VALUES(:username,:password)".replaceAll(":(\\w+)", "?"));

        System.out.println(Insert.make("user", "username", "password", "slat"));
        System.out.println(Insert.make("user", "username,password,slat"));

    }


}
