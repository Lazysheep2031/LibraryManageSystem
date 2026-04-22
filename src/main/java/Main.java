// Jackson: 负责把请求体里的 JSON 解析成对象树，也负责把 Java 对象序列化成 JSON 响应。
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// JDK 自带的轻量 HTTP 服务器接口，bonus 后端就是基于它搭起来的。
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
// 这些是基础实验里已经存在的实体类，HTTP 层直接复用，不重新定义一套模型。
import entities.Book;
import entities.Borrow;
import entities.Card;
// 这些是基础实验里定义好的统一返回结构和查询结果结构。
import queries.ApiResult;
import queries.BookQueryConditions;
import queries.BookQueryResults;
import queries.BorrowHistories;
import queries.CardList;
import queries.SortOrder;
// 数据库配置与数据库连接工具。
import utils.ConnectConfig;
import utils.DatabaseConnector;

// 标准 IO、网络、文件、时间、集合、线程池和日志工具。
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

    // 全局日志器：启动失败、请求异常等信息都从这里打到控制台。
    private static final Logger log = Logger.getLogger(Main.class.getName());
    // 全局唯一的 ObjectMapper：所有 JSON 读取和写出都复用它。
    private static final ObjectMapper mapper = new ObjectMapper();
    // 统一规定响应头里的 JSON 类型，避免每个接口重复手写。
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    // bonus 后端固定监听 8000 端口，前端 axios 也默认请求这个端口。
    private static final int PORT = 8000;

    public static void main(String[] args) {
        try {
            // 读取数据库连接配置，例如 host、port、用户名、密码、数据库类型。
            ConnectConfig conf = new ConnectConfig();
            // 创建本地 HTTP 服务并绑定到 8000 端口。
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            // 注册借书证相关接口。
            server.createContext("/card", new CardHandler(conf));
            // 注册借阅查询和借书接口。
            server.createContext("/borrow", new BorrowHandler(conf));
            // 注册还书接口。
            server.createContext("/return", new ReturnHandler(conf));
            // 注册按文件路径批量导入图书接口。
            server.createContext("/book/import", new BookImportHandler(conf));
            // 注册库存修改接口。
            server.createContext("/book/stock", new BookStockHandler(conf));
            // 注册图书查询、新增、修改、删除接口。
            server.createContext("/book", new BookHandler(conf));
            // 使用缓存线程池处理并发请求，避免单线程阻塞整个服务。
            server.setExecutor(Executors.newCachedThreadPool());
            // 真正开始监听端口。
            server.start();
            // 启动成功时打印日志，方便你确认后端已经起来。
            log.info("Server is listening on port " + PORT);
        } catch (Exception e) {
            // 如果启动过程中任意一步失败，就打印错误日志。
            log.log(Level.SEVERE, "Failed to start server.", e);
        }
    }

    // 所有具体路由处理器的公共父类：
    // 统一处理 CORS、OPTIONS 预检、异常捕获和数据库上下文创建。
    private abstract static class JsonHandler implements HttpHandler {
        // 每个 handler 都需要数据库配置，因此统一放在父类里保存。
        protected final ConnectConfig conf;

        private JsonHandler(ConnectConfig conf) {
            // 构造 handler 时把配置保存下来，后续请求到来时再据此连接数据库。
            this.conf = conf;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 每个请求先统一加上跨域响应头。
            addCorsHeaders(exchange.getResponseHeaders());
            // 如果是浏览器发来的 OPTIONS 预检请求，则不进入具体业务逻辑。
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                // 204 表示“没有响应体，但预检通过”。
                exchange.sendResponseHeaders(204, -1);
                // 及时关闭 exchange，结束这次请求。
                exchange.close();
                return;
            }
            try {
                // 真正的业务逻辑留给子类自己实现。
                handleRequest(exchange);
            } catch (IllegalArgumentException e) {
                // 参数错误、字段缺失这类“用户输入问题”统一返回 400。
                writeJson(exchange, 400, errorBody(e.getMessage()));
            } catch (Exception e) {
                // 其他异常视为服务端问题，先记录日志，再返回 500。
                log.log(Level.SEVERE, "Request failed.", e);
                writeJson(exchange, 500, errorBody(e.getMessage()));
            }
        }

        // 子类必须实现这个方法，告诉父类“这个路由真正怎么处理”。
        protected abstract void handleRequest(HttpExchange exchange) throws Exception;

        protected RequestContext openContext() throws Exception {
            // 每次 HTTP 请求都创建一个新的数据库上下文，
            // 避免多个请求共享同一个连接或共享同一个业务对象。
            return new RequestContext(conf);
        }
    }

    // 处理 /card 路由：
    // GET 查询全部借书证，POST 新增借书证，PUT 修改借书证，DELETE 删除借书证。
    private static class CardHandler extends JsonHandler {
        private CardHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 取出当前 HTTP 方法，后面按方法分发。
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                // 查询借书证：调用基础实验里的 showCards。
                try (RequestContext context = openContext()) {
                    ApiResult result = context.library.showCards();
                    // 基础接口如果返回失败，则直接把失败信息包装成 400。
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // payload 中真正装的是 CardList，需要先强转出来。
                    CardList cardList = (CardList) result.payload;
                    // 准备一个列表，用来装前端最终能消费的 JSON 对象。
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (Card card : cardList.getCards()) {
                        // 每张卡都从后端实体映射成前端字段：
                        // 例如 cardId -> id，枚举类型 -> 中文字符串。
                        payload.add(cardToView(card));
                    }
                    // 把整个数组直接返回给前端。
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                // 新增借书证时，请求体里会带 name/department/type 这几个字段。
                JsonNode body = readJson(exchange);
                // 把 JSON 解析成 Card 实体；新建时不要求 id。
                Card card = parseCard(body, false);
                try (RequestContext context = openContext()) {
                    // 真正的插卡逻辑仍然复用基础实验里的 registerCard。
                    ApiResult result = context.library.registerCard(card);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 返回成功消息以及新建好的卡片对象，便于前端显示。
                    writeJson(exchange, 200, successBody("Card created successfully.", cardToView(card)));
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                // 修改借书证时，请求体里必须包含 id。
                JsonNode body = readJson(exchange);
                // 第二个参数传 true，表示 parseCard 会强制要求 id 字段存在。
                Card card = parseCard(body, true);
                try (RequestContext context = openContext()) {
                    // 修改逻辑调用 bonus 扩展出来的 modifyCardInfo。
                    ApiResult result = context.library.modifyCardInfo(card);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 修改成功后返回前端当前这张卡的最新内容。
                    writeJson(exchange, 200, successBody("Card updated successfully.", cardToView(card)));
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                // 删除接口约定从 URL 查询参数中读取 cardId，例如 /card?cardId=3。
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                // 读取 cardId 并校验它必须是合法整数。
                int cardId = parseRequiredInt(query.get("cardId"), "cardId");
                try (RequestContext context = openContext()) {
                    // 真正删除动作复用基础实验里的 removeCard。
                    ApiResult result = context.library.removeCard(cardId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 删除成功时只需要返回成功消息，不一定需要 payload。
                    writeJson(exchange, 200, successBody("Card deleted successfully.", null));
                }
                return;
            }
            // 如果方法既不是 GET/POST/PUT/DELETE，就返回 405。
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    // 处理 /borrow 路由：
    // GET 查询某张借书证的借阅历史，POST 执行借书。
    private static class BorrowHandler extends JsonHandler {
        private BorrowHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 同样先取出 HTTP 方法。
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                // GET /borrow 用于查询借阅记录，不直接查 SQL，而是复用基础接口。
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                // 这个接口约定参数名叫 cardID，和 Borrow.vue 里的 axios 保持一致。
                int cardId = parseRequiredInt(query.get("cardID"), "cardID");
                try (RequestContext context = openContext()) {
                    // 真正查询逻辑由基础实验里的 showBorrowHistory 完成。
                    ApiResult result = context.library.showBorrowHistory(cardId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // payload 实际上是 BorrowHistories。
                    BorrowHistories histories = (BorrowHistories) result.payload;
                    // 准备返回给前端的数组。
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (BorrowHistories.Item item : histories.getItems()) {
                        // 每条借阅记录都转成前端固定需要的四个字段。
                        payload.add(borrowToView(item));
                    }
                    // 直接把数组写给前端，Borrow.vue 会用它渲染表格。
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                // POST /borrow 用于借书，请求体里只需要 bookId 和 cardId。
                JsonNode body = readJson(exchange);
                Borrow borrow = new Borrow(
                        // 从 JSON 中读取图书主键。
                        parseRequiredInt(body.path("bookId").asText(null), "bookId"),
                        // 从 JSON 中读取借书证主键。
                        parseRequiredInt(body.path("cardId").asText(null), "cardId")
                );
                // 借出时间由后端统一生成，保证所有借书请求的时间语义一致。
                borrow.resetBorrowTime();
                try (RequestContext context = openContext()) {
                    // 真正的借书事务、库存扣减、并发锁都在基础接口里完成。
                    ApiResult result = context.library.borrowBook(borrow);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 借书成功只需要返回一个成功消息即可。
                    writeJson(exchange, 200, successBody("Borrowed successfully.", null));
                }
                return;
            }
            // 其他 HTTP 方法不允许。
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    // 处理 /return 路由：
    // 只负责还书，因此只接受 POST。
    private static class ReturnHandler extends JsonHandler {
        private ReturnHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 如果不是 POST，则说明请求方式不符合接口约定。
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            // 读取前端发送来的 JSON。
            JsonNode body = readJson(exchange);
            // 同样用图书号和借书证号构造 Borrow 对象。
            Borrow borrow = new Borrow(
                    parseRequiredInt(body.path("bookId").asText(null), "bookId"),
                    parseRequiredInt(body.path("cardId").asText(null), "cardId")
            );
            // 归还时间由后端统一生成，不信任前端传时间。
            borrow.resetReturnTime();
            try (RequestContext context = openContext()) {
                // 真正的归还事务由基础接口 returnBook 完成。
                ApiResult result = context.library.returnBook(borrow);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                // 归还成功返回统一 JSON。
                writeJson(exchange, 200, successBody("Returned successfully.", null));
            }
        }
    }

    // 处理 /book/stock 路由：
    // 专门负责库存增减，不和修改图书基本信息混在一起。
    private static class BookStockHandler extends JsonHandler {
        private BookStockHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 库存修改接口约定只接受 POST。
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            // 读取前端发送的 bookId 和 deltaStock。
            JsonNode body = readJson(exchange);
            int bookId = parseRequiredInt(body.path("bookId").asText(null), "bookId");
            int deltaStock = parseRequiredInt(body.path("deltaStock").asText(null), "deltaStock");
            try (RequestContext context = openContext()) {
                // 实际库存修改仍然交给基础接口 incBookStock 完成。
                ApiResult result = context.library.incBookStock(bookId, deltaStock);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                // 成功则返回统一成功消息。
                writeJson(exchange, 200, successBody("Stock updated successfully.", null));
            }
        }
    }

    // 处理 /book/import 路由：
    // 接收一个文件路径，然后按行读文件，把内容批量导入图书表。
    private static class BookImportHandler extends JsonHandler {
        private BookImportHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 批量导入是一个会修改数据的动作，因此只允许 POST。
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("Method Not Allowed"));
                return;
            }
            // 读取请求体 JSON。
            JsonNode body = readJson(exchange);
            // 从 JSON 中强制读取 filePath 字段。
            String filePath = readRequiredText(body, "filePath");
            // 先把文件解析成 Book 对象列表。
            // 注意：这一步只是“读文件和组装数据”，不是“写数据库事务”。
            List<Book> books = parseBooksFromFile(filePath);
            try (RequestContext context = openContext()) {
                // 真正的批量插入事务复用基础实验里已经实现好的 storeBook(List<Book>)。
                ApiResult result = context.library.storeBook(books);
                if (!result.ok) {
                    writeJson(exchange, 400, errorBody(result.message));
                    return;
                }
                // 准备一个返回给前端的 payload。
                Map<String, Object> payload = new LinkedHashMap<>();
                // 告诉前端本次导入了多少本书。
                payload.put("count", books.size());
                // 把原始路径也带回去，便于调试或消息提示。
                payload.put("filePath", filePath);
                // 返回统一成功结构。
                writeJson(exchange, 200, successBody("Books imported successfully.", payload));
            }
        }
    }

    // 处理 /book 路由：
    // GET 查图书，POST 新增图书，PUT 修改图书，DELETE 删除图书。
    private static class BookHandler extends JsonHandler {
        private BookHandler(ConnectConfig conf) {
            super(conf);
        }

        @Override
        protected void handleRequest(HttpExchange exchange) throws Exception {
            // 读取当前请求方法。
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                // 图书查询参数来自 URL，例如 /book?title=Database&sortBy=price。
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                // 先把这些原始字符串参数装配成 BookQueryConditions。
                BookQueryConditions conditions = parseBookConditions(query);
                try (RequestContext context = openContext()) {
                    // 真实的筛选、模糊查询、排序都在基础接口 queryBook 里完成。
                    ApiResult result = context.library.queryBook(conditions);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // payload 是 BookQueryResults，需要先拿出来。
                    BookQueryResults books = (BookQueryResults) result.payload;
                    // 准备一个 JSON 数组作为响应体。
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (Book book : books.getResults()) {
                        // 每本书都转换成前端使用的对象结构。
                        payload.add(bookToView(book));
                    }
                    // 把数组写回前端。
                    writeJson(exchange, 200, payload);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                // 新增图书时，请求体里会带 category/title/press/publishYear/author/price/stock。
                JsonNode body = readJson(exchange);
                // 新建不需要前端传 id，因此 requireId 传 false。
                Book book = parseBook(body, false);
                try (RequestContext context = openContext()) {
                    // 调基础接口 storeBook 执行真正入库。
                    ApiResult result = context.library.storeBook(book);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 返回新建好的书对象，前端可以据此展示最新结果。
                    writeJson(exchange, 200, successBody("Book created successfully.", bookToView(book)));
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                // 修改图书时需要前端把 id 一起传来。
                JsonNode body = readJson(exchange);
                // requireId 传 true，表示 parseBook 会强制校验 id。
                Book book = parseBook(body, true);
                try (RequestContext context = openContext()) {
                    // 真正的修改由基础接口 modifyBookInfo 完成。
                    ApiResult result = context.library.modifyBookInfo(book);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 返回修改后的图书对象。
                    writeJson(exchange, 200, successBody("Book updated successfully.", bookToView(book)));
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                // 删除图书从 URL 查询参数中读取 bookId。
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int bookId = parseRequiredInt(query.get("bookId"), "bookId");
                try (RequestContext context = openContext()) {
                    // 真正删除逻辑交给基础接口 removeBook。
                    ApiResult result = context.library.removeBook(bookId);
                    if (!result.ok) {
                        writeJson(exchange, 400, errorBody(result.message));
                        return;
                    }
                    // 删除成功时只需返回成功消息。
                    writeJson(exchange, 200, successBody("Book deleted successfully.", null));
                }
                return;
            }
            // 如果方法不在约定范围内，则统一返回 405。
            writeJson(exchange, 405, errorBody("Method Not Allowed"));
        }
    }

    // RequestContext 是“每次请求对应的一次数据库上下文”。
    // 它的作用是把“数据库连接 + 已初始化的业务实现类”打包在一起，便于 try-with-resources 自动回收。
    private static class RequestContext implements AutoCloseable {
        // 当前请求所使用的数据库连接器。
        private final DatabaseConnector connector;
        // 当前请求所使用的核心业务实现类。
        private final LibraryManagementSystemImpl library;

        private RequestContext(ConnectConfig conf) throws Exception {
            // 根据配置创建数据库连接器。
            connector = new DatabaseConnector(conf);
            // 如果数据库连接失败，则直接抛异常，让上层统一返回错误。
            if (!connector.connect()) {
                throw new IOException("Failed to connect database.");
            }
            // 用已经连好的 connector 创建基础实验里的实现类。
            library = new LibraryManagementSystemImpl(connector);
        }

        @Override
        public void close() {
            // 请求结束后释放数据库连接，避免连接泄漏。
            if (!connector.release()) {
                log.warning("Failed to release database connection.");
            }
        }
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        // getRequestBody() 拿到的是原始输入流。
        try (InputStream inputStream = exchange.getRequestBody()) {
            // 用 Jackson 把输入流中的 JSON 解析成 JsonNode 树结构。
            return mapper.readTree(inputStream);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        // 用 LinkedHashMap 保持参数原有顺序，调试时更直观。
        Map<String, String> query = new LinkedHashMap<>();
        // 如果 URL 本来就没有 query string，直接返回空 map。
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        // 先按 & 切开，得到每一组 key=value。
        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            // 每组参数最多只按第一个 = 切成两部分，避免值里带 = 时被错误分割。
            String[] pair = part.split("=", 2);
            // 解析并解码参数名。
            String key = decodeComponent(pair[0]);
            // 如果存在参数值则解码；如果没有值则按空串处理。
            String value = pair.length > 1 ? decodeComponent(pair[1]) : "";
            // 把参数名和值放进 map。
            query.put(key, value);
        }
        return query;
    }

    private static String decodeComponent(String value) {
        try {
            // query string 中可能出现 %20 之类的 URL 编码，所以这里必须做解码。
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 在这里理论上不应该失败，如果失败就视为程序状态错误。
            throw new IllegalStateException("UTF-8 is not supported.", e);
        }
    }

    private static void addCorsHeaders(Headers headers) {
        // 放开所有来源，方便本地前端开发服务器访问。
        headers.set("Access-Control-Allow-Origin", "*");
        // 告诉浏览器后端支持哪些 HTTP 方法。
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        // 告诉浏览器前端可以带哪些请求头。
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        // 先把 Java 对象序列化成 JSON 字节数组。
        byte[] response = mapper.writeValueAsBytes(body);
        // 在响应头里声明响应体是 JSON。
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        // 写出 HTTP 状态码和响应体长度。
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            // 把 JSON 字节真正写到响应体输出流中。
            outputStream.write(response);
        }
    }

    private static Map<String, Object> successBody(String message, Object payload) {
        // successBody 统一构造成功响应结构，避免每个接口自己手写 JSON。
        Map<String, Object> body = new LinkedHashMap<>();
        // 标记本次请求成功。
        body.put("ok", true);
        // 给前端一个可以直接展示的成功消息。
        body.put("message", message);
        // 如果有额外返回数据，则放到 payload 字段。
        if (payload != null) {
            body.put("payload", payload);
        }
        return body;
    }

    private static Map<String, Object> errorBody(String message) {
        // errorBody 统一构造失败响应结构。
        Map<String, Object> body = new LinkedHashMap<>();
        // 标记请求失败。
        body.put("ok", false);
        // 如果 message 为空，则给一个兜底错误提示。
        body.put("message", message == null ? "Unknown error." : message);
        return body;
    }

    private static Card parseCard(JsonNode body, boolean requireId) {
        // 创建一个空的 Card 对象，后面逐字段填充。
        Card card = new Card();
        // 修改借书证时必须从 JSON 中读取 id；新增时不需要。
        if (requireId) {
            card.setCardId(parseRequiredInt(body.path("id").asText(null), "id"));
        }
        // 把前端 JSON 里的 name 映射到实体的 name 字段。
        card.setName(readRequiredText(body, "name"));
        // 把前端 JSON 里的 department 映射到实体的 department 字段。
        card.setDepartment(readRequiredText(body, "department"));
        // 把前端传来的“教师/学生”等文本映射成后端枚举类型。
        card.setType(parseCardType(readRequiredText(body, "type")));
        return card;
    }

    private static Book parseBook(JsonNode body, boolean requireId) {
        // 创建一个空的 Book 对象。
        Book book = new Book();
        // 修改图书时必须读取 id，新建时不需要。
        if (requireId) {
            book.setBookId(parseRequiredInt(body.path("id").asText(null), "id"));
        }
        // 依次从 JSON 中取出图书基本信息并写入实体。
        book.setCategory(readRequiredText(body, "category"));
        book.setTitle(readRequiredText(body, "title"));
        book.setPress(readRequiredText(body, "press"));
        book.setPublishYear(parseRequiredInt(body.path("publishYear").asText(null), "publishYear"));
        book.setAuthor(readRequiredText(body, "author"));
        book.setPrice(parseRequiredDouble(body.path("price").asText(null), "price"));
        // 修改图书时 stock 通常不会传，因此这里必须先判断字段是否存在。
        if (body.hasNonNull("stock")) {
            book.setStock(parseRequiredInt(body.path("stock").asText(null), "stock"));
        }
        return book;
    }

    private static List<Book> parseBooksFromFile(String filePath) throws IOException {
        // 去掉路径首尾空格后再转换成 Path 对象。
        Path path = Paths.get(filePath.trim());
        // 如果路径不存在，则立即报错。
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + filePath);
        }
        // 如果路径存在但不是普通文件，也立即报错。
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Path is not a file: " + filePath);
        }
        // 按 UTF-8 一次性读出文件所有行。
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        // 准备最终返回的图书对象列表。
        List<Book> books = new ArrayList<>();
        // 标记是否已经处理过表头。
        boolean headerHandled = false;
        // 记录当前行号，便于报错时指出具体哪一行出问题。
        int lineNumber = 0;
        for (String rawLine : lines) {
            // 每进入一行，行号先加一。
            lineNumber++;
            // trim 后用于判断空行和注释行。
            String line = rawLine.trim();
            // 空行、# 注释行、// 注释行直接跳过。
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            // 自动判断这一行更像 CSV 还是 TSV。
            char delimiter = detectDelimiter(line);
            // 按对应分隔符把这一行切成字段列表。
            List<String> fields = splitDelimitedLine(rawLine, delimiter);
            // 如果表头还没处理过，并且这一行看起来是表头，则跳过它。
            if (!headerHandled && isBookHeader(fields)) {
                headerHandled = true;
                continue;
            }
            // 到这里说明表头已经处理过，后续所有有效行都应视为数据行。
            headerHandled = true;
            // 图书导入模板固定要求 7 列，列数不对就直接报错。
            if (fields.size() != 7) {
                throw new IllegalArgumentException("Invalid book import format at line " + lineNumber +
                        ": expected 7 columns but got " + fields.size());
            }
            // 把当前行字段构造成一个 Book 对象，并加入列表。
            books.add(buildBookFromFields(fields, lineNumber));
        }
        // 如果整个文件最终没解析出任何图书，则视为非法导入文件。
        if (books.isEmpty()) {
            throw new IllegalArgumentException("No books found in import file.");
        }
        return books;
    }

    private static BookQueryConditions parseBookConditions(Map<String, String> query) {
        // 创建一个空的查询条件对象。
        BookQueryConditions conditions = new BookQueryConditions();
        // 只要某个参数非空，就把它写入查询条件对象中。
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
        // 这个对象稍后会直接交给基础接口 queryBook。
        return conditions;
    }

    private static Map<String, Object> cardToView(Card card) {
        // 用 LinkedHashMap 组装返回给前端的字段，保证输出顺序稳定。
        Map<String, Object> view = new LinkedHashMap<>();
        // 前端统一把 cardId 命名成 id。
        view.put("id", card.getCardId());
        // 姓名原样输出。
        view.put("name", card.getName());
        // 部门原样输出。
        view.put("department", card.getDepartment());
        // 枚举类型转成前端显示用的中文字符串。
        view.put("type", toDisplayType(card.getType()));
        return view;
    }

    private static Map<String, Object> bookToView(Book book) {
        // 组装前端图书表格所需的字段结构。
        Map<String, Object> view = new LinkedHashMap<>();
        // bookId -> id。
        view.put("id", book.getBookId());
        // 下面这些字段基本与实体字段一一对应。
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
        // 组装前端借阅记录表格需要的字段。
        Map<String, Object> view = new LinkedHashMap<>();
        // 借书证号保持前端既有命名 cardID。
        view.put("cardID", item.getCardId());
        // 图书号保持前端既有命名 bookID。
        view.put("bookID", item.getBookId());
        // 把时间戳格式化成页面可以直接显示的字符串。
        view.put("borrowTime", formatTime(item.getBorrowTime()));
        // 如果 returnTime 为 0，说明尚未归还，前端显示“未归还”。
        view.put("returnTime", item.getReturnTime() == 0 ? "未归还" : formatTime(item.getReturnTime()));
        return view;
    }

    private static String readRequiredText(JsonNode body, String field) {
        // 尝试从 JSON 中读取指定字段。
        String value = body.path(field).asText(null);
        // 如果字段不存在或为空白，则抛参数错误。
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        // 去掉首尾空格后返回。
        return value.trim();
    }

    private static int parseRequiredInt(String value, String field) {
        // 如果参数缺失，直接报错。
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        try {
            // 把字符串解析成整数。
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 如果不是合法整数，也报参数错误。
            throw new IllegalArgumentException("Invalid integer field: " + field);
        }
    }

    private static double parseRequiredDouble(String value, String field) {
        // 如果参数缺失，直接报错。
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        try {
            // 把字符串解析成浮点数。
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            // 如果不是合法数字，则抛参数错误。
            throw new IllegalArgumentException("Invalid number field: " + field);
        }
    }

    private static boolean hasText(String value) {
        // 工具函数：判断一个字符串是否非 null 且去掉空格后非空。
        return value != null && !value.trim().isEmpty();
    }

    private static Card.CardType parseCardType(String value) {
        // 先把原始输入去掉首尾空格。
        String normalized = value.trim();
        // 教师可以写成“教师”、“T”或“teacher”。
        if ("教师".equals(normalized) || "T".equalsIgnoreCase(normalized) ||
                "teacher".equalsIgnoreCase(normalized)) {
            return Card.CardType.Teacher;
        }
        // 学生可以写成“学生”、“S”或“student”。
        if ("学生".equals(normalized) || "S".equalsIgnoreCase(normalized) ||
                "student".equalsIgnoreCase(normalized)) {
            return Card.CardType.Student;
        }
        // 其他值一律视为非法类型。
        throw new IllegalArgumentException("Invalid card type.");
    }

    private static String toDisplayType(Card.CardType type) {
        // 后端枚举转前端中文文本。
        return type == Card.CardType.Teacher ? "教师" : "学生";
    }

    private static Book.SortColumn parseSortColumn(String value) {
        // 统一转成小写，便于大小写无关比较。
        String normalized = value.trim().toLowerCase();
        // 下面这串 if 就是一个“排序字段白名单”。
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
        // 如果不在白名单内，就拒绝该排序字段。
        throw new IllegalArgumentException("Invalid sortBy field.");
    }

    private static SortOrder parseSortOrder(String value) {
        // 统一转成小写后再判断。
        String normalized = value.trim().toLowerCase();
        // 支持升序。
        if ("asc".equals(normalized)) {
            return SortOrder.ASC;
        }
        // 支持降序。
        if ("desc".equals(normalized)) {
            return SortOrder.DESC;
        }
        // 其他值一律非法。
        throw new IllegalArgumentException("Invalid sortOrder field.");
    }

    private static String formatTime(long timestamp) {
        // 创建日期格式器，输出格式固定为 yyyy.MM.dd HH:mm。
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH:mm");
        // 使用系统当前时区，保证本机显示时间一致。
        sdf.setTimeZone(TimeZone.getDefault());
        // 把时间戳包装成 Date 后格式化输出。
        return sdf.format(new Date(timestamp));
    }

    private static char detectDelimiter(String line) {
        // 如果这一行里出现了制表符，就按 TSV 解析；否则按 CSV 解析。
        return line.indexOf('\t') >= 0 ? '\t' : ',';
    }

    private static boolean isBookHeader(List<String> fields) {
        // 如果一行连第一个字段都没有，那它不可能是合法表头。
        if (fields.isEmpty()) {
            return false;
        }
        // 只看第一列，把它转成小写后判断是否为 category/类别。
        String first = fields.get(0).trim().toLowerCase();
        return "category".equals(first) || "类别".equals(first);
    }

    private static Book buildBookFromFields(List<String> fields, int lineNumber) {
        try {
            // 创建一个新的 Book 对象。
            Book book = new Book();
            // 第 1 列：类别。
            book.setCategory(fields.get(0).trim());
            // 第 2 列：书名。
            book.setTitle(fields.get(1).trim());
            // 第 3 列：出版社。
            book.setPress(fields.get(2).trim());
            // 第 4 列：出版年份，需转成 int。
            book.setPublishYear(Integer.parseInt(fields.get(3).trim()));
            // 第 5 列：作者。
            book.setAuthor(fields.get(4).trim());
            // 第 6 列：价格，需转成 double。
            book.setPrice(Double.parseDouble(fields.get(5).trim()));
            // 第 7 列：库存，需转成 int。
            book.setStock(Integer.parseInt(fields.get(6).trim()));
            // 组装成功后返回。
            return book;
        } catch (NumberFormatException e) {
            // 只要有数值字段转换失败，就指出出错的具体行号。
            throw new IllegalArgumentException("Invalid numeric value in import file at line " + lineNumber + ".");
        }
    }

    private static List<String> splitDelimitedLine(String line, char delimiter) {
        // 这是一个轻量级 CSV/TSV 解析器：
        // 支持普通字段、带引号字段、以及 "" 这种双引号转义。
        List<String> fields = new ArrayList<>();
        // current 用来暂存“当前正在解析的字段内容”。
        StringBuilder current = new StringBuilder();
        // inQuotes 表示当前是否处于引号包裹的字段内部。
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            // 依次取出这一行的每个字符。
            char ch = line.charAt(i);
            if (ch == '"') {
                // 如果当前在引号内，且下一个字符还是引号，
                // 说明这是 CSV 中表示一个真实双引号的转义写法 ""。
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    // 下一个引号已经作为转义内容消费掉了，所以这里跳过它。
                    i++;
                } else {
                    // 否则表示进入或离开引号区域。
                    inQuotes = !inQuotes;
                }
                // 引号本身不作为字段内容写入 current。
                continue;
            }
            // 只有在“不在引号内”时，分隔符才表示一个字段结束。
            if (ch == delimiter && !inQuotes) {
                // 当前字段结束，把 current 内容加入结果列表。
                fields.add(current.toString());
                // 清空 current，为下一个字段做准备。
                current.setLength(0);
            } else {
                // 普通字符直接追加到当前字段。
                current.append(ch);
            }
        }
        // 循环结束后，把最后一个字段也加入列表。
        fields.add(current.toString());
        return fields;
    }
}
