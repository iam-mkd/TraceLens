package io.tracelens.product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductRepositoryTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        meters.close();
    }

    @Test
    void failedConnectionAcquisitionDoesNotCountAsExecutedSql() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("test connection unavailable"));
        ProductRepository repository = new ProductRepository(source, meters);
        assertThatThrownBy(() -> repository.findById(1))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(meters.get("tracelens.product.sql.attempts").counter().count()).isZero();
        assertThat(meters.get("tracelens.product.sql.active").gauge().value()).isZero();
        assertThat(meters.get("tracelens.product.sql.connection.acquire").timer().count()).isEqualTo(1);
    }

    @Test
    void failedExecutionReleasesResourcesAndDecrementsActiveGauge() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("test statement failure"));
        ProductRepository repository = new ProductRepository(source, meters);
        assertThatThrownBy(() -> repository.findById(1))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(meters.get("tracelens.product.sql.attempts").counter().count()).isEqualTo(1);
        assertThat(meters.get("tracelens.product.sql.failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("tracelens.product.sql.successes").counter().count()).isZero();
        assertThat(meters.get("tracelens.product.sql.active").gauge().value()).isZero();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void emptyResultIsSuccessfulSqlAndClosesResultSet() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        assertThat(new ProductRepository(source, meters).findById(99)).isEmpty();
        assertThat(meters.get("tracelens.product.sql.successes").counter().count()).isEqualTo(1);
        verify(rows).close();
        verify(statement).setQueryTimeout(2);
    }
}
