<template>
    <!-- 外层滚动容器：当页面内容较多时，整个图书页仍然可以在组件内部滚动。 -->
    <el-scrollbar height="100%" style="width: 100%; height: 100%;">
        <!-- 页头：左侧是标题，右侧是顶层操作按钮。 -->
        <div class="pageHeader">
            <!-- 页面标题。 -->
            <span class="pageTitle">图书管理</span>
            <!-- 顶部操作按钮区域。 -->
            <div class="headerActions">
                <!-- 刷新按钮：重新按当前查询条件拉取一次图书列表。 -->
                <el-button @click="QueryBooks">刷新</el-button>
                <!-- 批量导入按钮：打开输入文件路径的弹窗。 -->
                <el-button @click="OpenImportDialog">批量导入</el-button>
                <!-- 新增图书按钮：打开图书入库弹窗。 -->
                <el-button type="primary" @click="OpenNewBookDialog">新增图书</el-button>
            </div>
        </div>

        <!-- 查询面板：这一组输入框会真正影响后端 /book 查询。 -->
        <div class="filterPanel">
            <!-- 书名模糊查询，对应后端 queryBook 的 title like 条件。 -->
            <el-input v-model="queryForm.title" placeholder="书名模糊查询" clearable />
            <!-- 类别精确查询，对应后端 queryBook 的 category = 条件。 -->
            <el-input v-model="queryForm.category" placeholder="类别精确查询" clearable />
            <!-- 作者模糊查询，对应后端 queryBook 的 author like 条件。 -->
            <el-input v-model="queryForm.author" placeholder="作者模糊查询" clearable />
            <!-- 排序字段下拉框，对应后端 sortBy。 -->
            <el-select v-model="queryForm.sortBy" placeholder="排序字段">
                <!-- 遍历可选排序字段。 -->
                <el-option v-for="item in sortColumns" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <!-- 排序方式下拉框，对应后端 sortOrder。 -->
            <el-select v-model="queryForm.sortOrder" placeholder="排序方式">
                <!-- 遍历可选排序顺序。 -->
                <el-option v-for="item in sortOrders" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <!-- 查询按钮：把 queryForm 转成请求参数发给后端。 -->
            <el-button type="primary" @click="QueryBooks">查询</el-button>
            <!-- 清空按钮：恢复默认查询条件并重新查询。 -->
            <el-button @click="ClearFilters">清空</el-button>
        </div>

        <!-- 前端二次搜索：只在当前结果中搜索，不重新访问后端。 -->
        <div class="searchRow">
            <el-input v-model="toSearch" :prefix-icon="Search" placeholder="在当前结果中搜索书名、作者或出版社" clearable />
        </div>

        <!-- 图书主表格：真正展示的是经过 filteredBooks 计算后的结果。 -->
        <el-table :data="filteredBooks" height="620" :table-layout="'auto'" style="width: 100%; padding: 0 24px 24px;">
            <!-- 书号列。 -->
            <el-table-column prop="id" label="书号" width="90" sortable />
            <!-- 类别列。 -->
            <el-table-column prop="category" label="类别" width="120" />
            <!-- 书名列。 -->
            <el-table-column prop="title" label="书名" min-width="220" />
            <!-- 出版社列。 -->
            <el-table-column prop="press" label="出版社" min-width="180" />
            <!-- 出版年份列。 -->
            <el-table-column prop="publishYear" label="年份" width="100" sortable />
            <!-- 作者列。 -->
            <el-table-column prop="author" label="作者" width="130" />
            <!-- 价格列。 -->
            <el-table-column prop="price" label="价格" width="100" sortable />
            <!-- 库存列。 -->
            <el-table-column prop="stock" label="库存" width="90" sortable />
            <!-- 操作列：每一行都可以执行编辑、库存、借书、还书、删除。 -->
            <el-table-column label="操作" min-width="330">
                <!-- scope.row 代表当前这一行图书对象。 -->
                <template #default="scope">
                    <!-- 打开编辑弹窗。 -->
                    <el-button size="small" @click="OpenModifyDialog(scope.row)">编辑</el-button>
                    <!-- 打开库存调整弹窗。 -->
                    <el-button size="small" @click="OpenStockDialog(scope.row)">库存</el-button>
                    <!-- 打开借书弹窗。 -->
                    <el-button size="small" type="primary" @click="OpenBorrowDialog(scope.row)">借书</el-button>
                    <!-- 打开还书弹窗。 -->
                    <el-button size="small" type="success" @click="OpenReturnDialog(scope.row)">还书</el-button>
                    <!-- 打开删除确认弹窗。 -->
                    <el-button size="small" type="danger" @click="OpenRemoveDialog(scope.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <!-- 新增图书弹窗：用于图书入库。 -->
        <el-dialog v-model="newBookVisible" title="新增图书" width="38%" align-center>
            <!-- 弹窗中的字段用网格排列。 -->
            <div class="dialogGrid">
                <!-- 类别输入框。 -->
                <div class="field">
                    <span>类别</span>
                    <el-input v-model="newBook.category" clearable />
                </div>
                <!-- 书名输入框。 -->
                <div class="field">
                    <span>书名</span>
                    <el-input v-model="newBook.title" clearable />
                </div>
                <!-- 出版社输入框。 -->
                <div class="field">
                    <span>出版社</span>
                    <el-input v-model="newBook.press" clearable />
                </div>
                <!-- 出版年份输入框。 -->
                <div class="field">
                    <span>出版年份</span>
                    <el-input-number v-model="newBook.publishYear" :min="0" :step="1" />
                </div>
                <!-- 作者输入框。 -->
                <div class="field">
                    <span>作者</span>
                    <el-input v-model="newBook.author" clearable />
                </div>
                <!-- 价格输入框。 -->
                <div class="field">
                    <span>价格</span>
                    <el-input-number v-model="newBook.price" :min="0" :step="0.01" :precision="2" />
                </div>
                <!-- 初始库存输入框。 -->
                <div class="field">
                    <span>库存</span>
                    <el-input-number v-model="newBook.stock" :min="0" :step="1" />
                </div>
            </div>

            <template #footer>
                <span>
                    <!-- 取消：仅关闭弹窗，不提交。 -->
                    <el-button @click="newBookVisible = false">取消</el-button>
                    <!-- 确定：只有表单通过最基本校验时才允许提交。 -->
                    <el-button type="primary" @click="ConfirmNewBook" :disabled="!isBookFormValid(newBook)">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 批量导入弹窗：输入本机文件路径，然后由后端读取文件。 -->
        <el-dialog v-model="importVisible" title="批量导入图书" width="38%" align-center>
            <div class="field">
                <span>导入文件路径</span>
                <!-- 这里直接输入本地文件路径，后端会据此读取 CSV/TSV 文件。 -->
                <el-input v-model="importForm.filePath" placeholder="输入本机可访问的 CSV/TSV 文件路径" clearable />
            </div>
            <!-- 给用户展示模板文件格式。 -->
            <div class="importTips">
                文件格式：每行一条记录，字段顺序为
                <code>category,title,press,publishYear,author,price,stock</code>。
                支持逗号分隔或制表符分隔，可选表头。
            </div>

            <template #footer>
                <span>
                    <!-- 取消导入。 -->
                    <el-button @click="importVisible = false">取消</el-button>
                    <!-- 路径为空时不允许提交。 -->
                    <el-button type="primary" @click="ConfirmImportBooks" :disabled="!importForm.filePath">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 修改图书信息弹窗：只修改基本信息，不修改库存。 -->
        <el-dialog v-model="modifyBookVisible" :title="'修改图书信息（书号: ' + modifyBook.id + '）'" width="38%" align-center>
            <div class="dialogGrid">
                <!-- 类别编辑框。 -->
                <div class="field">
                    <span>类别</span>
                    <el-input v-model="modifyBook.category" clearable />
                </div>
                <!-- 书名编辑框。 -->
                <div class="field">
                    <span>书名</span>
                    <el-input v-model="modifyBook.title" clearable />
                </div>
                <!-- 出版社编辑框。 -->
                <div class="field">
                    <span>出版社</span>
                    <el-input v-model="modifyBook.press" clearable />
                </div>
                <!-- 出版年份编辑框。 -->
                <div class="field">
                    <span>出版年份</span>
                    <el-input-number v-model="modifyBook.publishYear" :min="0" :step="1" />
                </div>
                <!-- 作者编辑框。 -->
                <div class="field">
                    <span>作者</span>
                    <el-input v-model="modifyBook.author" clearable />
                </div>
                <!-- 价格编辑框。 -->
                <div class="field">
                    <span>价格</span>
                    <el-input-number v-model="modifyBook.price" :min="0" :step="0.01" :precision="2" />
                </div>
            </div>

            <template #footer>
                <span>
                    <!-- 取消修改。 -->
                    <el-button @click="modifyBookVisible = false">取消</el-button>
                    <!-- 提交修改。 -->
                    <el-button type="primary" @click="ConfirmModifyBook" :disabled="!isBookFormValid(modifyBook)">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 库存调整弹窗：只需要书号和库存增量。 -->
        <el-dialog v-model="stockVisible" :title="'调整库存（书号: ' + stockForm.id + '）'" width="30%" align-center>
            <div class="field">
                <span>库存增量</span>
                <!-- 正数表示增加库存，负数表示减少库存。 -->
                <el-input-number v-model="stockForm.deltaStock" :step="1" />
            </div>

            <template #footer>
                <span>
                    <!-- 取消库存修改。 -->
                    <el-button @click="stockVisible = false">取消</el-button>
                    <!-- 提交库存修改。 -->
                    <el-button type="primary" @click="ConfirmAdjustStock">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 借书弹窗：输入借书证号，把当前书借出去。 -->
        <el-dialog v-model="borrowVisible" :title="'借书（书号: ' + borrowForm.bookId + '）'" width="30%" align-center>
            <div class="field">
                <span>借书证号</span>
                <!-- 借书时只需要知道是哪张卡来借哪本书。 -->
                <el-input-number v-model="borrowForm.cardId" :min="1" :step="1" />
            </div>

            <template #footer>
                <span>
                    <!-- 取消借书。 -->
                    <el-button @click="borrowVisible = false">取消</el-button>
                    <!-- cardId 为空时不允许提交。 -->
                    <el-button type="primary" @click="ConfirmBorrow" :disabled="!borrowForm.cardId">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 还书弹窗：输入借书证号，把当前书归还。 -->
        <el-dialog v-model="returnVisible" :title="'还书（书号: ' + returnForm.bookId + '）'" width="30%" align-center>
            <div class="field">
                <span>借书证号</span>
                <!-- 还书接口同样只需要 bookId 和 cardId。 -->
                <el-input-number v-model="returnForm.cardId" :min="1" :step="1" />
            </div>

            <template #footer>
                <span>
                    <!-- 取消还书。 -->
                    <el-button @click="returnVisible = false">取消</el-button>
                    <!-- cardId 为空时不允许提交。 -->
                    <el-button type="success" @click="ConfirmReturn" :disabled="!returnForm.cardId">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <!-- 删除确认弹窗：防止误删图书。 -->
        <el-dialog v-model="removeVisible" title="删除图书" width="30%" align-center>
            <!-- 把待删除图书书名展示出来，便于用户确认。 -->
            <span>确定删除《<strong>{{ removeBook.title }}</strong>》吗？</span>
            <template #footer>
                <span>
                    <!-- 取消删除。 -->
                    <el-button @click="removeVisible = false">取消</el-button>
                    <!-- 真正执行删除。 -->
                    <el-button type="danger" @click="ConfirmRemoveBook">删除</el-button>
                </span>
            </template>
        </el-dialog>
    </el-scrollbar>
</template>

<script>
// axios 负责向 bonus 后端发 HTTP 请求。
import axios from 'axios'
// ElMessage 用于操作成功/失败后的消息提示。
import { ElMessage } from 'element-plus'
// 搜索框前面的搜索图标。
import { Search } from '@element-plus/icons-vue'

// 生成一份“空白图书表单”：
// 新建弹窗打开时要用它，重置表单时也要用它。
const emptyBookForm = () => ({
    // 图书主键，新增时通常为 0，修改时会被替换成真实 id。
    id: 0,
    // 图书类别。
    category: '',
    // 图书书名。
    title: '',
    // 出版社。
    press: '',
    // 出版年份，给一个默认值方便用户直接改。
    publishYear: 2024,
    // 作者。
    author: '',
    // 价格。
    price: 0,
    // 默认库存设为 1，避免新建时出现空库存。
    stock: 1
})

export default {
    data() {
        return {
            // 后端返回的原始图书列表。
            books: [],
            // 搜索图标组件，供模板里的 el-input 使用。
            Search,
            // 只对当前查询结果做前端过滤，不会重新访问后端。
            toSearch: '',
            // 真正会传给后端 /book 接口的查询条件。
            queryForm: {
                // 书名模糊查询。
                title: '',
                // 类别精确查询。
                category: '',
                // 作者模糊查询。
                author: '',
                // 默认按书号排序。
                sortBy: 'bookId',
                // 默认升序。
                sortOrder: 'asc'
            },
            // 排序字段候选项，用于下拉框展示。
            sortColumns: [
                { value: 'bookId', label: '书号' },
                { value: 'price', label: '价格' },
                { value: 'publishYear', label: '出版年份' },
                { value: 'stock', label: '库存' }
            ],
            // 排序顺序候选项。
            sortOrders: [
                { value: 'asc', label: '升序' },
                { value: 'desc', label: '降序' }
            ],
            // 新增图书弹窗是否可见。
            newBookVisible: false,
            // 批量导入弹窗是否可见。
            importVisible: false,
            // 修改图书弹窗是否可见。
            modifyBookVisible: false,
            // 调整库存弹窗是否可见。
            stockVisible: false,
            // 借书弹窗是否可见。
            borrowVisible: false,
            // 还书弹窗是否可见。
            returnVisible: false,
            // 删除确认弹窗是否可见。
            removeVisible: false,
            // 新增图书表单对象。
            newBook: emptyBookForm(),
            // 批量导入表单对象，目前只需要文件路径一个字段。
            importForm: {
                filePath: ''
            },
            // 修改图书表单对象。
            // 注意这里不会直接引用表格行对象，而是使用拷贝，避免未提交前污染列表显示。
            modifyBook: emptyBookForm(),
            // 库存修改表单对象，只需要图书 id 和库存增量。
            stockForm: {
                id: 0,
                deltaStock: 0
            },
            // 借书表单对象，只需要 bookId 和 cardId。
            borrowForm: {
                bookId: 0,
                cardId: null
            },
            // 还书表单对象，只需要 bookId 和 cardId。
            returnForm: {
                bookId: 0,
                cardId: null
            },
            // 删除图书时临时保存当前目标图书的 id 和标题。
            removeBook: {
                id: 0,
                title: ''
            }
        }
    },
    computed: {
        filteredBooks() {
            // 如果前端二次搜索框为空，直接返回原始列表。
            if (!this.toSearch) {
                return this.books
            }
            // 否则只在当前结果中搜索书名、作者、出版社。
            return this.books.filter(book =>
                book.title.includes(this.toSearch) ||
                book.author.includes(this.toSearch) ||
                book.press.includes(this.toSearch)
            )
        }
    },
    methods: {
        isBookFormValid(book) {
            // 最基本的前端校验：这几个关键字段不能为空。
            return book.category.length > 0 &&
                book.title.length > 0 &&
                book.press.length > 0 &&
                book.author.length > 0
        },
        buildQueryParams() {
            // 先把排序字段和排序方向放进去，因为它们始终有默认值。
            const params = {
                sortBy: this.queryForm.sortBy,
                sortOrder: this.queryForm.sortOrder
            }
            // 只有用户真的输入了书名时，才把 title 发给后端。
            if (this.queryForm.title) {
                params.title = this.queryForm.title
            }
            // 只有用户真的输入了类别时，才把 category 发给后端。
            if (this.queryForm.category) {
                params.category = this.queryForm.category
            }
            // 只有用户真的输入了作者时，才把 author 发给后端。
            if (this.queryForm.author) {
                params.author = this.queryForm.author
            }
            // 返回最终会传给 axios.get('/book', { params }) 的对象。
            return params
        },
        ClearFilters() {
            // 把查询表单恢复为初始状态。
            this.queryForm = {
                title: '',
                category: '',
                author: '',
                sortBy: 'bookId',
                sortOrder: 'asc'
            }
            // 清空条件后立刻重新查询，让页面回到默认结果。
            this.QueryBooks()
        },
        OpenNewBookDialog() {
            // 每次打开新增弹窗都重置成一份干净的空表单。
            this.newBook = emptyBookForm()
            // 打开新增图书弹窗。
            this.newBookVisible = true
        },
        OpenImportDialog() {
            // 每次打开导入弹窗都清空上一次的文件路径。
            this.importForm = {
                filePath: ''
            }
            // 打开批量导入弹窗。
            this.importVisible = true
        },
        OpenModifyDialog(book) {
            // 用当前行图书数据生成一个拷贝对象，供弹窗编辑。
            this.modifyBook = { ...book, stock: book.stock }
            // 打开修改图书弹窗。
            this.modifyBookVisible = true
        },
        OpenStockDialog(book) {
            // 库存调整只保留本次操作必需的数据：图书 id 和库存增量。
            this.stockForm = {
                id: book.id,
                deltaStock: 0
            }
            // 打开库存调整弹窗。
            this.stockVisible = true
        },
        OpenBorrowDialog(book) {
            // 借书动作只关心当前是哪本书，以及稍后用户输入哪张借书证。
            this.borrowForm = {
                bookId: book.id,
                cardId: null
            }
            // 打开借书弹窗。
            this.borrowVisible = true
        },
        OpenReturnDialog(book) {
            // 还书动作也只关心当前是哪本书，以及稍后输入的借书证号。
            this.returnForm = {
                bookId: book.id,
                cardId: null
            }
            // 打开还书弹窗。
            this.returnVisible = true
        },
        OpenRemoveDialog(book) {
            // 删除确认只需要显示图书标题并保存图书 id。
            this.removeBook = {
                id: book.id,
                title: book.title
            }
            // 打开删除确认弹窗。
            this.removeVisible = true
        },
        async QueryBooks() {
            try {
                // 用 buildQueryParams 生成后端真正需要的查询参数。
                const response = await axios.get('/book', { params: this.buildQueryParams() })
                // 后端返回的是图书数组，直接替换当前 books 列表。
                this.books = response.data
            } catch (error) {
                // 如果后端有明确错误消息就展示后端消息，否则给一个默认提示。
                ElMessage.error(error.response?.data?.message || '图书查询失败')
            }
        },
        async ConfirmNewBook() {
            try {
                // 把新增表单对象映射成后端 /book 需要的 JSON 结构。
                await axios.post('/book', {
                    category: this.newBook.category,
                    title: this.newBook.title,
                    press: this.newBook.press,
                    publishYear: this.newBook.publishYear,
                    author: this.newBook.author,
                    price: this.newBook.price,
                    stock: this.newBook.stock
                })
                // 入库成功后弹出提示。
                ElMessage.success('图书入库成功')
                // 关闭新增弹窗。
                this.newBookVisible = false
                // 重新查列表，保证页面和数据库状态一致。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书入库失败')
            }
        },
        async ConfirmImportBooks() {
            try {
                // 把文件路径发给后端；文件读取和批量事务都在后端完成。
                const response = await axios.post('/book/import', {
                    filePath: this.importForm.filePath
                })
                // 如果后端返回了导入数量，就把数量展示在成功消息里。
                const count = response.data?.payload?.count
                ElMessage.success(count ? `批量导入成功，共导入 ${count} 本图书` : '批量导入成功')
                // 关闭导入弹窗。
                this.importVisible = false
                // 重新查列表，让用户立刻看到导入结果。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '批量导入失败')
            }
        },
        async ConfirmModifyBook() {
            try {
                // 修改图书时只提交基本信息，不提交库存。
                await axios.put('/book', {
                    id: this.modifyBook.id,
                    category: this.modifyBook.category,
                    title: this.modifyBook.title,
                    press: this.modifyBook.press,
                    publishYear: this.modifyBook.publishYear,
                    author: this.modifyBook.author,
                    price: this.modifyBook.price
                })
                // 修改成功提示。
                ElMessage.success('图书信息修改成功')
                // 关闭修改弹窗。
                this.modifyBookVisible = false
                // 重新查询，刷新表格。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书信息修改失败')
            }
        },
        async ConfirmAdjustStock() {
            try {
                // 库存修改统一走 /book/stock，而不是复用 /book 修改接口。
                await axios.post('/book/stock', {
                    bookId: this.stockForm.id,
                    deltaStock: this.stockForm.deltaStock
                })
                // 库存更新成功提示。
                ElMessage.success('库存调整成功')
                // 关闭库存弹窗。
                this.stockVisible = false
                // 重新查询，刷新库存显示。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '库存调整失败')
            }
        },
        async ConfirmBorrow() {
            try {
                // 借书时前端只提供 bookId 和 cardId，借出时间由后端统一设置。
                await axios.post('/borrow', {
                    bookId: this.borrowForm.bookId,
                    cardId: this.borrowForm.cardId
                })
                // 借书成功提示。
                ElMessage.success('借书成功')
                // 关闭借书弹窗。
                this.borrowVisible = false
                // 重新查询，刷新库存和列表状态。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '借书失败')
            }
        },
        async ConfirmReturn() {
            try {
                // 还书时同样只传 bookId 和 cardId，归还时间由后端统一生成。
                await axios.post('/return', {
                    bookId: this.returnForm.bookId,
                    cardId: this.returnForm.cardId
                })
                // 还书成功提示。
                ElMessage.success('还书成功')
                // 关闭还书弹窗。
                this.returnVisible = false
                // 重新查询，刷新库存和状态。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '还书失败')
            }
        },
        async ConfirmRemoveBook() {
            try {
                // 删除图书走 DELETE /book，并在 query 参数里带上 bookId。
                await axios.delete('/book', {
                    params: {
                        bookId: this.removeBook.id
                    }
                })
                // 删除成功提示。
                ElMessage.success('图书删除成功')
                // 关闭删除确认框。
                this.removeVisible = false
                // 重新查询，刷新图书列表。
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书删除失败')
            }
        }
    },
    mounted() {
        // 图书页组件第一次挂载到页面时，先默认查一遍图书列表。
        this.QueryBooks()
    }
}
</script>

<style scoped>
/* 页头：标题和顶部按钮横向排布。 */
.pageHeader {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin: 20px 24px 0;
}

/* 页面标题的字号和字重。 */
.pageTitle {
    font-size: 2em;
    font-weight: bold;
}

/* 头部按钮区域：使用 flex 横向排列按钮。 */
.headerActions {
    display: flex;
    gap: 12px;
}

/* 查询面板：自适应网格布局，窄屏时会自动换行。 */
.filterPanel {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 12px;
    margin: 24px;
}

/* 前端搜索框所在行。 */
.searchRow {
    margin: 0 24px 16px;
}

/* 弹窗内部字段区域：同样使用网格布局。 */
.dialogGrid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 16px;
}

/* 单个字段块：标签和输入框纵向排列。 */
.field {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

/* 批量导入提示文本区域。 */
.importTips {
    margin-top: 16px;
    line-height: 1.6;
    color: #606266;
}
</style>
