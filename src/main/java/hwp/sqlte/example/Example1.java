package hwp.sqlte.example;

import hwp.sqlte.Row;
import hwp.sqlte.Sql;
import hwp.sqlte.SqlConnection;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Zero
 * Created on 2017/3/27.
 */
public class Example1 {

    public User queryExample(String username) throws Exception {
        SqlConnection conn = Sql.newConnection();
        User user = conn.query("select * from user where username =?", username).first(User::new);
        return user;
    }

    public User queryExample1(String username) throws Exception {
        SqlConnection conn = Sql.newConnection();
        Optional<User> user = conn.query("select * from user where username=?", username).first(User.MAPPER);
        if (user.isPresent()) {
            System.out.println(user.get());
            conn.query("select * from orders where user_id=?", user.get().id);
        }
        return user.orElse(null);
    }

    public Map<String, Object> queryExample2(String username) throws Exception {
        Row row = Sql.newConnection().query("select * from user where username=?", username).first();
        String name = row.getValue("username");
        int age = row.getValue("age");
        Optional<Integer> age2 = row.getOptValue("age");
        return row;
    }

    public List<User> queryExample3(String username) throws Exception {
        return Sql.newConnection().query("select * from user where username=?", username).map(User.MAPPER);
    }

    public void queryExample4(String username) throws Exception {
        //simple
        Sql.newConnection().query("select * from user where username=?", username).forEach(row -> {
            System.out.println(row.get("email"));
        });

        //use mapper
        Sql.newConnection().query("select * from user where username=?", username).map(User.MAPPER).forEach(user -> {
            System.out.println(user.email);
        });
    }

    public void queryExample5(String username) throws Exception {
        //Query big data
        Sql.newConnection().query("select * from user where username=?", rs -> {
            try {
                String name = rs.getString("username");
                System.out.println(name);
            } catch (SQLException e) {

            }
        }, username);
    }

    public User queryExample3(String username, String email, String password) throws Exception {
        SqlConnection conn = Sql.newConnection();
        Optional<User> user = conn.query(sql -> {
            sql.sql("select * from user");
            sql.where(where -> {
                where.and(username != null, "username =?", username);//if username has value, use
                where.and(email != null, "email =?", email);
                where.and("password =?", password);
            });
        }).first(User.MAPPER);
        return user.orElse(null);
    }

    //insert
    public void insertExample(String username, String email, String password) throws Exception {
        SqlConnection conn = Sql.newConnection();
        conn.insert("insert into user(username, email, password) value(?, ?, ?)", username, email, password);
    }

    public void insertExample2(String username, String email, String password) throws Exception {
        SqlConnection conn = Sql.newConnection();
        Long id = conn.insertAndReturnKey("insert into user(username, email, password) value(?, ?, ?)", username, email, password);
        System.out.println("user_id: " + id);
    }

    public void insertExample3(String username, String email, String password) throws Exception {
        SqlConnection conn = Sql.newConnection();
        User user = new User("May", "may@gmail.com", "123456");
        conn.insertBean(user);//table name: user
        conn.insertBean(user, "user");
    }


    public void updateExample() throws Exception {
        SqlConnection conn = Sql.newConnection();
        conn.update("update user set username=? where user_id=?", "Cindy", 123);
        //OR
        conn.update(builder -> {
            builder.sql("update user set username=?").args("Cindy");
            builder.where(where -> {
                where.and("user_id=?", 123);
            });
        });
    }

    public void batchUpdateExample(List<User> users) throws Exception {
        SqlConnection conn = Sql.newConnection();
        conn.batchUpdate("update user set username=?, password=? where id=?", 1000, users, (executor, user) -> {
            executor.exec(user.username, user.password);
        });

        conn.batchUpdate("update user set username=?, password=? where id=?", 1000, executor -> {
            users.forEach(user -> {
                executor.exec(user.username, user.password);
            });
        });
    }

    public static void main(String[] args) {
        System.out.println(BigInteger.ONE);
    }

}