<template>
    <el-scrollbar height="100%" style="width: 100%; height: 100%;">
        <div class="pageHeader">
            <span class="pageTitle">图书管理</span>
            <div class="headerActions">
                <el-button @click="QueryBooks">刷新</el-button>
                <el-button @click="OpenImportDialog">批量导入</el-button>
                <el-button type="primary" @click="OpenNewBookDialog">新增图书</el-button>
            </div>
        </div>

        <div class="filterPanel">
            <el-input v-model="queryForm.title" placeholder="书名模糊查询" clearable />
            <el-input v-model="queryForm.category" placeholder="类别精确查询" clearable />
            <el-input v-model="queryForm.author" placeholder="作者模糊查询" clearable />
            <el-select v-model="queryForm.sortBy" placeholder="排序字段">
                <el-option v-for="item in sortColumns" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-select v-model="queryForm.sortOrder" placeholder="排序方式">
                <el-option v-for="item in sortOrders" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button type="primary" @click="QueryBooks">查询</el-button>
            <el-button @click="ClearFilters">清空</el-button>
        </div>

        <div class="searchRow">
            <el-input v-model="toSearch" :prefix-icon="Search" placeholder="在当前结果中搜索书名、作者或出版社" clearable />
        </div>

        <el-table :data="filteredBooks" height="620" :table-layout="'auto'" style="width: 100%; padding: 0 24px 24px;">
            <el-table-column prop="id" label="书号" width="90" sortable />
            <el-table-column prop="category" label="类别" width="120" />
            <el-table-column prop="title" label="书名" min-width="220" />
            <el-table-column prop="press" label="出版社" min-width="180" />
            <el-table-column prop="publishYear" label="年份" width="100" sortable />
            <el-table-column prop="author" label="作者" width="130" />
            <el-table-column prop="price" label="价格" width="100" sortable />
            <el-table-column prop="stock" label="库存" width="90" sortable />
            <el-table-column label="操作" min-width="330">
                <template #default="scope">
                    <el-button size="small" @click="OpenModifyDialog(scope.row)">编辑</el-button>
                    <el-button size="small" @click="OpenStockDialog(scope.row)">库存</el-button>
                    <el-button size="small" type="primary" @click="OpenBorrowDialog(scope.row)">借书</el-button>
                    <el-button size="small" type="success" @click="OpenReturnDialog(scope.row)">还书</el-button>
                    <el-button size="small" type="danger" @click="OpenRemoveDialog(scope.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <el-dialog v-model="newBookVisible" title="新增图书" width="38%" align-center>
            <div class="dialogGrid">
                <div class="field">
                    <span>类别</span>
                    <el-input v-model="newBook.category" clearable />
                </div>
                <div class="field">
                    <span>书名</span>
                    <el-input v-model="newBook.title" clearable />
                </div>
                <div class="field">
                    <span>出版社</span>
                    <el-input v-model="newBook.press" clearable />
                </div>
                <div class="field">
                    <span>出版年份</span>
                    <el-input-number v-model="newBook.publishYear" :min="0" :step="1" />
                </div>
                <div class="field">
                    <span>作者</span>
                    <el-input v-model="newBook.author" clearable />
                </div>
                <div class="field">
                    <span>价格</span>
                    <el-input-number v-model="newBook.price" :min="0" :step="0.01" :precision="2" />
                </div>
                <div class="field">
                    <span>库存</span>
                    <el-input-number v-model="newBook.stock" :min="0" :step="1" />
                </div>
            </div>

            <template #footer>
                <span>
                    <el-button @click="newBookVisible = false">取消</el-button>
                    <el-button type="primary" @click="ConfirmNewBook" :disabled="!isBookFormValid(newBook)">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="importVisible" title="批量导入图书" width="38%" align-center>
            <div class="field">
                <span>导入文件路径</span>
                <el-input v-model="importForm.filePath" placeholder="输入本机可访问的 CSV/TSV 文件路径" clearable />
            </div>
            <div class="importTips">
                文件格式：每行一条记录，字段顺序为
                <code>category,title,press,publishYear,author,price,stock</code>。
                支持逗号分隔或制表符分隔，可选表头。
            </div>

            <template #footer>
                <span>
                    <el-button @click="importVisible = false">取消</el-button>
                    <el-button type="primary" @click="ConfirmImportBooks" :disabled="!importForm.filePath">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="modifyBookVisible" :title="'修改图书信息（书号: ' + modifyBook.id + '）'" width="38%" align-center>
            <div class="dialogGrid">
                <div class="field">
                    <span>类别</span>
                    <el-input v-model="modifyBook.category" clearable />
                </div>
                <div class="field">
                    <span>书名</span>
                    <el-input v-model="modifyBook.title" clearable />
                </div>
                <div class="field">
                    <span>出版社</span>
                    <el-input v-model="modifyBook.press" clearable />
                </div>
                <div class="field">
                    <span>出版年份</span>
                    <el-input-number v-model="modifyBook.publishYear" :min="0" :step="1" />
                </div>
                <div class="field">
                    <span>作者</span>
                    <el-input v-model="modifyBook.author" clearable />
                </div>
                <div class="field">
                    <span>价格</span>
                    <el-input-number v-model="modifyBook.price" :min="0" :step="0.01" :precision="2" />
                </div>
            </div>

            <template #footer>
                <span>
                    <el-button @click="modifyBookVisible = false">取消</el-button>
                    <el-button type="primary" @click="ConfirmModifyBook" :disabled="!isBookFormValid(modifyBook)">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="stockVisible" :title="'调整库存（书号: ' + stockForm.id + '）'" width="30%" align-center>
            <div class="field">
                <span>库存增量</span>
                <el-input-number v-model="stockForm.deltaStock" :step="1" />
            </div>

            <template #footer>
                <span>
                    <el-button @click="stockVisible = false">取消</el-button>
                    <el-button type="primary" @click="ConfirmAdjustStock">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="borrowVisible" :title="'借书（书号: ' + borrowForm.bookId + '）'" width="30%" align-center>
            <div class="field">
                <span>借书证号</span>
                <el-input-number v-model="borrowForm.cardId" :min="1" :step="1" />
            </div>

            <template #footer>
                <span>
                    <el-button @click="borrowVisible = false">取消</el-button>
                    <el-button type="primary" @click="ConfirmBorrow" :disabled="!borrowForm.cardId">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="returnVisible" :title="'还书（书号: ' + returnForm.bookId + '）'" width="30%" align-center>
            <div class="field">
                <span>借书证号</span>
                <el-input-number v-model="returnForm.cardId" :min="1" :step="1" />
            </div>

            <template #footer>
                <span>
                    <el-button @click="returnVisible = false">取消</el-button>
                    <el-button type="success" @click="ConfirmReturn" :disabled="!returnForm.cardId">确定</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="removeVisible" title="删除图书" width="30%" align-center>
            <span>确定删除《<strong>{{ removeBook.title }}</strong>》吗？</span>
            <template #footer>
                <span>
                    <el-button @click="removeVisible = false">取消</el-button>
                    <el-button type="danger" @click="ConfirmRemoveBook">删除</el-button>
                </span>
            </template>
        </el-dialog>
    </el-scrollbar>
</template>

<script>
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

// 新建和重置图书表单时统一使用同一份默认结构，避免多个弹窗各写一份初始值。
const emptyBookForm = () => ({
    id: 0,
    category: '',
    title: '',
    press: '',
    publishYear: 2024,
    author: '',
    price: 0,
    stock: 1
})

export default {
    data() {
        return {
            books: [], // 当前页面展示的原始图书列表
            Search,
            toSearch: '', // 只对当前结果做前端搜索，不会重新发请求
            queryForm: { // 这部分字段会被 buildQueryParams 转成后端查询参数
                title: '',
                category: '',
                author: '',
                sortBy: 'bookId',
                sortOrder: 'asc'
            },
            sortColumns: [
                { value: 'bookId', label: '书号' },
                { value: 'price', label: '价格' },
                { value: 'publishYear', label: '出版年份' },
                { value: 'stock', label: '库存' }
            ],
            sortOrders: [
                { value: 'asc', label: '升序' },
                { value: 'desc', label: '降序' }
            ],
            newBookVisible: false,
            importVisible: false,
            modifyBookVisible: false,
            stockVisible: false,
            borrowVisible: false,
            returnVisible: false,
            removeVisible: false,
            newBook: emptyBookForm(), // 新增图书表单
            importForm: {
                filePath: ''
            },
            modifyBook: emptyBookForm(), // 编辑时使用列表项拷贝，避免用户未提交前直接改动表格数据
            stockForm: {
                id: 0,
                deltaStock: 0
            },
            borrowForm: {
                bookId: 0,
                cardId: null
            },
            returnForm: {
                bookId: 0,
                cardId: null
            },
            removeBook: {
                id: 0,
                title: ''
            }
        }
    },
    computed: {
        filteredBooks() {
            if (!this.toSearch) {
                return this.books
            }
            // 对已查询到的结果做前端二次过滤，减少每次输入都重新请求后端的开销。
            return this.books.filter(book =>
                book.title.includes(this.toSearch) ||
                book.author.includes(this.toSearch) ||
                book.press.includes(this.toSearch)
            )
        }
    },
    methods: {
        isBookFormValid(book) {
            return book.category.length > 0 &&
                book.title.length > 0 &&
                book.press.length > 0 &&
                book.author.length > 0
        },
        buildQueryParams() {
            const params = {
                sortBy: this.queryForm.sortBy,
                sortOrder: this.queryForm.sortOrder
            }
            // 只把用户真正填写过的条件发给后端，未填写的条件保持为空。
            if (this.queryForm.title) {
                params.title = this.queryForm.title
            }
            if (this.queryForm.category) {
                params.category = this.queryForm.category
            }
            if (this.queryForm.author) {
                params.author = this.queryForm.author
            }
            return params
        },
        ClearFilters() {
            this.queryForm = {
                title: '',
                category: '',
                author: '',
                sortBy: 'bookId',
                sortOrder: 'asc'
            }
            // 清空后立即刷新，让页面回到默认查询结果。
            this.QueryBooks()
        },
        OpenNewBookDialog() {
            this.newBook = emptyBookForm()
            this.newBookVisible = true
        },
        OpenImportDialog() {
            // 每次打开批量导入弹窗都重置路径，避免保留上一次输入。
            this.importForm = {
                filePath: ''
            }
            this.importVisible = true
        },
        OpenModifyDialog(book) {
            this.modifyBook = { ...book, stock: book.stock }
            this.modifyBookVisible = true
        },
        OpenStockDialog(book) {
            // 库存调整只需要书号和库存增量，避免把整本书对象都塞进表单状态。
            this.stockForm = {
                id: book.id,
                deltaStock: 0
            }
            this.stockVisible = true
        },
        OpenBorrowDialog(book) {
            this.borrowForm = {
                bookId: book.id,
                cardId: null
            }
            this.borrowVisible = true
        },
        OpenReturnDialog(book) {
            this.returnForm = {
                bookId: book.id,
                cardId: null
            }
            this.returnVisible = true
        },
        OpenRemoveDialog(book) {
            this.removeBook = {
                id: book.id,
                title: book.title
            }
            this.removeVisible = true
        },
        async QueryBooks() {
            try {
                // 图书查询统一走 GET /book，筛选和排序都由后端 queryBook 处理。
                const response = await axios.get('/book', { params: this.buildQueryParams() })
                this.books = response.data
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书查询失败')
            }
        },
        async ConfirmNewBook() {
            try {
                // 新增图书时把表单字段映射成后端需要的 JSON 结构。
                await axios.post('/book', {
                    category: this.newBook.category,
                    title: this.newBook.title,
                    press: this.newBook.press,
                    publishYear: this.newBook.publishYear,
                    author: this.newBook.author,
                    price: this.newBook.price,
                    stock: this.newBook.stock
                })
                ElMessage.success('图书入库成功')
                this.newBookVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书入库失败')
            }
        },
        async ConfirmImportBooks() {
            try {
                // 文件路径只作为外层输入，真正的批量事务在后端 storeBook(List<Book>) 中完成。
                const response = await axios.post('/book/import', {
                    filePath: this.importForm.filePath
                })
                const count = response.data?.payload?.count
                ElMessage.success(count ? `批量导入成功，共导入 ${count} 本图书` : '批量导入成功')
                this.importVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '批量导入失败')
            }
        },
        async ConfirmModifyBook() {
            try {
                // 修改图书时不传 stock，因为后端 modifyBookInfo 只负责基本信息修改。
                await axios.put('/book', {
                    id: this.modifyBook.id,
                    category: this.modifyBook.category,
                    title: this.modifyBook.title,
                    press: this.modifyBook.press,
                    publishYear: this.modifyBook.publishYear,
                    author: this.modifyBook.author,
                    price: this.modifyBook.price
                })
                ElMessage.success('图书信息修改成功')
                this.modifyBookVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书信息修改失败')
            }
        },
        async ConfirmAdjustStock() {
            try {
                // 增减库存统一走独立接口，和修改图书基本信息分开。
                await axios.post('/book/stock', {
                    bookId: this.stockForm.id,
                    deltaStock: this.stockForm.deltaStock
                })
                ElMessage.success('库存调整成功')
                this.stockVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '库存调整失败')
            }
        },
        async ConfirmBorrow() {
            try {
                // 借书时只传 cardId 和 bookId，借出时间由后端生成。
                await axios.post('/borrow', {
                    bookId: this.borrowForm.bookId,
                    cardId: this.borrowForm.cardId
                })
                ElMessage.success('借书成功')
                this.borrowVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '借书失败')
            }
        },
        async ConfirmReturn() {
            try {
                // 还书接口同样只接收 cardId 和 bookId，归还时间由后端统一生成。
                await axios.post('/return', {
                    bookId: this.returnForm.bookId,
                    cardId: this.returnForm.cardId
                })
                ElMessage.success('还书成功')
                this.returnVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '还书失败')
            }
        },
        async ConfirmRemoveBook() {
            try {
                // 删除图书使用 query 参数，对应后端 DELETE /book?bookId=xxx。
                await axios.delete('/book', {
                    params: {
                        bookId: this.removeBook.id
                    }
                })
                ElMessage.success('图书删除成功')
                this.removeVisible = false
                this.QueryBooks()
            } catch (error) {
                ElMessage.error(error.response?.data?.message || '图书删除失败')
            }
        }
    },
    mounted() {
        // 图书页首次加载时先拉一遍默认列表。
        this.QueryBooks()
    }
}
</script>

<style scoped>
.pageHeader {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin: 20px 24px 0;
}

.pageTitle {
    font-size: 2em;
    font-weight: bold;
}

.headerActions {
    display: flex;
    gap: 12px;
}

.filterPanel {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 12px;
    margin: 24px;
}

.searchRow {
    margin: 0 24px 16px;
}

.dialogGrid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 16px;
}

.field {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.importTips {
    margin-top: 16px;
    line-height: 1.6;
    color: #606266;
}
</style>
