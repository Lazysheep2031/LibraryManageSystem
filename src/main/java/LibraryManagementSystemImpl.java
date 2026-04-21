import entities.Book;
import entities.Borrow;
import entities.Card;
import queries.*;
import utils.DBInitializer;
import utils.DatabaseConnector;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class LibraryManagementSystemImpl implements LibraryManagementSystem {

    private final DatabaseConnector connector;

    public LibraryManagementSystemImpl(DatabaseConnector connector) {
        this.connector = connector;
    }

    @Override
    public ApiResult storeBook(Book book) {
        Connection conn = connector.getConn();
        // 直接依赖数据库唯一约束判重，避免在 Java 层重复写查重逻辑。
        String sql = "insert into book(category, title, press, publish_year, author, price, stock) " +
                "values (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindBookInsert(stmt, book);
            if (stmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to insert book.");
            }
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    // 回填数据库生成的主键，后续测试和业务逻辑都要依赖它。
                    book.setBookId(rs.getInt(1));
                }
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult incBookStock(int bookId, int deltaStock) {
        Connection conn = connector.getConn();
        // 用单条原子更新保证“校验库存 + 修改库存”在数据库里一次完成，避免并发下先查后改带来的竞态。
        String updateSql = "update book set stock = stock + ? where book_id = ? and stock + ? >= 0";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setInt(1, deltaStock);
            stmt.setInt(2, bookId);
            stmt.setInt(3, deltaStock);
            int affected = stmt.executeUpdate();
            if (affected == 1) {
                commit(conn);
                return new ApiResult(true, null);
            }
            if (deltaStock == 0 && bookExists(conn, bookId)) {
                commit(conn);
                return new ApiResult(true, null);
            }
            rollback(conn);
            return new ApiResult(false, "Invalid book id or stock.");
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult storeBook(List<Book> books) {
        Connection conn = connector.getConn();
        // 批量入库必须作为一个整体事务执行，任意一本失败都要回滚整批。
        String insertSql = "insert into book(category, title, press, publish_year, author, price, stock) " +
                "values (?, ?, ?, ?, ?, ?, ?)";
        // 插入后再按唯一键回查主键，避免依赖 batch generated keys 的驱动行为。
        String querySql = "select book_id from book where category = ? and title = ? and press = ? " +
                "and publish_year = ? and author = ?";
        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement queryStmt = conn.prepareStatement(querySql)) {
            for (Book book : books) {
                bindBookInsert(insertStmt, book);
                insertStmt.addBatch();
            }
            insertStmt.executeBatch();
            for (Book book : books) {
                queryStmt.setString(1, book.getCategory());
                queryStmt.setString(2, book.getTitle());
                queryStmt.setString(3, book.getPress());
                queryStmt.setInt(4, book.getPublishYear());
                queryStmt.setString(5, book.getAuthor());
                try (ResultSet rs = queryStmt.executeQuery()) {
                    if (!rs.next()) {
                        rollback(conn);
                        return new ApiResult(false, "Failed to fetch generated book id.");
                    }
                    book.setBookId(rs.getInt("book_id"));
                }
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult removeBook(int bookId) {
        Connection conn = connector.getConn();
        // 只要还有未归还记录，就禁止删书，避免破坏借阅历史的业务约束。
        String borrowSql = "select 1 from borrow where book_id = ? and return_time = 0 limit 1";
        String deleteSql = "delete from book where book_id = ?";
        try (PreparedStatement borrowStmt = conn.prepareStatement(borrowSql);
             PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
            borrowStmt.setInt(1, bookId);
            try (ResultSet rs = borrowStmt.executeQuery()) {
                if (rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Book has unreturned borrows.");
                }
            }
            deleteStmt.setInt(1, bookId);
            if (deleteStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Book does not exist.");
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult modifyBookInfo(Book book) {
        Connection conn = connector.getConn();
        // 这里只允许修改图书基本信息，book_id 和 stock 不在这条更新语句中出现。
        String updateSql = "update book set category = ?, title = ?, press = ?, publish_year = ?, " +
                "author = ?, price = ? where book_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            boolean exists = bookExists(conn, book.getBookId());
            if (!exists) {
                rollback(conn);
                return new ApiResult(false, "Book does not exist.");
            }
            stmt.setString(1, book.getCategory());
            stmt.setString(2, book.getTitle());
            stmt.setString(3, book.getPress());
            stmt.setInt(4, book.getPublishYear());
            stmt.setString(5, book.getAuthor());
            stmt.setDouble(6, book.getPrice());
            stmt.setInt(7, book.getBookId());
            int affected = stmt.executeUpdate();
            if (affected == 1) {
                commit(conn);
                return new ApiResult(true, null);
            }
            rollback(conn);
            return new ApiResult(false, "Failed to modify book.");
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult queryBook(BookQueryConditions conditions) {
        Connection conn = connector.getConn();
        // 动态拼装 where 条件，但具体值仍然全部走 PreparedStatement 绑定，避免 SQL 注入。
        StringBuilder sql = new StringBuilder(
                "select book_id, category, title, press, publish_year, author, price, stock from book where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (conditions.getCategory() != null) {
            sql.append(" and category = ?");
            params.add(conditions.getCategory());
        }
        if (conditions.getTitle() != null) {
            sql.append(" and title like ?");
            params.add("%" + conditions.getTitle() + "%");
        }
        if (conditions.getPress() != null) {
            sql.append(" and press like ?");
            params.add("%" + conditions.getPress() + "%");
        }
        if (conditions.getMinPublishYear() != null) {
            sql.append(" and publish_year >= ?");
            params.add(conditions.getMinPublishYear());
        }
        if (conditions.getMaxPublishYear() != null) {
            sql.append(" and publish_year <= ?");
            params.add(conditions.getMaxPublishYear());
        }
        if (conditions.getAuthor() != null) {
            sql.append(" and author like ?");
            params.add("%" + conditions.getAuthor() + "%");
        }
        if (conditions.getMinPrice() != null) {
            sql.append(" and price >= ?");
            params.add(conditions.getMinPrice());
        }
        if (conditions.getMaxPrice() != null) {
            sql.append(" and price <= ?");
            params.add(conditions.getMaxPrice());
        }
        // 排序字段来自枚举白名单，同时追加 book_id 作为稳定的次排序键。
        sql.append(" order by ")
                .append(conditions.getSortBy().getValue())
                .append(" ")
                .append(conditions.getSortOrder().getValue())
                .append(", book_id asc");
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            List<Book> books = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    books.add(extractBook(rs));
                }
            }
            commit(conn);
            return new ApiResult(true, null, new BookQueryResults(books));
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult borrowBook(Borrow borrow) {
        Connection conn = connector.getConn();
        String cardSql = "select 1 from card where card_id = ?";
        // 对目标图书做当前读加锁，避免并发借最后一本书时出现超卖。
        String bookSql = "select stock from book where book_id = ? for update";
        String borrowSql = "select 1 from borrow where card_id = ? and book_id = ? and return_time = 0 limit 1";
        String insertSql = "insert into borrow(card_id, book_id, borrow_time, return_time) values (?, ?, ?, 0)";
        String updateSql = "update book set stock = stock - 1 where book_id = ?";
        try (PreparedStatement cardStmt = conn.prepareStatement(cardSql);
             PreparedStatement bookStmt = conn.prepareStatement(bookSql);
             PreparedStatement borrowStmt = conn.prepareStatement(borrowSql);
             PreparedStatement insertStmt = conn.prepareStatement(insertSql);
             PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            cardStmt.setInt(1, borrow.getCardId());
            try (ResultSet rs = cardStmt.executeQuery()) {
                if (!rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Card does not exist.");
                }
            }
            bookStmt.setInt(1, borrow.getBookId());
            int stock;
            try (ResultSet rs = bookStmt.executeQuery()) {
                if (!rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Book does not exist.");
                }
                stock = rs.getInt("stock");
            }
            if (stock <= 0) {
                rollback(conn);
                return new ApiResult(false, "Book out of stock.");
            }
            // 同一张卡在未归还前不能重复借同一本书。
            borrowStmt.setInt(1, borrow.getCardId());
            borrowStmt.setInt(2, borrow.getBookId());
            try (ResultSet rs = borrowStmt.executeQuery()) {
                if (rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Book is already borrowed by the card.");
                }
            }
            insertStmt.setInt(1, borrow.getCardId());
            insertStmt.setInt(2, borrow.getBookId());
            insertStmt.setLong(3, borrow.getBorrowTime());
            if (insertStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to insert borrow record.");
            }
            updateStmt.setInt(1, borrow.getBookId());
            if (updateStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to update book stock.");
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult returnBook(Borrow borrow) {
        Connection conn = connector.getConn();
        // 先锁书，再锁未归还记录，保证归还时库存和借阅记录一起更新。
        String bookSql = "select stock from book where book_id = ? for update";
        String recordSql = "select borrow_time from borrow where card_id = ? and book_id = ? and return_time = 0 " +
                "for update";
        String updateBorrowSql = "update borrow set return_time = ? where card_id = ? and book_id = ? " +
                "and borrow_time = ? and return_time = 0";
        String updateBookSql = "update book set stock = stock + 1 where book_id = ?";
        try (PreparedStatement bookStmt = conn.prepareStatement(bookSql);
             PreparedStatement recordStmt = conn.prepareStatement(recordSql);
             PreparedStatement updateBorrowStmt = conn.prepareStatement(updateBorrowSql);
             PreparedStatement updateBookStmt = conn.prepareStatement(updateBookSql)) {
            bookStmt.setInt(1, borrow.getBookId());
            try (ResultSet rs = bookStmt.executeQuery()) {
                if (!rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Book does not exist.");
                }
            }
            recordStmt.setInt(1, borrow.getCardId());
            recordStmt.setInt(2, borrow.getBookId());
            long borrowTime;
            try (ResultSet rs = recordStmt.executeQuery()) {
                if (!rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Borrow record does not exist.");
                }
                borrowTime = rs.getLong("borrow_time");
            }
            if (borrow.getReturnTime() <= borrowTime) {
                rollback(conn);
                return new ApiResult(false, "Return time is invalid.");
            }
            // 精确更新这一次借阅记录，避免误改历史上同卡同书的其他记录。
            updateBorrowStmt.setLong(1, borrow.getReturnTime());
            updateBorrowStmt.setInt(2, borrow.getCardId());
            updateBorrowStmt.setInt(3, borrow.getBookId());
            updateBorrowStmt.setLong(4, borrowTime);
            if (updateBorrowStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to update borrow record.");
            }
            updateBookStmt.setInt(1, borrow.getBookId());
            if (updateBookStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to update book stock.");
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult showBorrowHistory(int cardId) {
        Connection conn = connector.getConn();
        // 直接联表把借阅记录和图书信息一起查出，便于前端和测试统一消费。
        String sql = "select br.card_id, br.book_id, b.category, b.title, b.press, b.publish_year, b.author, " +
                "b.price, br.borrow_time, br.return_time " +
                "from borrow br join book b on br.book_id = b.book_id " +
                "where br.card_id = ? order by br.borrow_time desc, br.book_id asc";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, cardId);
            List<BorrowHistories.Item> items = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BorrowHistories.Item item = new BorrowHistories.Item();
                    item.setCardId(rs.getInt("card_id"));
                    item.setBookId(rs.getInt("book_id"));
                    item.setCategory(rs.getString("category"));
                    item.setTitle(rs.getString("title"));
                    item.setPress(rs.getString("press"));
                    item.setPublishYear(rs.getInt("publish_year"));
                    item.setAuthor(rs.getString("author"));
                    item.setPrice(rs.getDouble("price"));
                    item.setBorrowTime(rs.getLong("borrow_time"));
                    item.setReturnTime(rs.getLong("return_time"));
                    items.add(item);
                }
            }
            commit(conn);
            return new ApiResult(true, null, new BorrowHistories(items));
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult registerCard(Card card) {
        Connection conn = connector.getConn();
        // 借书证和图书入库同理，也依赖数据库唯一约束判重。
        String sql = "insert into card(name, department, type) values (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, card.getName());
            stmt.setString(2, card.getDepartment());
            stmt.setString(3, card.getType().getStr());
            if (stmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Failed to insert card.");
            }
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    // 回填新生成的 card_id，方便后续借书和删除等操作直接复用对象。
                    card.setCardId(rs.getInt(1));
                }
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult removeCard(int cardId) {
        Connection conn = connector.getConn();
        // 未归还图书的借书证不能删除，否则会留下悬空借阅记录。
        String borrowSql = "select 1 from borrow where card_id = ? and return_time = 0 limit 1";
        String deleteSql = "delete from card where card_id = ?";
        try (PreparedStatement borrowStmt = conn.prepareStatement(borrowSql);
             PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
            borrowStmt.setInt(1, cardId);
            try (ResultSet rs = borrowStmt.executeQuery()) {
                if (rs.next()) {
                    rollback(conn);
                    return new ApiResult(false, "Card has unreturned books.");
                }
            }
            deleteStmt.setInt(1, cardId);
            if (deleteStmt.executeUpdate() != 1) {
                rollback(conn);
                return new ApiResult(false, "Card does not exist.");
            }
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult showCards() {
        Connection conn = connector.getConn();
        String sql = "select card_id, name, department, type from card order by card_id asc";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<Card> cards = new ArrayList<>();
            while (rs.next()) {
                Card card = new Card();
                card.setCardId(rs.getInt("card_id"));
                card.setName(rs.getString("name"));
                card.setDepartment(rs.getString("department"));
                card.setType(Card.CardType.values(rs.getString("type")));
                cards.add(card);
            }
            commit(conn);
            return new ApiResult(true, null, new CardList(cards));
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    public ApiResult modifyCardInfo(Card card) {
        Connection conn = connector.getConn();
        // 这是 bonus 额外扩展的接口，基础测试不会直接覆盖，但前端编辑借书证需要它。
        String sql = "update card set name = ?, department = ?, type = ? where card_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!cardExists(conn, card.getCardId())) {
                rollback(conn);
                return new ApiResult(false, "Card does not exist.");
            }
            stmt.setString(1, card.getName());
            stmt.setString(2, card.getDepartment());
            stmt.setString(3, card.getType().getStr());
            stmt.setInt(4, card.getCardId());
            stmt.executeUpdate();
            commit(conn);
            return new ApiResult(true, null);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
    }

    @Override
    public ApiResult resetDatabase() {
        Connection conn = connector.getConn();
        try {
            Statement stmt = conn.createStatement();
            DBInitializer initializer = connector.getConf().getType().getDbInitializer();
            // 每次测试前都重建表结构，保证测试环境干净且可重复。
            stmt.addBatch(initializer.sqlDropBorrow());
            stmt.addBatch(initializer.sqlDropBook());
            stmt.addBatch(initializer.sqlDropCard());
            stmt.addBatch(initializer.sqlCreateCard());
            stmt.addBatch(initializer.sqlCreateBook());
            stmt.addBatch(initializer.sqlCreateBorrow());
            stmt.executeBatch();
            commit(conn);
        } catch (Exception e) {
            rollback(conn);
            return new ApiResult(false, e.getMessage());
        }
        return new ApiResult(true, null);
    }

    private void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void commit(Connection conn) {
        try {
            conn.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 统一封装图书插入参数绑定，避免单本和批量入库各写一遍。
    private void bindBookInsert(PreparedStatement stmt, Book book) throws SQLException {
        stmt.setString(1, book.getCategory());
        stmt.setString(2, book.getTitle());
        stmt.setString(3, book.getPress());
        stmt.setInt(4, book.getPublishYear());
        stmt.setString(5, book.getAuthor());
        stmt.setDouble(6, book.getPrice());
        stmt.setInt(7, book.getStock());
    }

    // 动态查询条件统一按顺序绑定，和 StringBuilder 拼出来的条件一一对应。
    private void bindParams(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }

    // 把 ResultSet 当前行还原成 Book 对象，便于 queryBook 和其他读取逻辑复用。
    private Book extractBook(ResultSet rs) throws SQLException {
        Book book = new Book();
        book.setBookId(rs.getInt("book_id"));
        book.setCategory(rs.getString("category"));
        book.setTitle(rs.getString("title"));
        book.setPress(rs.getString("press"));
        book.setPublishYear(rs.getInt("publish_year"));
        book.setAuthor(rs.getString("author"));
        book.setPrice(rs.getDouble("price"));
        book.setStock(rs.getInt("stock"));
        return book;
    }

    // 用最轻量的 exists 查询判断主键是否存在。
    private boolean bookExists(Connection conn, int bookId) throws SQLException {
        String sql = "select 1 from book where book_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    // 修改借书证前先做主键存在性检查，逻辑和 bookExists 保持一致。
    private boolean cardExists(Connection conn, int cardId) throws SQLException {
        String sql = "select 1 from card where card_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, cardId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

}
