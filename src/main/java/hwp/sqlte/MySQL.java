package hwp.sqlte;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * @author Zero
 * Created on 2019/2/20.
 */
public class MySQL {

    public static final Function<String, String> TO_INSERT_IGNORE_INTO =
            sql -> sql.replace("INSERT INTO", "INSERT IGNORE INTO");

    public static final Function<String, String> TO_REPLACE_INTO =
            sql -> sql.replace("INSERT INTO", "REPLACE INTO");

    public static BiConsumer<PreparedStatement, int[]> newGKAdder(LongAdder adder) {
        return (ps, ints) -> {
            try {
                ResultSet keys = ps.getGeneratedKeys();//MySQL只有自增ID才会返回
                if (keys != null) {
                    if (keys.last()) {
                        adder.add(keys.getRow());
                    }
                }
            } catch (SQLException e) {//如果不支持滚动会报异常
                throw new UncheckedSQLException(e);
            }
        };
    }
}
