package com.rojas.fastcash.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.sql.Date;
import java.util.*;

@Service
public class ReporteService {

    @Autowired private JdbcTemplate jdbcTemplate;

    // 1. REPORTE GENERAL DE VENTAS
    public List<Map<String, Object>> obtenerReporteVentas(String inicio, String fin, Integer usuarioID) {
        if (inicio == null || inicio.isEmpty()) inicio = LocalDate.now().toString();
        if (fin == null || fin.isEmpty()) fin = LocalDate.now().toString();
        Integer uidParam = (usuarioID != null && usuarioID > 0) ? usuarioID : null;

        // POSTGRES: SELECT * FROM function(...)
        // SE AGREGA cliente2.
        String sql = "SELECT * FROM cliente2.sp_reporte_detalladoventas(?, ?, ?, NULL)";
        return jdbcTemplate.queryForList(sql, Date.valueOf(inicio), Date.valueOf(fin), uidParam);
    }

    // 2. REPORTE POR CAJAS
    public List<Map<String, Object>> obtenerReporteCajas(String inicio, String fin, Integer usuarioID) {
        if (inicio == null || inicio.isEmpty()) inicio = LocalDate.now().toString();
        if (fin == null || fin.isEmpty()) fin = LocalDate.now().toString();

        // POSTGRES: SELECT * FROM function(...)
        // SE AGREGA cliente2.
        return jdbcTemplate.queryForList("SELECT * FROM cliente2.sp_reporte_porcaja(?, ?, ?)", Date.valueOf(inicio), Date.valueOf(fin), usuarioID);
    }

    // 3. DASHBOARD DE GRÁFICOS (Compatible Postgres)
    public Map<String, Object> obtenerDatosGraficos(String fechaStr, Integer usuarioID) {
        String fechaFinal = (fechaStr == null || fechaStr.isEmpty()) ? LocalDate.now().toString() : fechaStr;
        Map<String, Object> resultado = new HashMap<>();
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(fechaFinal));

        String filtroUsuario = "";
        if(usuarioID != null && usuarioID > 0) {
            filtroUsuario = " AND v.UsuarioID = ? ";
            params.add(usuarioID);
        }

        // SQL CORREGIDO: COALESCE y TO_CHAR
        // SE AGREGA cliente2. A LAS TABLAS
        String sqlCat = "SELECT COALESCE(c.Nombre, 'Sin Categoría') as label, COALESCE(SUM(vd.Monto), 0) as value " +
                        "FROM cliente2.Ventas v " +
                        "LEFT JOIN cliente2.VentaDetalle vd ON v.VentaID = vd.VentaID " +
                        "LEFT JOIN cliente2.CategoriasVenta c ON vd.CategoriaID = c.CategoriaID " +
                        "WHERE TO_CHAR(v.FechaEmision, 'YYYY-MM-DD') = TO_CHAR(?::date, 'YYYY-MM-DD') " +
                        "  AND v.Estado IN ('PAGADO', 'COMPLETADO') " + filtroUsuario + "GROUP BY c.Nombre";

        // SE AGREGA cliente2. A LAS TABLAS
        String sqlPago = "SELECT COALESCE(p.FormaPago, 'Sin Pago') as label, COALESCE(SUM(p.MontoPagado), 0) as value " +
                         "FROM cliente2.Ventas v LEFT JOIN cliente2.PagosRegistrados p ON v.VentaID = p.VentaID " +
                         "WHERE TO_CHAR(v.FechaEmision, 'YYYY-MM-DD') = TO_CHAR(?::date, 'YYYY-MM-DD') " +
                         "  AND v.Estado IN ('PAGADO', 'COMPLETADO') " + filtroUsuario + "GROUP BY p.FormaPago";

        resultado.put("categorias", forzarMinusculas(jdbcTemplate.queryForList(sqlCat, params.toArray())));
        resultado.put("pagos", forzarMinusculas(jdbcTemplate.queryForList(sqlPago, params.toArray())));
        return resultado;
    }

    private List<Map<String, Object>> forzarMinusculas(List<Map<String, Object>> listaOriginal) {
        List<Map<String, Object>> listaLimpia = new ArrayList<>();
        for (Map<String, Object> fila : listaOriginal) {
            Map<String, Object> mapaLimpio = new HashMap<>();
            fila.forEach((k, v) -> {
                if (k.equalsIgnoreCase("label")) mapaLimpio.put("label", v);
                if (k.equalsIgnoreCase("value")) mapaLimpio.put("value", v);
            });
            if (!mapaLimpio.isEmpty()) listaLimpia.add(mapaLimpio);
        }
        return listaLimpia;
    }

    // 4. OBTENER DATOS DE CIERRE ACTUAL (Arqueo)
    public Map<String, Object> obtenerCierreActual(Integer usuarioID) {
        try {
            // POSTGRES: SELECT * FROM function(...)
            // SE AGREGA cliente2.
            return jdbcTemplate.queryForMap("SELECT * FROM cliente2.sp_operacion_obtenercierreactual(?)", usuarioID);
        } catch (Exception e) {
            // En caso de que no haya caja abierta, devolvemos el esquema limpio
            Map<String, Object> vacio = new HashMap<>();
            vacio.put("VentasQR", 0);
            vacio.put("VentasTransferencia", 0);
            vacio.put("VentasTarjeta", 0);
            vacio.put("TotalVendido", 0);
            vacio.put("TurnoNombre", "GENERAL");
            return vacio;
        }
    }

// 5. OBTENER DETALLE DE TRANSACCIONES DEL CIERRE ACTUAL (INCLUYENDO TITULAR)
    public List<Map<String, Object>> obtenerDetalleCierreActual(Integer usuarioID) {
        // SE AGREGA cliente2. A TODAS LAS TABLAS
        String sql = "SELECT v.fechaemision, p.formapago, " +
                     "COALESCE(e.nombre, '-') AS entidadbancaria, " +
                     "CASE " +
                     "    WHEN p.numerooperacion IS NOT NULL AND p.numerooperacion <> '' THEN p.numerooperacion " +
                     "    WHEN p.lotetarjeta IS NOT NULL AND p.lotetarjeta <> '' THEN 'Lote: ' || p.lotetarjeta " +
                     "    WHEN p.ultimos4digitos IS NOT NULL AND p.ultimos4digitos <> '' THEN '***' || p.ultimos4digitos " +
                     "    ELSE '-' " +
                     "END AS numerooperacion, " +
                     // NUEVO CAMPO: Traemos el titular de la cuenta
                     "COALESCE(p.nombretitular, '-') AS titular, " +
                     "p.montopagado " +
                     "FROM cliente2.ventas v " +
                     "JOIN cliente2.pagosregistrados p ON v.ventaid = p.ventaid " +
                     "LEFT JOIN cliente2.entidadesfinancieras e ON p.entidadfinancieraid = e.entidadid " +
                     "JOIN cliente2.sesionescaja sc ON sc.usuarioid = v.usuarioid AND sc.estado = 'ABIERTO' " +
                     "WHERE v.usuarioid = ? AND v.estado IN ('PAGADO', 'COMPLETADO') " +
                     "AND v.fechaemision >= sc.fechainicio " +
                     "ORDER BY v.fechaemision ASC";

        try {
            return jdbcTemplate.queryForList(sql, usuarioID);
        } catch (Exception e) {
            System.err.println("❌ Error obteniendo detalle de caja: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}