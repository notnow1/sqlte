package hwp.sqlte;

//import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;

//import com.mysql.cj.jdbc.MysqlDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.*;

import java.net.URL;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Zero
 * Created by Zero on 2017/6/17 0017.
 */
public class SqlConnectionTest {

    private SqlConnection conn;

    private static String dbname = "h2";//h2, mysql, pgsql

    @BeforeClass
    public static void beforeClass() {
        HikariConfig config = new HikariConfig();
        config.setAutoCommit(true);
        config.setMaximumPoolSize(2);//包括空闲链接和正在使用的连接, 也是程序可使用的最大连接数
        config.setConnectionTimeout(5_000);// 当达到最大连接数时, getConnection()会阻塞, 该值是阻塞超时值
        config.setConnectionInitSql("select 1");
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
//        config.addDataSourceProperty("rewriteBatchedStatements",true);
//        config.addDataSourceProperty("useAffectedRows","true");

        //h2
        config.setJdbcUrl("jdbc:h2:mem:h2-memory");
        //mysql
        if ("mysql".equals(dbname)) {
            config.setJdbcUrl("jdbc:mysql://localhost:3306/test?serverTimezone=UTC&characterEncoding=utf-8&useAffectedRows=true");
            config.setUsername("root");
        }
        //pgsql
        if ("pgsql".equals(dbname)) {
            config.setJdbcUrl("jdbc:postgresql://10.1.1.203:5432/testdb");
            config.setUsername("zero");
            config.setPassword("123456");
        }

        Sql.config().setDataSource(new HikariDataSource(config));

        Sql.use(conn -> {
            URL resource = SqlConnectionTest.class.getResource("/init_" + dbname + ".sql");
            ScriptRunner runner = new ScriptRunner(true, true);
            runner.runScript(conn.connection(), resource);
        });
    }

    @Before
    public void before() {
        conn = Sql.open();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    }

    @After
    public void after() {
        deleteAllUsers();
        if (conn != null) {
            conn.close();
        }
    }

    private boolean isMySQL() {
        return "mysql".equals(dbname);
    }


    ////////////////////////////////////ORM////////////////////////////////////////////////////////////////

    private User newUser() {
        return new User("May", "may@xxx.com", "123456");
    }

    private User insertUser() {
        User user = new User("May", "may@xxx.com", "123456");
        user.password_salt = "***";
        conn.insert(user, "users");
        return user;
    }

    private void deleteAllUsers() {
        conn.executeUpdate("delete from users");
    }

    @Test
    public void testInsertBean() {
        conn.setAutoCommit(false);
        insertUser();
    }

    @Test
    public void testInsertIgnoreBean() {
        if (isMySQL()) {
            User user = newUser();
            user.id = 1;
            conn.insert(user, "users");
            conn.insertIgnore(user, "users");
        }
    }

    @Test
    public void testLoad() { // Single primary key
        User2 user = new User2("May", "may@xxx.com", "123456");
        user.passwordSalt = "***";
        user.id = 123456;
        conn.insert(user, "users");
        User2 _user = conn.tryGet(User2::new, 123456);
        Assert.assertNotNull(_user);
        Assert.assertNotNull(_user.passwordSalt);
    }

    @Test
    public void testReload() { // Single primary key OR Composite primary key
        User2 user = new User2("May", "may@xxx.com", "123456");
        user.passwordSalt = "***";
        user.id = 123456;
        conn.insert(user, "users");

        User2 tmp = new User2();
        tmp.id = user.id;
        conn.reload(tmp);
        Assert.assertNotNull(tmp.password);
    }


    @Test
    public void testUpdateBean() {
        User2 user = new User2("May", "may@xxx.com", "123456");
        user.passwordSalt = "***";
        user.id = 8888888;
        conn.insert(user, "users");
        String newPassword = ThreadLocalRandom.current().nextInt() + "@";
        user.password = newPassword;
        conn.update(user, "password");
        User2 user2 = conn.query("select * from users where password=?", newPassword).first(User2.class);
        Assert.assertNotNull(user2);
    }

    @Test
    public void testDeleteBean() {
        User2 user = new User2("May", "may@xxx.com", "123456");
        user.passwordSalt = "***";
        user.id = 123456;
        conn.insert(user, "users");
        Assert.assertTrue(conn.delete(user, "users"));
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void testQuery() {
        User user = conn.query("select * from users where username =?", "Frank").first(User::new);
        Assert.assertNull(user);
    }

    @Test
    public void testQueryBean() {
//        User user = conn.query(User.class, where -> {
//        }).first(User::new);
//        Assert.assertNull(user);
    }

    @Test
    public void testQuery1() {
        insertUser();
        User user = conn.query("select * from users where username=?", "May").first(User.MAPPER);
        Assert.assertNotNull(user);
    }

    @Test
    public void testQuery2() {
        insertUser();
        Row row = conn.query("select * from users where username=?", "May").first();
        Assert.assertNotNull(row.getValue("username"));
        Assert.assertNotNull(row.getValue("email"));
        Assert.assertNull(row.getValue("xxx"));
        Optional<String> xxx = row.getOptValue("xxx");
        Assert.assertFalse(xxx.isPresent());
    }


    @Test
    public void testQuery3() {
        insertUser();
        String sql = "select * from users where username=?";
        // 如果User中有单个参数的构造器, 会导致冲突
        //select list
        List<User> users1 = conn.query(sql, "May").list(User.MAPPER);
        Assert.assertTrue(users1.size() > 0);
        List<User> users2 = conn.query(sql, "May").list(User::new);
        Assert.assertTrue(users2.size() > 0);

        //select one
        User frank = conn.query(sql, "Frank").first(User.MAPPER);
        Assert.assertNull(frank);
    }

    @Test
    public void testQuery4() {
        String username = "Frank";
        //simple
        conn.query("select * from users where username=?", username).forEach(row -> {
            Assert.assertEquals("frank@xxx.com", row.get("email"));
            Assert.assertNull(row.get("unk"));
        });

        //use mapper
        conn.query("select * from users where username=?", username).list(User.MAPPER).forEach(user -> {
            Assert.assertEquals("frank@xxx.com", user.email);
        });

        //RowHandler (big data RowHandler)
        conn.query(Sql.create("select * from users where username=?", username), row -> {
            Assert.assertEquals("frank@xxx.com", row.getString("email"));
            return true;
        });
    }

    @Test
    public void testQuery5() {
//        conn.query(sql -> sql.select(""));
    }

    @Test
    public void testQueryResultSet() {
        conn.query(Sql.create("select * from users where username=?", "Frank"), rs -> {
            try {
                String name = rs.getString("username");
                Assert.assertEquals("Frank", name);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    @Test
    public void testFirstExample() {
        User2 user = new User2("Zero", "zero@xxx.com", "123456");
        user.id = 1;
        conn.insert(user);
        User2 t1 = conn.firstExample(user);
        Assert.assertNotNull(t1);
        User2 t2 = conn.firstExample(new User2("Zero", null, null));
        Assert.assertNotNull(t2);
        User2 t3 = conn.firstExample(new User2(null, "zero@xxx.com", null));
        Assert.assertNotNull(t3);
        User2 t4 = conn.firstExample(new User2(null, null, "123456"));
        Assert.assertNotNull(t4);
        User2 t5 = conn.firstExample(new User2(1));
        Assert.assertNotNull(t5);
    }

    @Test
    public void testSqlBuilder() {
        insertUser();
        String username = "May";
        String email = null;
        String password = "123456";
        User user = conn.query(sql -> {
            sql.sql("select * from users");
            sql.where(where -> {
                where.and(username != null, "username =?", username);//if username has value, use
                where.and("password =?", password);
//                where.and("email =?", email);//IS NULL, NOT NULL
//                where.or("age =?", 18);
            });
            System.out.println(sql);
        }).first(User.MAPPER);
        Assert.assertNotNull(user);
    }

    @Test
    public void testInsert() {
        conn.insert("users", "username,password,password_salt", "may", "123456", "xxx");
    }


    @Test
    public void testInsertMap1() {
        Map<String, Object> map = new HashMap<>();
        map.put("username", "Zero");
        map.put("password", "123456");
        map.put("email", "zero@xxx.com");
        conn.insertMap("users", map);
    }

    @Test
    public void testInsertMap2() {
        Map<String, Object> map = new HashMap<>();
        map.put("username", "Zero");
        map.put("password", "123456");
        map.put("email", "zero@xxx.com");
        conn.insertMap("users", map, "id");
        //{id=6, password=123456, email=zero@xxx.com, username=Zero}
        System.out.println(map);
        Assert.assertNotNull(map.get("id"));
    }

    @Test
    public void testInsertMap3() {
        conn.insertMap("users", map -> {
            map.put("username", "Zero");
            map.put("password", "123456");
            map.put("email", "zero@xxx.com");
        });
    }

    @Test
    public void testBatchInsert1() {
        conn.batchUpdate("INSERT INTO users (email, username)  VALUES (?, ?)", executor -> {
            executor.exec("bb@example.com", "bb");
            executor.exec("aa@example.com", "aa");
        });
    }

    @Test
    public void testBatchInsert2() {
        conn.batchInsert("users", "email, username", executor -> {
            executor.exec("bb@example.com", "bb");
            executor.exec("aa@example.com", "aa");
        });
    }

    @Test
    public void testBatchInsert3() {
        List<User> users = new ArrayList<>();
        int size = 20000;
        for (int i = 0; i < size; i++) {
            users.add(new User("zero" + i, "zero@xxx.com", "123456"));
        }
        BatchUpdateResult result = conn.batchUpdate("INSERT /*IGNORE*/ INTO users (email, username)  VALUES (?, ?)", 1000, users, (executor, user) -> {
            executor.exec(user.email, user.username);
        });
        if (result.hasSuccessNoInfo()) {
            Assert.assertEquals(result.successNoInfoCount, size);
        } else {
            Assert.assertEquals(result.affectedRows, size);
        }
    }

    @Test
    public void testBatchInsert4() throws SQLException {
        List<User> users = new ArrayList<>();
        int size = 200;
        for (int i = 0; i < size; i++) {
            users.add(new User("zero" + i, "zero@xxx.com", "123456"));
        }
        Counter count = new Counter();
        PreparedStatement ps = conn.prepareStatement("INSERT /*IGNORE*/ INTO users (email, username)  VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS);
//        PreparedStatement ps = conn.prepareStatement("INSERT /*IGNORE*/ INTO users (email, username)  VALUES (?, ?)",
//                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY ,  ResultSet.CLOSE_CURSORS_AT_COMMIT);
        BatchUpdateResult result = conn.batchUpdate(ps, 10, executor -> {
            users.forEach(user -> executor.exec(user.email, user.username));
        }, (statement, rs) -> {
            try (ResultSet keys = statement.getGeneratedKeys()) {//MySQL只有自增ID才会返回
                if (keys != null) {
                    //bug: h2: Feature not supported
//                     ResultSet.TYPE_SCROLL_xxx
//                    if (keys.last()) {
//                        count.add(keys.getRow());
//                    }
                    while (keys.next()) {//Statement.RETURN_GENERATED_KEYS 才会返回, 但是很耗性能
                        int cc = keys.getMetaData().getColumnCount();
                        //MySQL: GENERATED_KEY
                        //H2: 1.4.193 返回固定名称:identity  1.4.197 返回实际列名
                        //PGSQL: 返回实际列名, 如果没有指定返回列名而是Statement.RETURN_GENERATED_KEYS, 则会返回所有列数据
                        String name = keys.getMetaData().getColumnName(1);
                        count.add(1);
                    }
                }
            } catch (SQLException e) {
                throw new SqlteException(e);
            }
        });
        ps.close();
//        conn.commit();
        System.out.println(count);
        System.out.println(result);
    }

    @Test
    public void testBatchInsert_Beans() {
        //pgsql9.x 自增的id列可以为null, pgsql10.x是不行的
        List<User> users = new ArrayList<>();
        int size = 20;
        for (int i = 0; i < size; i++) {
            User user = new User("zero" + i, "zero@xxx.com", "123456");
            user.id = i;
            user.updated_time = new Date();
            user.password_salt = "***";
            users.add(user);
        }
        conn.batchInsert(users, "users");
    }

    @Test
    public void testBatchInsert_Beans2() {
        int size = 2000;
        BatchUpdateResult result = conn.batchInsert(db -> {
            for (int i = 0; i < size; i++) {
                User user = new User("zero" + i, "zero@xxx.com", "123456");
                user.id = i;
                user.updated_time = new Date();
                db.accept(user);
            }
        }, User.class, "users");
        if (result.hasSuccessNoInfo()) {
            Assert.assertTrue(result.successNoInfoCount > 0);
        } else {
            Assert.assertEquals(size, result.affectedRows);
        }
    }

    @Test
    public void testBatchInsert_Beans_ORM() {
        int size = 200;
        BatchUpdateResult result = conn.batchInsert(db -> {
            for (int i = 0; i < size; i++) {
                OrmUser user = new OrmUser("zero" + i, "zero@xxx.com", "123456");
                user.updatedTime = LocalDateTime.now();
                db.accept(user);
            }
        }, OrmUser.class, "users");
        if (result.hasSuccessNoInfo()) {
            Assert.assertTrue(result.successNoInfoCount > 0);
        } else {
            Assert.assertEquals(size, result.affectedRows);
        }
        deleteAllUsers();
    }


    @Test
    public void testBatchUpdate_insert5() {
        List<User> users = new ArrayList<>();
        int size = 20;
        for (int i = 0; i < size; i++) {
            users.add(new User("zero" + i, "zero@xxx.com", "123456"));
        }
        BatchUpdateResult result = conn.batchUpdate("INSERT INTO users (email, username)  VALUES (?, ?)", executor -> {
            users.forEach(user -> executor.exec(user.email, user.username));
        });
        if (result.hasSuccessNoInfo()) {
            Assert.assertTrue(result.successNoInfoCount > 0);
        } else {
            Assert.assertEquals(size, result.affectedRows);
        }
        deleteAllUsers();
    }

    @Test
    public void testBatchUpdate_insert6() {
        List<User> users = new ArrayList<>();
        int size = 20;
        for (int i = 0; i < size; i++) {
            users.add(new User("zero" + i, "zero@xxx.com", "123456"));
        }
        BatchUpdateResult result = conn.batchInsert("users", "email, username", executor -> {
            users.forEach(user -> executor.exec(user.email, user.username));
        });
        if (result.hasSuccessNoInfo()) {
            Assert.assertTrue(result.successNoInfoCount > 0);
        } else {
            Assert.assertEquals(size, result.affectedRows);
        }
        deleteAllUsers();
    }

    @Test
    public void testBatchUpdate_insert_pgsql() {
        if (dbname.equals("pgsql")) {
            List<User> users = new ArrayList<>();
            int size = 200;
            for (int i = 0; i < size; i++) {
                users.add(new User("zero" + i, "zero@xxx.com", "123456"));
            }
            String sql = "INSERT INTO users (email, username)  VALUES (?, ?) ON CONFLICT (username) DO NOTHING";
            BatchUpdateResult result = conn.batchUpdate(sql, 10, users, (executor, user) -> {
                executor.exec(user.email, user.username);
            });
            if (result.hasSuccessNoInfo()) {
                Assert.assertEquals(result.successNoInfoCount, size);
            } else {
                Assert.assertEquals(result.affectedRows, size);
            }
        }
    }


    @Test
    public void testUpdate2() {
        Row data = new Row().set("username", "Zero").set("email", "bb@example.com");
        conn.insertMap("users", data, "id");
        int update = conn.update("users", data.set("username", "zero1"), where -> {
            where.and("id=?", data.get("id"));
        });
        Assert.assertEquals(1, update);
        update = conn.update("users", row -> {
            row.set("username", "zero00").set("email", "zero@example.com");
        }, where -> {
            where.and("id=?", data.get("id"));
        });
        Assert.assertEquals(1, update);
        //OR
        update = conn.updateByPks("users", data.set("username", "zero2"), "id");
        Assert.assertEquals(1, update);
        //OR
        update = conn.updateByPks("users", data.set("username", "zero3"));// pk default: id
        Assert.assertEquals(1, update);
    }

    @Test
    public void testUpdate3() {
        OrmUser user = new OrmUser("May", "may@xxx.com", "123456");
        conn.insert(user, "users");
        user.username = "My new name";
        user.email = null;
        conn.update(user, "username", true);
        OrmUser _user = conn.tryGet(OrmUser::new, user.id);
        Assert.assertEquals(_user.username, user.username);
        Assert.assertNotNull(_user.email);
    }

    public void testUpdate2_2() {
        conn.update("", "");
        conn.update("", "", true);
        conn.update("qw", "", true);
    }

    ///////////////////////////////////////////Use Sql Provider////////////////////////////////////////////////////
    @Test
    public void testExecuteExternalSql() {
        String first = conn.query("#all").first(RowMapper.STRING);
        System.out.println(first);
    }

    @Test
    public void testExecuteExternalSql2() {
        User first = conn.query("#user.login", "zero", "123456").first(User::new);
        Assert.assertNull(first);
    }

    @Test
    public void LocalDateTime() {
        OrmUser user = new OrmUser("May", "may@xxx.com", "123456");
        user.updatedTime = LocalDateTime.now();
        user.passwordSalt = OrmUser.PasswordSalt.B123456;
        conn.insert(user, "users");
        OrmUser user3 = conn.query("select * from users where email=?", user.email).first(OrmUser::new);
        Assert.assertEquals(user3.passwordSalt, OrmUser.PasswordSalt.B123456);
    }

    @Test
    public void testLocalDate() {
        int i = conn.insertMap("users", row -> {
            row.put("username", "QQ");
            row.put("email", "qq@qq.com");
            row.put("password", "123456");
            row.put("password_salt", "999");
            row.put("updated_time", LocalDateTime.of(2019, 12, 31, 12, 5));
        });
//        conn.commit();
        Assert.assertEquals(1, i);
        LocalDate from = LocalDate.of(2019, 12, 25);
        LocalDate to = LocalDate.of(2020, 1, 1);
        Assert.assertFalse(conn.query("select * from users where updated_time BETWEEN ? AND ?", from, to).isEmpty());
    }

    @Test
    public void testTransaction() {
        SqlConnection[] ss = new SqlConnection[1];
        Sql.transaction(conn -> {
            ss[0] = conn;
            System.out.println(conn.connection().hashCode());
            System.out.println(ss[0].getAutoCommit());
            return true;
        });
        for (int i = 0; i < 5; i++) {
            SqlConnection conn = Sql.open();
            System.out.println(conn.connection().hashCode());
            System.out.println(conn.getAutoCommit());
            conn.close();
        }
    }

    //    @Test
    public void testTransaction2() {
        CompletableFuture.runAsync(() -> {
            Sql.transaction(conn -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            });
        });
        for (int i = 0; i < 5; i++) {
            System.out.println("正在获取连接: " + LocalDateTime.now());
            try (SqlConnection conn = Sql.open()) {
                System.out.println("获取连接: " + LocalDateTime.now());
                System.out.println(conn.connection().hashCode());
                System.out.println(conn.getAutoCommit());
            }
        }
    }
}