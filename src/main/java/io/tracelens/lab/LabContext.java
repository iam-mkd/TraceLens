package io.tracelens.lab;

/** Request-thread context is explicitly installed and removed by LabAdmissionFilter. */
public final class LabContext {
    private static final ThreadLocal<LabObservation> CURRENT = new ThreadLocal<>();
    private LabContext() { }
    public static LabObservation current() { return CURRENT.get(); }
    static void install(LabObservation value) { CURRENT.set(value); }
    static void clear() { CURRENT.remove(); }
}
