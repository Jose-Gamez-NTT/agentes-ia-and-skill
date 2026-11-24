package es.asisa.jasper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeQueryServiceTest {

    @Test
    void previewCompilesJoinGroupAndLimit() throws Exception {
        String json = "{" +
                "\"select\":[" +
                "  {\"column\":\"f.COD_CLIENTE\",\"as\":\"cliente\"}," +
                "  {\"expr\":{\"func\":\"COUNT\",\"star\":true},\"as\":\"total\"}" +
                "]," +
                "\"from\":{\"table\":\"TFACTURA\",\"alias\":\"f\"}," +
                "\"joins\":[{" +
                "  \"type\":\"left\"," +
                "  \"table\":\"TCLIENTE\"," +
                "  \"alias\":\"c\"," +
                "  \"on\":[{" +
                "    \"left\":{\"column\":\"c.ID\"}," +
                "    \"op\":\"eq\"," +
                "    \"right\":{\"column\":\"f.ID_CLIENTE\"}" +
                "  }]" +
                "}]," +
                "\"where\":{\"and\":[{" +
                "  \"left\":{\"column\":\"f.FECHA\"}," +
                "  \"op\":\"gte\"," +
                "  \"right\":{\"param\":\"fechaDesde\"}" +
                "},{" +
                "  \"left\":{\"column\":\"f.ESTADO\"}," +
                "  \"op\":\"eq\"," +
                "  \"right\":{\"value\":\"A\"}" +
                "}]}," +
                "\"groupBy\":[{\"column\":\"f.COD_CLIENTE\"}]," +
                "\"orderBy\":[{\"column\":\"cliente\",\"dir\":\"asc\"}]," +
                "\"limit\":50," +
                "\"params\":{\"fechaDesde\":\"2025-01-01\"}" +
                "}";

        SafeQueryService.PreviewResponse preview = SafeQueryService.preview(json);

        assertThat(preview.sql).contains("SELECT f.COD_CLIENTE AS cliente, COUNT(*) AS total");
        assertThat(preview.sql).contains("LEFT JOIN TCLIENTE c ON (c.ID = f.ID_CLIENTE)");
        assertThat(preview.sql).contains("GROUP BY f.COD_CLIENTE");
        assertThat(preview.sql).contains("ORDER BY cliente ASC");
        assertThat(preview.sql).contains("FETCH FIRST 50 ROWS ONLY");
        assertThat(preview.parameters).hasSize(2);
        assertThat(preview.parameters.get(0).name).isEqualTo("fechaDesde");
        assertThat(preview.parameters.get(1).value).isEqualTo("A");
    }

    @Test
    void previewRejectsUnsafeIdentifiers() {
        String json = "{" +
                "\"select\":[{\"column\":\"f.ID\"}]," +
                "\"from\":{\"table\":\"FACTURA;DELETE\",\"alias\":\"f\"}" +
                "}";

        assertThatThrownBy(() -> SafeQueryService.preview(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Identificador no válido");
    }

    @Test
    void previewSelectStarTrue() throws Exception {
        String json = "{" +
                "\"select\":[{\"star\":true}]," +
                "\"from\":{\"schema\":\"DBA1\",\"table\":\"TINFORME\",\"alias\":\"t\"}," +
                "\"where\":{\"left\":{\"column\":\"t.IND_ONLINE\"},\"op\":\"eq\",\"right\":{\"value\":\"N\"}}" +
                "}";

        SafeQueryService.PreviewResponse preview = SafeQueryService.preview(json);

        assertThat(preview.sql).startsWith("SELECT * FROM DBA1.TINFORME t WHERE");
        assertThat(preview.parameters).hasSize(1);
    }

    @Test
    void previewSelectStarWithAlias() throws Exception {
        String json = "{" +
                "\"select\":[{\"star\":\"t\"}]," +
                "\"from\":{\"schema\":\"DBA1\",\"table\":\"TINFORME\",\"alias\":\"t\"}" +
                "}";

        SafeQueryService.PreviewResponse preview = SafeQueryService.preview(json);

        assertThat(preview.sql).startsWith("SELECT t.* FROM DBA1.TINFORME t");
    }

    @Test
    void previewSelectStarMixedWithColumns() throws Exception {
        String json = "{" +
                "\"select\":[{\"star\":\"t\"},{\"expr\":{\"func\":\"COUNT\",\"star\":true},\"as\":\"total\"}]," +
                "\"from\":{\"table\":\"TINFORME\",\"alias\":\"t\"}" +
                "}";

        SafeQueryService.PreviewResponse preview = SafeQueryService.preview(json);

        assertThat(preview.sql).startsWith("SELECT t.*, COUNT(*) AS total FROM TINFORME t");
    }

    @Test
    void previewSelectStarRejectsInjectionInAlias() {
        String json = "{" +
                "\"select\":[{\"star\":\"bad;alias\"}]," +
                "\"from\":{\"table\":\"TINFORME\",\"alias\":\"t\"}" +
                "}";

        assertThatThrownBy(() -> SafeQueryService.preview(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Identificador no válido");
    }

    @Test
    void previewSelectStarFalseIsRejected() {
        String json = "{" +
                "\"select\":[{\"star\":false}]," +
                "\"from\":{\"table\":\"TINFORME\",\"alias\":\"t\"}" +
                "}";

        assertThatThrownBy(() -> SafeQueryService.preview(json))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
