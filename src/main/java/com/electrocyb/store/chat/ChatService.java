package com.electrocyb.store.chat;

import com.electrocyb.store.producto.Producto;
import com.electrocyb.store.producto.ProductoRepository;
import com.electrocyb.store.pedido.Pedido;
import com.electrocyb.store.pedido.PedidoRepository;
import com.electrocyb.store.pedido.OrderStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private final ProductoRepository productoRepository;
    private final PedidoRepository pedidoRepository;

    // patrón simple para códigos EC-000001
    private static final Pattern PEDIDO_PATTERN =
            Pattern.compile("(EC-\\d{6})", Pattern.CASE_INSENSITIVE);

    // stopwords básicas para español
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "el", "la", "los", "las", "un", "una", "unos", "unas",
            "de", "del", "al", "a", "y", "o", "u", "en", "para",
            "por", "con", "que", "es", "son", "tienen", "hay", "venden",
            "precio", "cuanto", "cuánto", "vale", "cuesta", "quiero",
            "dime", "sobre", "del", "mi", "su", "mis", "sus",
            "tienes", "tienen", "vende", "venden", "vendeis", "vendéis"
    ));

    public ChatService(
            ProductoRepository productoRepository,
            PedidoRepository pedidoRepository
    ) {
        this.productoRepository = productoRepository;
        this.pedidoRepository = pedidoRepository;
    }

    public String processMessage(ChatRequest request) {
        String msgOriginal = Optional.ofNullable(request.getMessage()).orElse("");
        String msg = msgOriginal.toLowerCase(Locale.ROOT).trim();

        // 1) Preguntas frecuentes
        String faq = handleFaq(msg);
        if (faq != null) return faq;

        // 2) Código de pedido
        String codigo = extractNumeroPedido(msg);
        if (codigo != null) return handlePedidoStatus(codigo);

        // 3) Consultas de precio (ej: "precio del multímetro")
        String respPrecio = handlePriceQuery(msg);
        if (respPrecio != null) return respPrecio;

        // 4) Búsqueda general de productos (ej: "tienen cámaras de seguridad?")
        String respProductos = handleProductSearch(msg);
        if (respProductos != null) return respProductos;

        // 5) Respuesta por defecto
        return """
                😅 No entendí eso, ¿puedes repetirlo?

                Puedo ayudarte con:
                • Estado de pedido (ej: EC-000123)
                • Precios (ej: "precio del multímetro")
                • Buscar productos (ej: "tienen cámaras de seguridad?")
                • Envíos y delivery
                • Pagos con Yape
                """;
    }

    // ------------------------------------
    // 1) Preguntas frecuentes
    // ------------------------------------
    private String handleFaq(String msg) {
        if (msg.contains("envio") || msg.contains("envío") || msg.contains("delivery")) {
            return """
                    🚚 Realizamos envíos a todo el Perú.
                    • En Lima el costo depende del distrito.
                    • En provincias depende del departamento.
                    El costo se calcula automáticamente en tu carrito.
                    """;
        }

        if (msg.contains("yape")) {
            return """
                    📲 Puedes pagar con Yape al número **940 310 317**.
                    En el Checkout verás el código QR para escanear.
                    """;
        }

        if (msg.contains("horario") || msg.contains("atienden") || msg.contains("abren")) {
            return """
                    🕒 Nuestro horario de atención es:
                    Lunes a Sábado de 9:00 AM a 7:00 PM.
                    """;
        }

        if (msg.contains("ubicacion") || msg.contains("ubicación") ||
            msg.contains("direccion") || msg.contains("dirección")) {
            return """
                    📍 Estamos en Lima, Perú.
                    Puedes escribirnos para enviarte la ubicación exacta por WhatsApp.
                    """;
        }

        if (msg.contains("garantia") || msg.contains("garantía")) {
            return """
                    🛡 Nuestros productos cuentan con garantía según el fabricante.
                    Ante cualquier falla, contáctanos con tu número de pedido.
                    """;
        }

        return null;
    }

    // ------------------------------------
    // 2) Estado de pedido
    // ------------------------------------
    private String extractNumeroPedido(String msg) {
        Matcher matcher = PEDIDO_PATTERN.matcher(msg);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private String handlePedidoStatus(String numeroPedido) {
        Optional<Pedido> opt = pedidoRepository.findByNumeroPedido(numeroPedido);
        if (opt.isEmpty()) {
            return "❌ No encontré un pedido con el código " + numeroPedido +
                    ". Verifica que esté bien escrito (ej: EC-000123).";
        }

        Pedido pedido = opt.get();
        OrderStatus estado = pedido.getEstado();
        String estadoTexto;

        switch (estado) {
            case RECIBIDO ->
                    estadoTexto = "📩 RECIBIDO (esperando verificación de pago)";
            case PAGO_VERIFICADO ->
                    estadoTexto = "💰 PAGO_VERIFICADO (pago confirmado, pronto saldrá en reparto)";
            case EN_CAMINO ->
                    estadoTexto = "🚚 EN_CAMINO (tu pedido está en reparto)";
            case ENTREGADO ->
                    estadoTexto = "📦 ENTREGADO (pedido completado)";
            default ->
                    estadoTexto = estado.name();
        }

        return "📦 El estado actual de tu pedido " + numeroPedido + " es:\n" + estadoTexto;
    }

    // ------------------------------------
    // 3) Consultas de precio
    // ------------------------------------
    private String handlePriceQuery(String msg) {
        // solo intentamos si realmente habla de precio
        if (!(msg.contains("precio") || msg.contains("cuanto") ||
              msg.contains("cuánto") || msg.contains("vale") ||
              msg.contains("cuesta"))) {
            return null;
        }

        List<String> keywords = extractKeywords(msg);
        if (keywords.isEmpty()) {
            return "💰 ¿De qué producto quieres saber el precio?";
        }

        // probamos cada keyword hasta encontrar productos
        for (String kw : keywords) {
            List<Producto> encontrados =
                    productoRepository.findTop5ByNombreContainingIgnoreCase(kw);
            if (!encontrados.isEmpty()) {
                Producto p = encontrados.get(0);
                return "💰 El precio de **" + p.getNombre() + "** es S/ " + p.getPrecio();
            }
        }

        return "😕 No encontré el producto para darte el precio. " +
               "¿Puedes indicarme el nombre más exacto (ej: 'multímetro digital')?";
    }

    // ------------------------------------
    // 4) Búsqueda de productos
    // ------------------------------------
    private String handleProductSearch(String msg) {
        List<String> keywords = extractKeywords(msg);
        if (keywords.isEmpty()) {
            return null;
        }

        // juntamos resultados de varias keywords (sin repetir)
        LinkedHashSet<Producto> resultado = new LinkedHashSet<>();

        for (String kw : keywords) {
            List<Producto> encontrados =
                    productoRepository.findTop5ByNombreContainingIgnoreCase(kw);
            resultado.addAll(encontrados);
            if (resultado.size() >= 5) break; // máximo 5
        }

        if (resultado.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("🔍 Encontré estos productos relacionados:\n\n");
        resultado.stream().limit(5).forEach(p -> {
            sb.append("• ").append(p.getNombre())
              .append(" — S/ ").append(p.getPrecio())
              .append("\n");
        });

        sb.append("\nPuedes ver más detalles en el catálogo 😉");
        return sb.toString();
    }

    // ------------------------------------
    // Helpers para keywords
    // ------------------------------------
    private List<String> extractKeywords(String msg) {
        // quitar acentos y caracteres raros
        String normalized = normalize(msg);
        // quitar signos
        normalized = normalized.replaceAll("[^a-z0-9áéíóúüñ\\s]", " ");
        String[] parts = normalized.split("\\s+");

        List<String> keywords = new ArrayList<>();
        for (String p : parts) {
            if (p.length() < 3) continue; // muy corta
            if (STOPWORDS.contains(p)) continue;
            keywords.add(p);
        }

        return keywords;
    }

    private String normalize(String input) {
        String temp = Normalizer.normalize(input, Normalizer.Form.NFD);
        return temp.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}