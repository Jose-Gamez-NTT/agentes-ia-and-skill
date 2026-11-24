package es.asisa.jasper;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyJdbcTest {

    @Test
    void allowsSelectQueries() throws Exception {
        try (Connection raw = DriverManager.getConnection("jdbc:h2:mem:ro_select;DB_CLOSE_DELAY=-1");
             Statement setup = raw.createStatement()) {
            setup.execute("create table T(ID int)");
            setup.execute("insert into T(ID) values (1)");

            Connection readOnly = ReadOnlyJdbc.wrap(raw);
            try (Statement st = readOnly.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from T")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void blocksWriteStatements() throws Exception {
        try (Connection raw = DriverManager.getConnection("jdbc:h2:mem:ro_write;DB_CLOSE_DELAY=-1");
             Statement setup = raw.createStatement()) {
            setup.execute("create table T(ID int)");
            Connection readOnly = ReadOnlyJdbc.wrap(raw);

            assertThatThrownBy(() -> readOnly.createStatement().execute("delete from T"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("solo se permiten consultas de lectura");
        }
    }

    @Test
    void blocksPreparedWriteStatements() throws Exception {
        try (Connection raw = DriverManager.getConnection("jdbc:h2:mem:ro_prepared;DB_CLOSE_DELAY=-1");
             Statement setup = raw.createStatement()) {
            setup.execute("create table T(ID int)");
            Connection readOnly = ReadOnlyJdbc.wrap(raw);

            assertThatThrownBy(() -> readOnly.prepareStatement("update T set ID = 2"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("solo se permiten consultas de lectura");
        }
    }
}
