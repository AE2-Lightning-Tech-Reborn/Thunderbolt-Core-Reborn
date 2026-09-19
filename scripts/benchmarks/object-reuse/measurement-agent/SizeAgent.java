package bench;
import java.lang.instrument.Instrumentation;
public final class SizeAgent {
 private static Instrumentation instrumentation;
 public static void premain(String arguments, Instrumentation value) { instrumentation = value; }
 public static long sizeOf(Object value) { return instrumentation.getObjectSize(value); }
}
