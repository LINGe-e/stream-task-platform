# -*- coding: utf-8 -*-
"""生成智流工坊项目介绍 Word 文档"""

from docx import Document
from docx.shared import Pt, Inches, Cm, RGBColor, Emu
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.section import WD_ORIENT
from docx.oxml.ns import qn, nsdecls
from docx.oxml import parse_xml
import os

doc = Document()

# ── 页面设置 ──
section = doc.sections[0]
section.page_width = Inches(8.5)
section.page_height = Inches(11)
section.top_margin = Inches(1.0)
section.bottom_margin = Inches(1.0)
section.left_margin = Inches(1.0)
section.right_margin = Inches(1.0)
section.header_distance = Inches(0.492)
section.footer_distance = Inches(0.492)

# ── 样式定义 ──
style = doc.styles['Normal']
style.font.name = 'Calibri'
style.font.size = Pt(11)
style.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
style.paragraph_format.space_after = Pt(6)
style.paragraph_format.line_spacing = 1.25
# 设置中文字体
style.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

def set_heading_style(style_name, size, color_hex, space_before, space_after):
    s = doc.styles[style_name]
    s.font.name = 'Calibri'
    s.font.size = Pt(size)
    s.font.color.rgb = RGBColor(*bytes.fromhex(color_hex))
    s.font.bold = True
    s.paragraph_format.space_before = Pt(space_before)
    s.paragraph_format.space_after = Pt(space_after)
    s.paragraph_format.line_spacing = 1.15
    s.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

set_heading_style('Heading 1', 18, '1A5276', 18, 8)
set_heading_style('Heading 2', 14, '2E74B5', 14, 6)
set_heading_style('Heading 3', 12, '1F4D78', 10, 4)

# ── 辅助函数 ──
def add_title(text, size=26, color='1A5276'):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(40)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run(text)
    run.font.size = Pt(size)
    run.font.color.rgb = RGBColor(*bytes.fromhex(color))
    run.font.bold = True
    run.font.name = 'Calibri'
    run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    return p

def add_subtitle(text, size=13, color='666666'):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(20)
    run = p.add_run(text)
    run.font.size = Pt(size)
    run.font.color.rgb = RGBColor(*bytes.fromhex(color))
    run.font.name = 'Calibri'
    run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    return p

def add_para(text, bold=False, indent=False):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(6)
    if indent:
        p.paragraph_format.left_indent = Inches(0.25)
    run = p.add_run(text)
    run.font.size = Pt(11)
    run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
    run.font.name = 'Calibri'
    run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    if bold:
        run.font.bold = True
    return p

def add_bullet(text, level=0):
    p = doc.add_paragraph(style='List Bullet')
    p.clear()
    p.paragraph_format.space_after = Pt(3)
    p.paragraph_format.left_indent = Inches(0.25 + level * 0.25)
    run = p.add_run(text)
    run.font.size = Pt(11)
    run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
    run.font.name = 'Calibri'
    run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    return p

def add_table(headers, rows, col_widths=None):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = 'Table Grid'
    table.alignment = WD_TABLE_ALIGNMENT.LEFT

    # 表头
    for i, h in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = ''
        p = cell.paragraphs[0]
        run = p.add_run(h)
        run.font.size = Pt(10)
        run.font.bold = True
        run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
        run.font.name = 'Calibri'
        run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
        # 表头背景色
        shading = parse_xml(f'<w:shd {nsdecls("w")} w:fill="2E74B5"/>')
        cell._tc.get_or_add_tcPr().append(shading)

    # 数据行
    for r_idx, row in enumerate(rows):
        for c_idx, val in enumerate(row):
            cell = table.rows[r_idx + 1].cells[c_idx]
            cell.text = ''
            p = cell.paragraphs[0]
            run = p.add_run(str(val))
            run.font.size = Pt(10)
            run.font.name = 'Calibri'
            run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
            # 斑马纹
            if r_idx % 2 == 1:
                shading = parse_xml(f'<w:shd {nsdecls("w")} w:fill="EBF5FB"/>')
                cell._tc.get_or_add_tcPr().append(shading)

    # 列宽
    if col_widths:
        for row in table.rows:
            for i, w in enumerate(col_widths):
                row.cells[i].width = Inches(w)

    doc.add_paragraph()  # 表后空行
    return table

def add_separator():
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(6)
    pPr = p._p.get_or_add_pPr()
    pBdr = parse_xml(
        f'<w:pBdr {nsdecls("w")}>'
        f'  <w:bottom w:val="single" w:sz="6" w:space="1" w:color="D5D8DC"/>'
        f'</w:pBdr>'
    )
    pPr.append(pBdr)

# ================================================================
# 文档正文
# ================================================================

# ── 封面区 ──
add_title('智流工坊')
add_subtitle('Smart Flow Workshop — 实时数据流处理平台项目介绍')

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(30)
p.paragraph_format.space_after = Pt(4)
run = p.add_run('版本：v1.0　　日期：2026-08-16')
run.font.size = Pt(10)
run.font.color.rgb = RGBColor(0x99, 0x99, 0x99)
run.font.name = 'Calibri'
run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

add_separator()

# ── 1. 项目概述 ──
doc.add_heading('1　项目概述', level=1)
add_para(
    '智流工坊（Smart Flow Workshop）是一个面向数据工程团队的实时数据流处理平台。'
    '平台提供可视化的作业编排界面，支持从多种数据源（文件、数据库、消息队列）采集数据，'
    '经过灵活的转换处理后写入目标存储，同时集成 AI 辅助能力，帮助用户快速构建数据管道。'
)

add_para('平台的核心设计理念：')
add_bullet('低门槛：通过拖拽式画布编排数据流，无需手写代码')
add_bullet('全链路：覆盖数据采集、转换、存储、监控的完整生命周期')
add_bullet('智能化：内置 AI 助手，根据自然语言描述自动生成作业编排方案')
add_bullet('可扩展：基于插件架构的转换算子，支持自定义扩展')

# ── 2. 系统架构 ──
doc.add_heading('2　系统架构', level=1)
add_para(
    '平台采用前后端分离的微服务架构，由三大核心模块组成：前端交互层、后端服务层、流处理引擎层。'
    '基础设施通过 Docker Compose 统一编排，包含 MySQL、Redis、Kafka、Flink 等组件。'
)

doc.add_heading('2.1　架构总览', level=2)
add_table(
    ['层级', '技术栈', '职责'],
    [
        ['前端交互层', 'Vue 3 + Vite + Vue Flow', '用户界面、可视化画布、API 调用'],
        ['后端服务层', 'Spring Boot 3.4 + Java 17', '业务逻辑、认证授权、文件处理、AI 集成'],
        ['流处理引擎', 'Apache Flink 1.14/1.20', '实时数据流执行、Kafka 消费、状态管理'],
        ['数据存储', 'MySQL 8.4 + Redis 7', '持久化存储、缓存与会话管理'],
        ['消息中间件', 'Apache Kafka 3.8', '异步消息传递、数据流接入'],
    ],
    col_widths=[1.3, 2.2, 3.0]
)

doc.add_heading('2.2　部署架构', level=2)
add_para(
    '开发与测试环境通过 docker-compose.yml 一键部署，包含以下容器服务：'
)
add_table(
    ['服务', '镜像', '端口映射', '说明'],
    [
        ['MySQL', 'mysql:8.4', '3307 → 3306', '主数据库，存储任务、用户、文件元数据'],
        ['Redis', 'redis:7-alpine', '6379 → 6379', '缓存与 Token 存储'],
        ['Kafka', 'apache/kafka:3.8.1', '9092 → 9092', '消息队列，数据流接入点'],
        ['JobManager', 'flink:1.20-java17', '8081 → 8081', 'Flink 作业管理节点'],
        ['TaskManager', 'flink:1.20-java17', '—', 'Flink 任务执行节点'],
    ],
    col_widths=[1.2, 2.0, 1.3, 2.0]
)

# ── 3. 前端模块 ──
doc.add_heading('3　前端模块', level=1)
add_para(
    '前端采用 Vue 3 Composition API 构建，使用 Vite 作为开发与构建工具。'
    '核心特色是基于 Vue Flow 的可视化作业编排画布，支持拖拽节点、连线、配置参数。'
)

doc.add_heading('3.1　技术栈详情', level=2)
add_table(
    ['技术', '版本', '用途'],
    [
        ['Vue 3', '3.5.13', '响应式 UI 框架，Composition API'],
        ['Vite', '6.0.5', '开发服务器与生产构建'],
        ['Vue Flow', '1.48.2', '基于 React Flow 的可视化流程编辑器'],
        ['D3.js', '(间接依赖)', '画布缩放、拖拽等底层交互'],
        ['Fetch API', '原生', 'HTTP 请求，Token 认证'],
    ],
    col_widths=[1.5, 1.3, 3.7]
)

doc.add_heading('3.2　页面与组件', level=2)
add_table(
    ['文件', '功能说明'],
    [
        ['App.vue', '主布局框架，侧栏导航，页面路由，概览仪表盘'],
        ['LoginPage.vue', '登录页面，品牌展示，动态背景灯效'],
        ['FlowEditor.vue', '可视化作业编排画布，节点拖拽与连线，AI 辅助生成'],
        ['TaskTable.vue', '任务列表管理，状态筛选，批量操作'],
        ['UserManage.vue', '用户管理（管理员功能），角色分配'],
        ['api.js', '统一 API 封装，Token 管理，错误处理'],
        ['style.css', '全局样式，深色玻璃质感主题，青蓝发光体系'],
    ],
    col_widths=[2.0, 4.5]
)

doc.add_heading('3.3　角色与权限', level=2)
add_para('平台实现基于角色的访问控制（RBAC），支持四种角色：')
add_table(
    ['角色', '可访问页面'],
    [
        ['SADMIN（超级管理员）', '全部页面，含用户管理'],
        ['ADMIN（管理员）', '全部页面，含用户管理'],
        ['USER（普通用户）', '概览、任务管理、作业编排、数据工作区、运行日志'],
        ['OPS_READONLY（运维只读）', '概览、任务管理、数据工作区、Flink 作业、运行日志'],
    ],
    col_widths=[2.0, 4.5]
)

# ── 4. 后端模块 ──
doc.add_heading('4　后端模块', level=1)
add_para(
    '后端基于 Spring Boot 3.4 构建，采用分层架构：Controller → Service → Repository。'
    '提供 RESTful API 供前端调用，同时集成 Kafka、Redis、Flink 等中间件。'
)

doc.add_heading('4.1　技术栈详情', level=2)
add_table(
    ['技术', '版本', '用途'],
    [
        ['Spring Boot', '3.4.1', 'Web 框架、自动配置、依赖注入'],
        ['Spring Data JPA', '(Boot 内置)', 'ORM 持久化，支持 MySQL 与 H2'],
        ['Spring Security Crypto', '(Boot 内置)', '密码加密、Token 认证'],
        ['Spring Data Redis', '(Boot 内置)', 'Redis 缓存集成'],
        ['Spring Kafka', '(Boot 内置)', 'Kafka 生产者/消费者'],
        ['Apache POI', '5.3.0', 'Excel 文件读写'],
        ['Apache Commons CSV', '1.12.0', 'CSV 文件解析'],
        ['Jackson', '(Boot 内置)', 'JSON 序列化/反序列化'],
        ['MySQL Connector', '5.1.49', 'MySQL 数据库驱动'],
        ['H2 Database', '(Boot 内置)', '嵌入式数据库（开发/测试）'],
    ],
    col_widths=[2.0, 1.3, 3.2]
)

doc.add_heading('4.2　包结构与功能', level=2)
add_table(
    ['包名', '职责', '关键类'],
    [
        ['auth', '认证授权、用户管理、Token 管理', 'AuthController, AuthService, UserEntity'],
        ['task', '任务 CRUD、执行调度、状态管理', 'TaskController, TaskService, TaskEntity'],
        ['engine', '数据流执行引擎、转换插件', 'TaskExecutionEngine, TransformPluginRegistry'],
        ['engine.transform', '转换算子插件接口与上下文', 'TransformPlugin, TransformContext'],
        ['file', '文件上传、解析、存储', 'FileController, FileParserService, StoredFile'],
        ['flink', 'Flink 作业提交与管理', 'FlinkController, FlinkTaskService'],
        ['ai', 'AI 辅助服务集成', 'AiAssistController, AiAssistService'],
        ['feedback', '用户反馈收集', 'FeedbackEntity, FeedbackRepository'],
    ],
    col_widths=[1.3, 2.5, 2.7]
)

doc.add_heading('4.3　转换插件体系', level=2)
add_para(
    '引擎采用插件化架构，通过 TransformPlugin 接口实现可扩展的数据转换能力。'
    '每个插件接收 TransformContext，返回 TransformResult，支持链式组合。'
)
add_table(
    ['插件类', '功能', '典型场景'],
    [
        ['FieldMapTransformPlugin', '字段映射与重命名', '将源字段名映射为目标字段名'],
        ['FilterTransformPlugin', '条件过滤', '按表达式筛选数据行'],
        ['DedupTransformPlugin', '数据去重', '按指定字段去除重复记录'],
        ['TypeConvertTransformPlugin', '类型转换', '字符串转数字、日期格式化等'],
        ['MaskTransformPlugin', '数据脱敏', '手机号、身份证号等敏感字段遮罩'],
        ['ConcatTransformPlugin', '字段拼接', '多个字段合并为新字段'],
        ['XmlJsonTransformPlugin', 'XML/JSON 互转', '格式转换适配不同系统'],
    ],
    col_widths=[2.3, 1.5, 2.7]
)

# ── 5. Flink 流处理模块 ──
doc.add_heading('5　Flink 流处理模块', level=1)
add_para(
    'Flink 模块负责实际的流处理作业执行。后端通过 Flink REST API 提交 JAR 包，'
    '查询作业状态并发送取消请求；Checkpoint、吞吐量和详细异常信息以 Flink Dashboard 为准。'
)

doc.add_heading('5.1　技术栈详情', level=2)
add_table(
    ['技术', '版本', '用途'],
    [
        ['Apache Flink', '1.14.6（编译）/ 1.20（运行时）', '流处理引擎'],
        ['Flink Kafka Connector', '1.14.6', 'Kafka Source/Sink 接入'],
        ['MySQL Connector/J', '8.3.0', 'JDBC 数据源接入'],
        ['Jackson Databind', '2.17.0', '数据序列化'],
        ['Maven Shade Plugin', '3.5.1', 'Fat JAR 打包'],
    ],
    col_widths=[2.2, 2.5, 1.8]
)

doc.add_heading('5.2　当前动态管道实现', level=2)
add_para('Flink 作业由后端生成配置并提交给 DynamicPipelineJob 执行：')
add_bullet('Source：Kafka 与 MySQL、OceanBase、PostgreSQL、Oracle 等 JDBC 数据源')
add_bullet('Transform：字段映射、条件过滤、类型转换、字段拼接和去重')
add_bullet('Sink：Kafka 与外部 JDBC 数据库；文件和 Redis 节点当前回退到 MySQL 配置')

# ── 6. 数据源与数据目标 ──
doc.add_heading('6　数据源与数据目标', level=1)
add_para('平台支持多种数据源和数据目标的接入与写出：')

doc.add_heading('6.1　数据源（Source）', level=2)
add_table(
    ['类型', '说明', '技术实现'],
    [
        ['JSON 文件', '读取 JSON 格式数据文件', 'FileParserService + Jackson'],
        ['CSV 文件', '读取 CSV 格式数据文件', 'FileParserService + Commons CSV'],
        ['Excel 文件', '读取 .xlsx/.xls 文件', 'FileParserService + Apache POI'],
        ['MySQL 表', '从 MySQL 数据库读取', 'Spring Data JPA / JDBC'],
        ['Kafka 消息', '消费 Kafka Topic 消息', 'KafkaSourceService + Spring Kafka'],
    ],
    col_widths=[1.3, 2.3, 2.9]
)

doc.add_heading('6.2　数据目标（Sink）', level=2)
add_table(
    ['类型', '说明', '技术实现'],
    [
        ['MySQL 表', '写入 MySQL 数据库', 'DynamicTableService + JPA'],
        ['Redis', '写入 Redis 缓存', 'RedisSinkService + Spring Data Redis'],
        ['Kafka Topic', '发布到 Kafka Topic', 'KafkaSinkService + Spring Kafka'],
        ['文件导出', '导出为 CSV/Excel 文件', 'ExportService + POI/Commons CSV'],
    ],
    col_widths=[1.3, 2.3, 2.9]
)

# ── 7. AI 辅助功能 ──
doc.add_heading('7　AI 辅助功能', level=1)
add_para(
    '平台集成 AI 辅助能力，基于阿里云百炼（DashScope）大模型服务，'
    '默认使用 qwen-plus 模型。AI 助手可以：'
)
add_bullet('根据自然语言描述生成作业编排方案（如"读取 CSV，过滤金额大于 1000，脱敏后写入 MySQL"）')
add_bullet('分析当前页面上下文，提供操作建议')
add_bullet('支持图片和文件附件作为上下文输入')

add_para('AI 集成的关键设计：')
add_bullet('安全约束：AI 仅提供建议，不允许直接操作任务或修改数据')
add_bullet('可配置：通过环境变量 DASHSCOPE_API_KEY 或 A22_AI_API_KEY 配置')
add_bullet('多页面可用：任务管理、作业编排、运行日志、数据工作区、Flink 作业页面均可唤起 AI')

# ── 8. 核心业务流程 ──
doc.add_heading('8　核心业务流程', level=1)

doc.add_heading('8.1　任务生命周期', level=2)
add_para('任务从创建到完成经历以下状态流转：')
add_table(
    ['状态', '说明'],
    [
        ['PUBLISHED', '任务已发布，等待执行'],
        ['RUNNING', '任务正在运行中'],
        ['STOPPED', '任务已停止'],
        ['SUCCEEDED', '任务执行成功'],
        ['FAILED', '任务执行失败'],
    ],
    col_widths=[1.5, 5.0]
)

doc.add_heading('8.2　作业编排流程', level=2)
add_para('典型的数据处理流程：')
add_bullet('用户在可视化画布中拖拽添加 Source 节点（如 CSV 输入）')
add_bullet('添加 Transform 节点（如字段映射、条件过滤、数据脱敏）')
add_bullet('添加 Sink 节点（如写入 MySQL、发布到 Kafka）')
add_bullet('通过连线定义数据流向，系统自动验证 DAG 图合法性')
add_bullet('保存并发布任务，后端生成 Flink Pipeline 配置并提交执行')

# ── 9. 开发与部署指南 ──
doc.add_heading('9　开发与部署指南', level=1)

doc.add_heading('9.1　环境要求', level=2)
add_table(
    ['组件', '版本要求'],
    [
        ['Java', '17+'],
        ['Node.js', '24.15.0'],
        ['Maven', '3.8+'],
        ['Docker', '24+ / Docker Compose V2'],
    ],
    col_widths=[2.0, 4.5]
)

doc.add_heading('9.2　快速启动', level=2)
add_para('1. 启动基础设施（在项目根目录）：')
add_para('docker-compose up -d', indent=True)
add_para('2. 启动后端：')
add_para('cd backend && mvn spring-boot:run', indent=True)
add_para('3. 启动前端：')
add_para('cd frontend && npm ci && npm run dev', indent=True)
add_para('4. 访问 http://localhost:5173 即可使用平台。')

doc.add_heading('9.3　关键配置', level=2)
add_table(
    ['配置项', '默认值', '说明'],
    [
        ['DASHSCOPE_API_KEY', '（空）', '阿里云百炼 API Key，启用 AI 功能'],
        ['MySQL 端口', '3307', '容器映射端口，避免与本机冲突'],
        ['Kafka 端口', '9092', '外部访问端口'],
        ['Flink Dashboard', 'http://localhost:8081', 'Flink Web UI'],
    ],
    col_widths=[2.0, 1.5, 3.0]
)

# ── 10. 项目结构 ──
doc.add_heading('10　项目目录结构', level=1)
add_table(
    ['目录', '说明'],
    [
        ['backend/', 'Spring Boot 后端服务'],
        ['frontend/', 'Vue 3 前端应用'],
        ['flink-jobs/', 'Flink 流处理作业'],
        ['docs/', '项目文档'],
        ['tools/', '开发辅助脚本（数据修复、插件编解码等）'],
        ['samples/', '示例数据文件'],
        ['test-files/', '测试用数据文件'],
        ['img/', '项目图片资源'],
        ['docker-compose.yml', '容器编排配置'],
    ],
    col_widths=[2.0, 4.5]
)

# ── 页脚 ──
add_separator()
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(10)
run = p.add_run('— 智流工坊 · Smart Flow Workshop —')
run.font.size = Pt(9)
run.font.color.rgb = RGBColor(0x99, 0x99, 0x99)
run.font.name = 'Calibri'
run.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

# ── 保存 ──
out_path = r'D:\a22\docs\智流工坊-项目介绍.docx'
doc.save(out_path)
print(f'文档已保存: {out_path}')
