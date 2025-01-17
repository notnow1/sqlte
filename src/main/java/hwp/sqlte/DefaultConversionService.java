package hwp.sqlte;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class DefaultConversionService implements ConversionService {

    public static final ConversionService INSTANCE = new DefaultConversionService();

    private Map<Class<?>, Map<Class<?>, TypeConverter<Object, Object>>> map = new HashMap<>();

    public DefaultConversionService() {
        //String to X
        register(String.class, Character.class, new StringToCharacterConverter());
        register(String.class, Byte.class, Byte::valueOf);
        register(String.class, Byte.TYPE, Byte::parseByte);
        register(String.class, Short.class, Short::valueOf);
        register(String.class, Short.TYPE, Short::parseShort);
        register(String.class, Integer.class, Integer::valueOf);
        register(String.class, Integer.TYPE, Integer::parseInt);
        register(String.class, Float.class, Float::valueOf);
        register(String.class, Float.TYPE, Float::parseFloat);
        register(String.class, Double.class, Double::valueOf);
        register(String.class, Double.TYPE, Double::parseDouble);
        register(String.class, Long.class, Long::valueOf);
        register(String.class, Long.TYPE, Long::parseLong);
        register(String.class, BigInteger.class, BigInteger::new);
        register(String.class, BigDecimal.class, BigDecimal::new);
        register(String.class, UUID.class, new StringToUUIDConverter());
        register(String.class, Boolean.class, new StringToBooleanConverter());
        register(String.class, Boolean.TYPE, new StringToBooleanConverter());

        //to String
        register(Object.class, String.class, Object::toString);

        // Boolean
        register(Boolean.class, String.class, Object::toString);
        register(Boolean.class, Number.class, b -> b ? 1 : 0);
        register(Boolean.class, Boolean.TYPE, b -> b);
        register(Boolean.class, Integer.class, b -> b ? 1 : 0);
        register(Boolean.class, Integer.TYPE, b -> b ? 1 : 0);
        register(Boolean.class, Long.class, b -> b ? 1L : 0L);
        register(Boolean.class, Long.TYPE, b -> b ? 1L : 0L);
        register(Boolean.class, Byte.class, b -> b ? (byte) 1 : (byte) 0);
        register(Boolean.class, Byte.TYPE, b -> b ? (byte) 1 : (byte) 0);


        // number
        register(Byte.class, String.class, Object::toString);
        register(Short.class, String.class, Object::toString);
        register(Short.class, Boolean.class, v -> v > 0);
        register(Integer.class, String.class, Object::toString);
        register(Integer.class, Boolean.class, v -> v > 0);
        register(Long.class, String.class, Object::toString);
        register(Long.class, Boolean.class, v -> v > 0);
        register(Float.class, String.class, Object::toString);
        register(Double.class, String.class, Object::toString);

        Class<?>[] numbers = new Class<?>[]{
                Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE,
                Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
                BigDecimal.class, BigInteger.class, AtomicLong.class, AtomicInteger.class, LongAdder.class
        };
        for (Class<?> f : numbers) {
            for (Class<?> t : numbers) {
                register(f, t, new NumberConverter(t));
            }
        }

        // date & time
        TimeZone timeZone = Config.getConfig().getDatabaseTimeZone();
//            ZoneOffset zoneOffset = OffsetDateTime.now().getOffset();

        register(Timestamp.class, String.class, timestamp -> timestamp.toInstant().toString());
        register(Timestamp.class, Long.class, Timestamp::getTime);
        register(Timestamp.class, LocalDateTime.class, Timestamp::toLocalDateTime);
        register(Timestamp.class, LocalDate.class, timestamp -> timestamp.toLocalDateTime().toLocalDate());
        register(Timestamp.class, java.util.Date.class, timestamp -> timestamp);
        register(Timestamp.class, Instant.class, timestamp -> Instant.ofEpochMilli(timestamp.getTime()));

        register(Instant.class, Timestamp.class, Timestamp::from);

        register(Date.class, String.class, Date::toString);
        register(Date.class, Instant.class, Date::toInstant);
        register(Date.class, Long.class, java.util.Date::getTime);
        register(Date.class, LocalDate.class, Date::toLocalDate);
        register(Date.class, LocalDateTime.class, date -> date.toLocalDate().atTime(LocalTime.MIN));

        register(Time.class, String.class, Time::toString);
        register(Time.class, LocalTime.class, Time::toLocalTime);
        register(Time.class, Instant.class, Time::toInstant);

        register(LocalDateTime.class, Timestamp.class, Timestamp::valueOf);
        register(LocalDateTime.class, Date.class, dateTime -> Date.valueOf(dateTime.toLocalDate()));
        register(LocalDateTime.class, Long.class, dateTime -> dateTime.toEpochSecond(ZoneOffset.UTC) * 1000L);
        register(LocalDateTime.class, String.class, LocalDateTime::toString);
        register(LocalDateTime.class, java.util.Date.class, dateTime -> {
            Instant instant = dateTime.atZone(timeZone.toZoneId()).toInstant();
            return java.util.Date.from(instant);
        });

        register(LocalDate.class, String.class, LocalDate::toString);
        register(LocalDate.class, Long.class, date -> date.atStartOfDay(timeZone.toZoneId()).getSecond() * 1000L);
        register(LocalDate.class, Integer.class, date -> date.atStartOfDay(timeZone.toZoneId()).getSecond());
        register(LocalDate.class, java.util.Date.class, date -> {
            Instant instant = date.atStartOfDay(timeZone.toZoneId()).toInstant();
            return java.util.Date.from(instant);
        });

        register(OffsetDateTime.class, String.class, OffsetDateTime::toString);
        register(OffsetDateTime.class, Date.class, dateTime -> Date.valueOf(dateTime.toLocalDate()));
        register(OffsetDateTime.class, java.util.Date.class, dateTime -> java.util.Date.from(dateTime.toZonedDateTime().toInstant()));
        register(OffsetDateTime.class, Long.class, dateTime -> dateTime.toZonedDateTime().toInstant().getEpochSecond() * 1000L);

        register(ZonedDateTime.class, String.class, ZonedDateTime::toString);
        register(ZonedDateTime.class, Date.class, dateTime -> new Date(dateTime.toEpochSecond() * 1000L));
        register(ZonedDateTime.class, java.util.Date.class, dateTime -> java.util.Date.from(dateTime.toInstant()));
        register(ZonedDateTime.class, Long.class, dateTime -> dateTime.toEpochSecond() * 1000L);

    }

    @Override
    public boolean canConvert(Class<?> from, Class<?> to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (to.isAssignableFrom(from)) {
            return true;
        }
        if (Integer.class == from && to.isEnum()) {
            return true;
        }
        if (from.isEnum() && Integer.class == to) {
            return true;
        }
        if (String.class == from && to.isEnum()) {
            return true;
        }
        if (from.isEnum() && String.class == to) {
            return true;
        }
        Map<Class<?>, TypeConverter<Object, Object>> map1 = map.get(from);
        if (map1 == null) {
            return false;
        }
        return map1.containsKey(to);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T convert(Object from, Class<T> to) {
        if (from == null) return null;
        Objects.requireNonNull(to);
        // 如果是继承关系
        if (to.isAssignableFrom(from.getClass())) {
            return to.cast(from);
        }
        // 非继承关系
        if (from instanceof Integer && to.isEnum()) {
            return to.getEnumConstants()[(Integer) from];
        }

        if (from instanceof Enum && Integer.class == to) {
            return (T) Integer.valueOf(((Enum) from).ordinal());
        }
        if (from instanceof String && to.isEnum()) {
            String _from = (String) from;
            if (_from.isEmpty()) {
                return null;
            }
            T[] items = to.getEnumConstants();
            for (T item : items) {
                Enum e = (Enum) item;
                if (e.name().equalsIgnoreCase(_from)) {
                    return item;
                }
            }
            return (T) Enum.valueOf((Class) to, _from.toUpperCase());
        }
        if (from instanceof Enum && String.class == to) {
            return (T) ((Enum) from).name();
        }
        Map<Class<?>, TypeConverter<Object, Object>> map1 = map.get(from.getClass());
        if (map1 == null) {
            throw new IllegalArgumentException("Conversion not supported: " + from.getClass() + " -> " + to);
        }
        TypeConverter<Object, Object> converter = map1.get(to);
        if (converter != null) {
            return (T) converter.convert(from);
        }
        return (T) from;
    }


    @Override
    public <F, T> void register(Class<F> from, Class<T> to, TypeConverter<F, T> converter) {
        Map<Class<?>, TypeConverter<Object, Object>> map1 = map.computeIfAbsent(from, k -> new HashMap<>());
        map1.put(to, (TypeConverter<Object, Object>) converter);
    }

    private static class StringToCharacterConverter implements TypeConverter<String, Character> {
        public Character convert(String source) {
            if (source.length() == 0) {
                return null;
            } else if (source.length() > 1) {
                throw new IllegalArgumentException("Can only convert a [String] with length of 1 to a [Character]; string value '" + source + "'  has length of " + source.length());
            } else {
                return source.charAt(0);
            }
        }
    }

    private static class StringToUUIDConverter implements TypeConverter<String, UUID> {
        public UUID convert(String source) {
            return source.length() > 0 ? UUID.fromString(source.trim()) : null;
        }
    }

    private static class StringToBooleanConverter implements TypeConverter<String, Boolean> {
        private final List<String> any = Arrays.asList("yes", "on", "true", "y", "1");

        public Boolean convert(String source) {
            if (source == null || source.isEmpty()) {
                return null;
            }
            return any.contains(source.toLowerCase());
        }
    }

    @SuppressWarnings("unchecked")
    private static class NumberConverter<F extends Number, T extends Number> implements TypeConverter<F, T> {

        private final Class<T> toClass;

        NumberConverter(Class<T> toClass) {
            this.toClass = toClass;
        }

        public T convert(F o) {
            if (o == null) {
                return null;
            }
            if (toClass.isInstance(o)) {
                return (T) o;
            }
            if (toClass == Integer.TYPE || toClass == Integer.class) {
//                return toClass.cast(o.intValue());// Integer 不能转换为 int
                return (T) (Integer.valueOf(o.intValue()));
            }
            if (toClass == Long.TYPE || toClass == Long.class) {
                return (T) (Long.valueOf(o.longValue()));
            }
            if (toClass == Short.TYPE || toClass == Short.class) {
                return (T) (Short.valueOf(o.shortValue()));
            }
            if (toClass == Float.TYPE || toClass == Float.class) {
                return (T) (Float.valueOf(o.floatValue()));
            }
            if (toClass == Double.TYPE || toClass == Double.class) {
                return (T) (Double.valueOf(o.doubleValue()));
            }
            if (toClass == Byte.TYPE || toClass == Byte.class) {
                return (T) (Byte.valueOf(o.byteValue()));
            }
            if (toClass == BigDecimal.class) {
                return (T) new BigDecimal(o.toString());
            }
            if (toClass == BigInteger.class) {
                return (T) BigInteger.valueOf(o.longValue());
            }
            if (toClass == AtomicLong.class) {
                return (T) new AtomicLong(o.longValue());
            }
            if (toClass == AtomicInteger.class) {
                return (T) new AtomicInteger(o.intValue());
            }
            if (toClass == LongAdder.class) {
                LongAdder adder = new LongAdder();
                adder.add(o.longValue());
                return (T) adder;
            }
            return (T) o;
        }
    }
}