import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import entities.Book;
import entities.Borrow;
import entities.Card;
import queries.ApiResult;
import queries.BookQueryConditions;
import queries.BookQueryResults;
import queries.BorrowHistories;
import queries.CardList;
import queries.SortOrder;
import utils.ConnectConfig;
import utils.DatabaseConnector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final int PORT = 8000;

    public static void main(String[] args) {
        try {
            ConnectConfig conf = new ConnectConfig();
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            // bonus 的 HTTP 接口统一在这里注册，前端只和这些路由交互。
            server.createContext("/card", new CardHandler(conf));
            server.createContext("/borrow", new BorrowHandler(conf));
            server.createContext("/return", new ReturnHandler(conf));
            server.createContext("/book/import", new BookImportHandler(conf));
            server.createContext("/book/stock", new BookStockHandler(conf));
            server.createContext("/book", new BookHandler(conf));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            log.info("Server is listening on port " + PORT);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to start server.", e);
        }
    }

    private abstract static class JsonHandler implements HttpHandler {
        protected final ConnectConfig conf;

        private JsonHandler(ConnectConfig conf) {
            this.conf = conf;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 统一处理 CORS 和 OPTIONS 预检，请求分发逻辑只保留在具体 handler 中。
            addCorsHeaders(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            try {
                handleRequest(exchange);
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, errorBody(e.getMessage()));
            } catch (Exception e) {
                log.log(Level.SEVERE, "Request failed.", e);
                writeJson(exchange, 500, errorBody(e.getMessage()));
            }
        }

        protected abstract void handleRequest(HttpExchange exchange) throws Exception;

        protected RequestContext openContext() throws Exception {
            // 每个请求单独打开并释放数据库连接，避免不同 HTTP 请求共享状态。
            return new RequestContext(conf);
        }
    }

    private static class CardHandler extends JsonHandler {
        private CardHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.showCards();
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    CardList cardList = (CardList) result.payload;
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (Card card : cardList.getCards()) {
                        // 这里把后端实体转成前端需要的字段结构，避免前端直接依赖数据库字段名。
                        payload.add(cardToView(card));
                    }
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                JsonNode body = readJson(exchange);
                Card card = parseCard(body, false);
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.registerCard(card);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Card created successfully.", cardToView(card)));
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                JsonNode body = readJson(exchange);
                Card card = parseCard(body, true);
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.modifyCardInfo(card);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Card updated successfully.", cardToView(card)));
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int cardId = parseRequiredInt(query.get("cardId"), "cardId");
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.removeCard(cardId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Card deleted successfully.", null));
                }
                return;
            }
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    private static class BorrowHandler extends JsonHandler {
        private BorrowHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                // 借阅记录查询直接复用基础实验里的 showBorrowHistory。
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int cardId = parseRequiredInt(query.get("cardID"), "cardID");
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.showBorrowHistory(cardId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    BorrowHistories histories = (BorrowHistories) result.payload;
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (BorrowHistories.Item item : histories.getItems()) {
                        payload.add(borrowToView(item));
                    }
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                JsonNode body = readJson(exchange);
                Borrow borrow = new Borrow(
                        parseRequiredInt(body.path("bookId").asText(null), "bookId"),
                        parseRequiredInt(body.path("cardId").asText(null), "cardId")
                );
                // 借出时间由后端统一生成，避免前端自己传时间破坏业务约束。
                borrow.resetBorrowTime();
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.borrowBook(borrow);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Borrowed successfully.", null));
                }
                return;
            }
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    private static class ReturnHandler extends JsonHandler {
        private ReturnHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            JsonNode body = readJson(exchange);
            Borrow borrow = new Borrow(
                    parseRequiredInt(body.path("bookId").asText(null), "bookId"),
                    parseRequiredInt(body.path("cardId").asText(null), "cardId")
            );
            // 归还时间同样由后端生成，和基础接口的时间语义保持一致。
            borrow.resetReturnTime();
            try (RequestContext context = openContext()) {
                ApiResult result = context.library.returnBook(borrow);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                writeJson(exchange, 200, successBody("Returned successfully.", null));
            }
        }
    }

    private static class BookStockHandler extends JsonHandler {
        private BookStockHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            JsonNode body = readJson(exchange);
            int bookId = parseRequiredInt(body.path("bookId").asText(null), "bookId");
            int deltaStock = parseRequiredInt(body.path("deltaStock").asText(null), "deltaStock");
            try (RequestContext context = openContext()) {
                ApiResult result = context.library.incBookStock(bookId, deltaStock);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                writeJson(exchange, 200, successBody("Stock updated successfully.", null));
            }
        }
    }

    private static class BookImportHandler extends JsonHandler {
        private BookImportHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            JsonNode body = readJson(exchange);
            String filePath = readRequiredText(body, "filePath");
            // 文件读取只是外层包装，真正的批量事务仍然复用 storeBook(List<Book>)。
            List<Book> books = parseBooksFromFile(filePath);
            try (RequestContext context = openContext()) {
                ApiResult result = context.library.storeBook(books);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("count", books.size());
                payload.put("filePath", filePath);
                writeJson(exchange, 200, successBody("Books imported successfully.", payload));
            }
        }
    }

    private static class BookHandler extends JsonHandler {
        private BookHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                // 图书页的查询参数先被还原成 BookQueryConditions，再交给基础查询接口处理。
                BookQueryConditions conditions = parseBookConditions(query);
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.queryBook(conditions);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    BookQueryResults books = (BookQueryResults) result.payload;
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (Book book : books.getResults()) {
                        payload.add(bookToView(book));
                    }
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                JsonNode body = readJson(exchange);
                Book book = parseBook(body, false);
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.storeBook(book);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Book created successfully.", bookToView(book)));
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                JsonNode body = readJson(exchange);
                Book book = parseBook(body, true);
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.modifyBookInfo(book);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Book updated successfully.", bookToView(book)));
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int bookId = parseRequiredInt(query.get("bookId"), "bookId");
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.removeBook(bookId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    writeJson(exchange, 200, successBody("Book deleted successfully.", null));
                }
                return;
            }
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    private static class RequestContext implements AutoCloseable {
        private final DatabaseConnector connector;
        private final LibraryManagementSystemImpl library;

        private RequestContext(ConnectConfig conf) throws Exception {
            // HTTP 层不自己写 SQL，而是统一复用已经通过测试的后端实现类。
            connector = new DatabaseConnector(conf);
            if (!connector.connect()) {
                throw new IOException("Failed to connect database.");
            }
            library = new LibraryManagementSystemImpl(connector);
        }

        @Override
        public void close() {
            if (!connector.release()) {
                log.warning("Failed to release database connection.");
            }
        }
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return mapper.readTree(inputStream);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        // 把 ?a=1&b=2 这种 query string 解析成 map，后续 handler 统一按键取值。
        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            String key = decodeComponent(pair[0]);
            String value = pair.length > 1 ? decodeComponent(pair[1]) : "";
            query.put(key, value);
        }
        return query;
    }

    private static String decodeComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is not supported.", e);
        }
    }

    private static void addCorsHeaders(Headers headers) {
        // 前端运行在另一个端口，必须放开跨域访问。
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        // 所有接口统一走 JSON 响应，前端可以直接按 response.data 读取。
        byte[] response = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(response);
        }
    }

    private static Map<String, Object> successBody(String message, Object payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("message", message);
        if (payload != null) {
            body.put("payload", payload);
        }
        return body;
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message == null ? "Unknown error." : message);
        return body;
    }

    private static Card parseCard(JsonNode body, boolean requireId) {
        Card card = new Card();
        if (requireId) {
            card.setCardId(parseRequiredInt(body.path("id").asText(null), "id"));
        }
        // 这里做的是前端 JSON -> 后端实体的字段映射。
        card.setName(readRequiredText(body, "name"));
        card.setDepartment(readRequiredText(body, "department"));
        card.setType(parseCardType(readRequiredText(body, "type")));
        return card;
    }

    private static Book parseBook(JsonNode body, boolean requireId) {
        Book book = new Book();
        if (requireId) {
            book.setBookId(parseRequiredInt(body.path("id").asText(null), "id"));
        }
        // 修改图书时 stock 不是必传字段，所以这里单独判断是否存在。
        book.setCategory(readRequiredText(body, "category"));
        book.setTitle(readRequiredText(body, "title"));
        book.setPress(readRequiredText(body, "press"));
        book.setPublishYear(parseRequiredInt(body.path("publishYear").asText(null), "publishYear"));
        book.setAuthor(readRequiredText(body, "author"));
        book.setPrice(parseRequiredDouble(body.path("price").asText(null), "price"));
        if (body.hasNonNull("stock")) {
            book.setStock(parseRequiredInt(body.path("stock").asText(null), "stock"));
        }
        return book;
    }

    private static List<Book> parseBooksFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath.trim());
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Path is not a file: " + filePath);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Book> books = new ArrayList<>();
        boolean headerHandled = false;
        int lineNumber = 0;
        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.trim();
            // 允许空行和注释行，方便手工维护导入模板。
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            char delimiter = detectDelimiter(line);
            List<String> fields = splitDelimitedLine(rawLine, delimiter);
            if (!headerHandled && isBookHeader(fields)) {
                headerHandled = true;
                continue;
            }
            headerHandled = true;
            if (fields.size() != 7) {
                throw new IllegalArgumentException("Invalid book import format at line " + lineNumber +
                        ": expected 7 columns but got " + fields.size());
            }
            books.add(buildBookFromFields(fields, lineNumber));
        }
        if (books.isEmpty()) {
            throw new IllegalArgumentException("No books found in import file.");
        }
        return books;
    }

    private static BookQueryConditions parseBookConditions(Map<String, String> query) {
        BookQueryConditions conditions = new BookQueryConditions();
        // 这里只做参数解析，不在 HTTP 层里重复实现查询逻辑。
        if (hasText(query.get("category"))) {
            conditions.setCategory(query.get("category"));
        }
        if (hasText(query.get("title"))) {
            conditions.setTitle(query.get("title"));
        }
        if (hasText(query.get("press"))) {
            conditions.setPress(query.get("press"));
        }
        if (hasText(query.get("author"))) {
            conditions.setAuthor(query.get("author"));
        }
        if (hasText(query.get("minPublishYear"))) {
            conditions.setMinPublishYear(parseRequiredInt(query.get("minPublishYear"), "minPublishYear"));
        }
        if (hasText(query.get("maxPublishYear"))) {
            conditions.setMaxPublishYear(parseRequiredInt(query.get("maxPublishYear"), "maxPublishYear"));
        }
        if (hasText(query.get("minPrice"))) {
            conditions.setMinPrice(parseRequiredDouble(query.get("minPrice"), "minPrice"));
        }
        if (hasText(query.get("maxPrice"))) {
            conditions.setMaxPrice(parseRequiredDouble(query.get("maxPrice"), "maxPrice"));
        }
        if (hasText(query.get("sortBy"))) {
            conditions.setSortBy(parseSortColumn(query.get("sortBy")));
        }
        if (hasText(query.get("sortOrder"))) {
            conditions.setSortOrder(parseSortOrder(query.get("sortOrder")));
        }
        return conditions;
    }

    private static Map<String, Object> cardToView(Card card) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", card.getCardId());
        view.put("name", card.getName());
        view.put("department", card.getDepartment());
        view.put("type", toDisplayType(card.getType()));
        return view;
    }

    private static Map<String, Object> bookToView(Book book) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", book.getBookId());
        view.put("category", book.getCategory());
        view.put("title", book.getTitle());
        view.put("press", book.getPress());
        view.put("publishYear", book.getPublishYear());
        view.put("author", book.getAuthor());
        view.put("price", book.getPrice());
        view.put("stock", book.getStock());
        return view;
    }

    private static Map<String, Object> borrowToView(BorrowHistories.Item item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cardID", item.getCardId());
        view.put("bookID", item.getBookId());
        // 前端直接显示格式化后的时间字符串，避免页面再处理时间戳。
        view.put("borrowTime", formatTime(item.getBorrowTime()));
        view.put("returnTime", item.getReturnTime() == 0 ? "未归还" : formatTime(item.getReturnTime()));
        return view;
    }

    private static String readRequiredText(JsonNode body, String field) {
        String value = body.path(field).asText(null);
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.trim();
    }

    private static int parseRequiredInt(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer field: " + field);
        }
    }

    private static double parseRequiredDouble(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number field: " + field);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Card.CardType parseCardType(String value) {
        String normalized = value.trim();
        if ("教师".equals(normalized) || "T".equalsIgnoreCase(normalized) ||
                "teacher".equalsIgnoreCase(normalized)) {
            return Card.CardType.Teacher;
        }
        if ("学生".equals(normalized) || "S".equalsIgnoreCase(normalized) ||
                "student".equalsIgnoreCase(normalized)) {
            return Card.CardType.Student;
        }
        throw new IllegalArgumentException("Invalid card type.");
    }

    private static String toDisplayType(Card.CardType type) {
        return type == Card.CardType.Teacher ? "教师" : "学生";
    }

    private static Book.SortColumn parseSortColumn(String value) {
        String normalized = value.trim().toLowerCase();
        // 排序字段只允许走白名单枚举，避免把任意字符串直接拼进 SQL。
        if ("bookid".equals(normalized) || "book_id".equals(normalized)) {
            return Book.SortColumn.BOOK_ID;
        }
        if ("category".equals(normalized)) {
            return Book.SortColumn.CATEGORY;
        }
        if ("title".equals(normalized)) {
            return Book.SortColumn.TITLE;
        }
        if ("press".equals(normalized)) {
            return Book.SortColumn.PRESS;
        }
        if ("publishyear".equals(normalized) || "publish_year".equals(normalized)) {
            return Book.SortColumn.PUBLISH_YEAR;
        }
        if ("author".equals(normalized)) {
            return Book.SortColumn.AUTHOR;
        }
        if ("price".equals(normalized)) {
            return Book.SortColumn.PRICE;
        }
        if ("stock".equals(normalized)) {
            return Book.SortColumn.STOCK;
        }
        throw new IllegalArgumentException("Invalid sortBy field.");
    }

    private static SortOrder parseSortOrder(String value) {
        String normalized = value.trim().toLowerCase();
        if ("asc".equals(normalized)) {
            return SortOrder.ASC;
        }
        if ("desc".equals(normalized)) {
            return SortOrder.DESC;
        }
        throw new IllegalArgumentException("Invalid sortOrder field.");
    }

    private static String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH:mm");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(timestamp));
    }

    private static char detectDelimiter(String line) {
        // 模板支持 CSV 和 TSV，两者只在分隔符上有区别。
        return line.indexOf('\t') >= 0 ? '\t' : ',';
    }

    private static boolean isBookHeader(List<String> fields) {
        if (fields.isEmpty()) {
            return false;
        }
        String first = fields.get(0).trim().toLowerCase();
        return "category".equals(first) || "类别".equals(first);
    }

    private static Book buildBookFromFields(List<String> fields, int lineNumber) {
        try {
            Book book = new Book();
            book.setCategory(fields.get(0).trim());
            book.setTitle(fields.get(1).trim());
            book.setPress(fields.get(2).trim());
            book.setPublishYear(Integer.parseInt(fields.get(3).trim()));
            book.setAuthor(fields.get(4).trim());
            book.setPrice(Double.parseDouble(fields.get(5).trim()));
            book.setStock(Integer.parseInt(fields.get(6).trim()));
            return book;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in import file at line " + lineNumber + ".");
        }
    }

    private static List<String> splitDelimitedLine(String line, char delimiter) {
        // 这里做了一个轻量级 CSV/TSV 解析，支持带引号字段和双引号转义。
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (ch == delimiter && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
