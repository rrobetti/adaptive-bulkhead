package io.github.rrobetti.adaptivebulkhead;

public interface Permit extends AutoCloseable {

    String bulkheadName();

    boolean isReleased();

    @Override
    void close();
}
