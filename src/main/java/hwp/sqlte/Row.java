package hwp.sqlte;


import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * @author Zero
 * Created on 2017/3/20.
 */
public class Row extends HashMap<String, Object> {

    public String getString(String name) {
        return (String) get(name);
    }

    public Number getNumber(String name) {
        return (Number) get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue(String name, T defValue) {
        T v = (T) super.get(name);
        return v == null ? defValue : v;
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue(String name) {
        return (T) super.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue(String name, Class<T> tClass) {
        Object value = get(name);
        if (value == null) return null;
        if (value.getClass() == tClass || tClass.isInstance(value)) {
            return (T) value;
        } else {
            ConversionService conversionService = Config.getConfig().getConversionService();
            return conversionService.convert(value, tClass);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptValue(String name) {
        return Optional.ofNullable((T) super.get(name));
    }

    public <T> T map(RowMapper<T> mapper) {
        return mapper.map(this);
    }


    public <T> T map(Supplier<T> supplier) {
        return copyTo(supplier.get());
    }

    public <T> T map(Class<T> clazz) {
        try {
            return copyTo(clazz.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new SqlteException(e);
        }
    }

    public <T> T copyTo(T bean) {
        return BeanMapper.copy(this, bean);
    }

    public Row set(String name, Object val) {
        put(name, val);
        return this;
    }

    public static Row from(ResultSet rs) {
        try {
            Row row = new Row();
            ResultSetMetaData metaData = rs.getMetaData();
            int cols = metaData.getColumnCount();
            for (int i = 1; i <= cols; i++) {
                row.put(metaData.getColumnLabel(i).intern(), rs.getObject(i));
            }
            return row;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }


}
