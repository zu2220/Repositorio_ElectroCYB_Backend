package com.electrocyb.store.producto;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ProductAdviceService {

    private final ProductoRepository productoRepository;

    public ProductAdviceService(ProductoRepository productoRepository) {
        this.productoRepository = productoRepository;
    }

    // ==========================================================
    // Tipos auxiliares
    // ==========================================================

    // Rango de precios
    public static class PriceRange {
        final BigDecimal min; // puede ser null
        final BigDecimal max; // puede ser null

        public PriceRange(BigDecimal min, BigDecimal max) {
            this.min = min;
            this.max = max;
        }

        public BigDecimal min() { return min; }
        public BigDecimal max() { return max; }
    }

    // Tipo de resultado de búsqueda
    public enum SearchType {
        NO_PRODUCTS_IN_DB,
        DB_NAME_MATCH,
        CATALOG_REQUEST,
        DIRECT_NAME_MATCH,
        TEXT_MATCH,
        FALLBACK
    }

    // Resultado estructurado para poder usarlo desde el ChatService + LLM
    public record ProductSearchResult(
            List<Producto> products,
            PriceRange priceRange,
            SearchType type,
            boolean catalogTruncated
    ) {}

    // Wrapper interno para puntaje
    private static class ScoredProduct {
        final Producto product;
        final int score;

        ScoredProduct(Producto product, int score) {
            this.product = product;
            this.score = score;
        }
    }

    // ==========================================================
    // PÚBLICOS
    // ==========================================================

    /**
     * NUEVO: búsqueda estructurada para que el ChatService pueda pasarle
     * la lista al modelo de IA.
     */
    public ProductSearchResult findProductsForMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new ProductSearchResult(
                    List.of(),
                    null,
                    SearchType.FALLBACK,
                    false
            );
        }

        String original = userMessage.trim();
        String normalizedMsg = normalize(original);

        // 1) Traemos todos los productos
        List<Producto> todos = productoRepository.findAll();
        if (todos.isEmpty()) {
            return new ProductSearchResult(
                    List.of(),
                    null,
                    SearchType.NO_PRODUCTS_IN_DB,
                    false
            );
        }

        // 2) BÚSQUEDA DIRECTA POR NOMBRE EN BD (match fuerte)
        List<Producto> fromDbByName = productoRepository
                .findTop5ByNombreContainingIgnoreCase(original);

        if (!fromDbByName.isEmpty()) {
            return new ProductSearchResult(
                    fromDbByName,
                    null,
                    SearchType.DB_NAME_MATCH,
                    false
            );
        }

        // 3) Catálogo completo / lista de productos
        if (isAskForAllProducts(normalizedMsg)) {
            int limit = 20;
            List<Producto> limited = todos.stream()
                    .limit(limit)
                    .collect(Collectors.toList());
            boolean truncated = todos.size() > limit;

            return new ProductSearchResult(
                    limited,
                    null,
                    SearchType.CATALOG_REQUEST,
                    truncated
            );
        }

        // 4) Intentar match directo por nombre normalizado (en memoria)
        List<Producto> exactByName = todos.stream()
                .filter(p -> nameMatchesUserInput(p, normalizedMsg))
                .collect(Collectors.toList());

        if (!exactByName.isEmpty()) {
            List<Producto> limited = exactByName.stream()
                    .limit(5)
                    .collect(Collectors.toList());

            return new ProductSearchResult(
                    limited,
                    null,
                    SearchType.DIRECT_NAME_MATCH,
                    false
            );
        }

        // 5) Detectar rango de precio (si existe)
        PriceRange priceRange = extractPriceRange(normalizedMsg);

        // 6) Palabras clave
        List<String> keywords = extractKeywords(normalizedMsg);

        // 7) Score por relevancia de texto
        List<ScoredProduct> scored = todos.stream()
                .map(p -> new ScoredProduct(p, scoreProduct(p, normalizedMsg, keywords)))
                .filter(sp -> sp.score > 0)
                .sorted(Comparator.comparingInt((ScoredProduct sp) -> sp.score).reversed())
                .toList();

        List<Producto> byText = scored.stream()
                .map(sp -> sp.product)
                .collect(Collectors.toList());

        if (byText.isEmpty()) {
            byText = todos;
        }

        // 8) Filtro de precio (si no deja vacío)
        List<Producto> filtered = byText;
        if (priceRange != null) {
            List<Producto> byPrice = byText.stream()
                    .filter(p -> isWithinPriceRange(p, priceRange))
                    .collect(Collectors.toList());
            if (!byPrice.isEmpty()) {
                filtered = byPrice;
            }
        }

        // 9) Si aún así no tenemos nada
        if (filtered.isEmpty()) {
            List<Producto> primeros = todos.stream()
                    .limit(5)
                    .collect(Collectors.toList());

            if (primeros.isEmpty()) {
                return new ProductSearchResult(
                        List.of(),
                        priceRange,
                        SearchType.FALLBACK,
                        false
                );
            }

            return new ProductSearchResult(
                    primeros,
                    priceRange,
                    SearchType.FALLBACK,
                    false
            );
        }

        // 10) Top 5
        List<Producto> top = filtered.stream()
                .limit(5)
                .collect(Collectors.toList());

        return new ProductSearchResult(
                top,
                priceRange,
                SearchType.TEXT_MATCH,
                false
        );
    }

    /**
     * Método de compatibilidad: genera la respuesta en TEXTO PLANO
     * con las viñetas "• [id] ..." para que el front las convierta en cards.
     */
    public String buildProductSuggestionText(String userMessage) {
        ProductSearchResult result = findProductsForMessage(userMessage);

        // No hay productos en DB
        if (result.type() == SearchType.NO_PRODUCTS_IN_DB) {
            return """
                    No encontré productos para lo que me indicas porque actualmente no hay productos registrados en la base de datos.
                    
                    Revisa si la tabla 'productos' tiene datos cargados.
                    """;
        }

        // Si no hay nada utilizable, pedimos más info de forma inteligente
        if (result.products() == null || result.products().isEmpty()) {
            return """
                    No estoy seguro de haber encontrado el producto exacto que necesitas 😅.
                    
                    ¿Me puedes indicar un poco más de detalle? Por ejemplo:
                    - ¿Es para interior o exterior?
                    - ¿Para qué ambiente? (sala, dormitorio, fachada, jardín, etc.)
                    - ¿Presupuesto aproximado? (por ejemplo: hasta 50 soles)
                    
                    Con eso puedo recomendarte mejores opciones de nuestro catálogo.
                    """;
        }

        String lista = result.products().stream()
                .map(this::formatProductLine)
                .collect(Collectors.joining("\n"));

        // Texto según tipo de búsqueda
        return switch (result.type()) {
            case DB_NAME_MATCH -> "Estos productos coinciden con el nombre que me indicaste:\n\n"
                    + lista
                    + "\n\nSi quieres más detalles de uno de ellos, haz clic en la tarjeta o dime el nombre.";
            case CATALOG_REQUEST -> {
                String extra = result.catalogTruncated()
                        ? "\n\n(Se muestran solo los primeros " + result.products().size() +
                          " productos del catálogo. Si buscas algo más específico, dime por ejemplo: 'foco led para sala', 'sensor de movimiento para pasadizo', etc.)"
                        : "\n\nSi quieres algo más específico, dime por ejemplo: 'foco led', 'sensor de movimiento', 'lámpara para sala', etc.";
                yield "Te muestro parte de nuestro catálogo de productos:\n\n"
                        + lista
                        + extra;
            }
            case DIRECT_NAME_MATCH -> "Estos productos coinciden directamente con el nombre que me indicaste:\n\n"
                    + lista
                    + "\n\nSi quieres más detalles de uno de ellos, dime el nombre o haz clic en la tarjeta.";
            case TEXT_MATCH, FALLBACK -> {
                String header = "Esto es lo que encontré según lo que me comentas";
                String priceText = buildPriceFilterText(result.priceRange());
                if (!priceText.isBlank()) {
                    header += " (considerando " + priceText + ")";
                }
                header += ":\n\n";
                String footer = "\n\nSi quieres más detalles de uno de ellos, dime el nombre o haz clic en la tarjeta.";
                yield header + lista + footer;
            }
            default -> {
                // por si acaso
                yield "Esto es lo que encontré:\n\n" + lista;
            }
        };
    }

    // ==========================================================
    // Helpers internos
    // ==========================================================

    /**
     * Detecta si el usuario está pidiendo el catálogo completo o todos los productos.
     */
    private boolean isAskForAllProducts(String normalizedMsg) {
        return normalizedMsg.contains("todos los productos")
                || normalizedMsg.contains("todo el catalogo")
                || normalizedMsg.contains("todo el catálogo")
                || normalizedMsg.contains("lista de productos")
                || normalizedMsg.contains("lista completa")
                || normalizedMsg.contains("catalogo")
                || normalizedMsg.contains("catálogo")
                || normalizedMsg.contains("todo tu catalogo")
                || normalizedMsg.contains("todo su catalogo");
    }

    /**
     * Intenta ver si el nombre del producto coincide "directamente" con lo que escribió el usuario.
     * Ignora mayúsculas, tildes y pequeños extras.
     */
    private boolean nameMatchesUserInput(Producto p, String normalizedMsg) {
        if (p.getNombre() == null || p.getNombre().isBlank()) return false;

        String nameNorm = normalize(p.getNombre());

        // Igual exacto
        if (nameNorm.equals(normalizedMsg)) return true;

        // Usuario escribió el nombre más cosas, ej: "quiero lampara led moderna circular"
        if (normalizedMsg.contains(nameNorm)) return true;

        // Usuario solo puso parte del nombre, pero suficientemente larga
        if (nameNorm.contains(normalizedMsg) && normalizedMsg.length() >= 4) return true;

        // Adicional: si la mayoría de las palabras del nombre aparecen en el mensaje
        String[] nameParts = nameNorm.split("\\s+");
        int hits = 0;
        int totalWords = 0;
        for (String part : nameParts) {
            if (part.length() < 3) continue;
            totalWords++;
            if (normalizedMsg.contains(part)) {
                hits++;
            }
        }
        return totalWords > 0 && hits >= Math.max(1, totalWords / 2);
    }

    /**
     * Construye un texto del producto (nombre + categoría + descripción + características) normalizado sin acentos.
     */
    private String buildNormalizedProductText(Producto p) {
        StringBuilder sb = new StringBuilder();
        if (p.getNombre() != null) sb.append(p.getNombre()).append(" ");
        if (p.getCategoria() != null) sb.append(p.getCategoria()).append(" ");
        if (p.getDescripcion() != null) sb.append(p.getDescripcion()).append(" ");
        if (p.getCaracteristicas() != null && !p.getCaracteristicas().isEmpty()) {
            p.getCaracteristicas().forEach((k, v) -> {
                if (k != null) sb.append(k).append(" ");
                if (v != null) sb.append(v).append(" ");
            });
        }

        return normalize(sb.toString());
    }

    /**
     * Calcula un score de relevancia sencillo para el producto según el mensaje del usuario.
     */
    private int scoreProduct(Producto p, String normalizedMsg, List<String> keywords) {
        int score = 0;

        // Si coincide por nombre de forma fuerte, le damos muchos puntos
        if (nameMatchesUserInput(p, normalizedMsg)) {
            score += 100;
        }

        String productText = buildNormalizedProductText(p);

        // Puntuamos por palabras clave encontradas
        for (String kw : keywords) {
            if (kw.length() < 3) continue;
            if (productText.contains(kw)) {
                score += 10;
            }
        }

        return score;
    }

    /**
     * Extrae palabras clave relevantes (sacando solo stopwords muy genéricas).
     */
    private List<String> extractKeywords(String normalizedMsg) {
        String[] parts = normalizedMsg.split("\\s+");
        Set<String> stopwords = Set.of(
                "quiero", "busco", "necesito", "una", "un", "para", "que", "cual",
                "producto", "productos", "me", "recomiendame", "recomiendeme", "recomienda",
                "hasta", "maximo", "minimo", "entre", "desde", "soles", "s", "aprox",
                "al", "menos", "mas", "de", "a", "y", "como", "el", "la", "los", "las",
                "todos", "todas", "catalogo", "catálogo", "lista", "completa",
                "dame", "muestrame", "muéstrame", "ensename", "enséname"
        );

        List<String> keywords = new ArrayList<>();
        for (String p : parts) {
            String token = p.trim();
            if (token.length() < 3) continue;
            if (stopwords.contains(token)) continue;
            keywords.add(token);
        }

        if (keywords.isEmpty()) {
            keywords.add(normalizedMsg);
        }

        return keywords;
    }

    /**
     * Normaliza texto: minúsculas + sin acentos.
     */
    private String normalize(String input) {
        if (input == null) return "";
        String lower = input.toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    /**
     * Formatea la línea de producto que verá el usuario.
     * Formato: "• [id] Nombre — S/ 35.00 — Descripción..."
     */
    public String formatProductLine(Producto p) {
        String price = (p.getPrecio() != null && !p.getPrecio().isBlank())
                ? "S/ " + p.getPrecio()
                : "Precio no disponible";

        String desc = (p.getDescripcion() != null && !p.getDescripcion().isBlank())
                ? " — " + p.getDescripcion()
                : "";

        return "• [" + p.getId() + "] " + p.getNombre() + " — " + price + desc;
    }

    /**
     * Intenta interpretar el rango de precio desde el mensaje normalizado.
     */
    private PriceRange extractPriceRange(String normalizedMsg) {
        // 1) entre X y Y / de X a Y
        Pattern betweenPattern = Pattern.compile(
                "(?:entre|de)\\s+(\\d+(?:[.,]\\d+)?)\\s+(?:a|y)\\s+(\\d+(?:[.,]\\d+)?)"
        );
        Matcher mBetween = betweenPattern.matcher(normalizedMsg);
        if (mBetween.find()) {
            BigDecimal min = toBigDecimal(mBetween.group(1));
            BigDecimal max = toBigDecimal(mBetween.group(2));
            if (min != null && max != null) {
                if (min.compareTo(max) > 0) {
                    BigDecimal tmp = min;
                    min = max;
                    max = tmp;
                }
                return new PriceRange(min, max);
            }
        }

        // 2) hasta X / maximo X / no mas de X
        Pattern maxPattern = Pattern.compile(
                "(?:hasta|maximo|como maximo|no mas de)\\s+(\\d+(?:[.,]\\d+)?)"
        );
        Matcher mMax = maxPattern.matcher(normalizedMsg);
        if (mMax.find()) {
            BigDecimal max = toBigDecimal(mMax.group(1));
            if (max != null) {
                return new PriceRange(null, max);
            }
        }

        // 3) desde X / a partir de X / minimo / al menos X
        Pattern minPattern = Pattern.compile(
                "(?:desde|a partir de|minimo|como minimo|al menos)\\s+(\\d+(?:[.,]\\d+)?)"
        );
        Matcher mMin = minPattern.matcher(normalizedMsg);
        if (mMin.find()) {
            BigDecimal min = toBigDecimal(mMin.group(1));
            if (min != null) {
                return new PriceRange(min, null);
            }
        }

        return null;
    }

    /**
     * Convierte String a BigDecimal de forma segura.
     */
    private BigDecimal toBigDecimal(String value) {
        if (value == null) return null;
        try {
            String normalized = value.replace(",", ".").trim();
            return new BigDecimal(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifica si el precio del producto está dentro del rango.
     */
    private boolean isWithinPriceRange(Producto p, PriceRange range) {
        if (p.getPrecio() == null || p.getPrecio().isBlank()) {
            return false;
        }
        BigDecimal price = toBigDecimal(p.getPrecio());
        if (price == null) return false;

        if (range.min != null && price.compareTo(range.min) < 0) {
            return false;
        }
        if (range.max != null && price.compareTo(range.max) > 0) {
            return false;
        }
        return true;
    }

    /**
     * Texto para mostrar el rango de precio en la respuesta (solo informativo).
     */
    private String buildPriceFilterText(PriceRange range) {
        if (range == null) return "";
        if (range.min != null && range.max != null) {
            return "precios entre S/ " + range.min + " y S/ " + range.max;
        } else if (range.min != null) {
            return "precios desde S/ " + range.min;
        } else if (range.max != null) {
            return "precios hasta S/ " + range.max;
        }
        return "";
    }
}