# -*- coding: utf-8 -*-
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn

doc = Document()

# 设置默认字体
style = doc.styles['Normal']
font = style.font
font.name = 'Microsoft YaHei'
font.size = Pt(11)
style.element.rPr.rFonts.set(qn('w:eastAsia'), 'Microsoft YaHei')

# 标题
title = doc.add_heading('A22 数据平台 - 多数据源扩展功能说明', 0)
title.alignment = WD_ALIGN_PARAGRAPH.CENTER

# 文档信息
doc.add_paragraph('')
info = doc.add_paragraph()
info.add_run('文档版本：').bold = True
info.add_run('v1.0')
info.add_run('    ')
info.add_run('更新日期：').bold = True
info.add_run('2026-08-28')
info.alignment = WD_ALIGN_PARAGRAPH.RIGHT

doc.add_page_break()

# 目录
doc.add_heading('目录', 1)
toc_items = [
    '1. 功能概述',
    '2. 新增数据源',
    '   2.1 Redis 数据源',
    '   2.2 PostgreSQL 数据源',
    '   2.3 Oracle 数据源',
    '   2.4 JDBC 通用数据源',
    '   2.5 HDFS 数据源',
    '   2.6 S3/MinIO/OSS 数据源',
    '3. 新增处理控件',
    '   3.1 Redis 查询补全',
    '4. 安全与稳定性修复',
    '5. 使用指南',
    '6. 配置示例',
]
for item in toc_items:
    doc.add_paragraph(item, style='List Number')

doc.add_page_break()

# 第1章 功能概述
doc.add_heading('1. 功能概述', 1)
doc.add_paragraph(
    '本次扩展为 A22 数据平台新增了多种数据源支持和处理控件，使平台能够：'
)
features = [
    '从 Redis、PostgreSQL、Oracle、HDFS、S3 等外部系统读取数据',
    '将处理结果写入上述外部系统',
    '根据字段值查询 Redis 补充数据信息',
    '安全、稳定地处理大规模数据',
]
for f in features:
    doc.add_paragraph(f, style='List Bullet')

doc.add_paragraph('')
doc.add_paragraph(
    '同时修复了多项安全和稳定性问题，包括 SQL 注入防护、正则 DoS 防护、资源泄漏修复等。'
)

doc.add_page_break()

# 第2章 新增数据源
doc.add_heading('2. 新增数据源', 1)

# 2.1 Redis
doc.add_heading('2.1 Redis 数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('从 Redis 读取数据，支持 List、Hash、Set、Keys 模式匹配等多种数据类型。')

doc.add_heading('能怎么干', 3)
table = doc.add_table(rows=5, cols=3)
table.style = 'Light Grid Accent 1'
table.alignment = WD_TABLE_ALIGNMENT.CENTER
headers = ['读取类型', '说明', '适用场景']
for i, h in enumerate(headers):
    table.rows[0].cells[i].text = h
    table.rows[0].cells[i].paragraphs[0].runs[0].bold = True

data = [
    ['List', '从 List 左端读取（非破坏性）', '消息队列、日志收集'],
    ['Hash', '读取整个 Hash 作为一条记录', '用户信息、配置存储'],
    ['Set', '读取 Set 中的成员', '标签、去重数据'],
    ['Keys', '使用 SCAN 模式匹配 Key', '批量数据发现、调试'],
]
for i, row in enumerate(data):
    for j, cell in enumerate(row):
        table.rows[i+1].cells[j].text = cell

doc.add_paragraph('')
doc.add_heading('画布配置', 4)
doc.add_paragraph('节点类型：Source（输入）')
doc.add_paragraph('控件 kind：redis')
doc.add_paragraph('前端图标：Redis')

# 2.2 PostgreSQL
doc.add_heading('2.2 PostgreSQL 数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('连接 PostgreSQL 数据库，读取或写入表数据。')

doc.add_heading('能怎么干', 3)
items = [
    '读取：指定表名和读取限制，自动生成 SELECT 语句',
    '写入：批量插入数据到目标表，自动建表',
]
for item in items:
    doc.add_paragraph(item, style='List Bullet')

doc.add_paragraph('')
doc.add_heading('画布配置', 4)
table = doc.add_table(rows=5, cols=2)
table.style = 'Light Grid Accent 1'
config_data = [
    ['配置项', '说明'],
    ['url', 'jdbc:postgresql://host:port/database'],
    ['username', '数据库用户名'],
    ['password', '数据库密码'],
    ['table', '表名'],
]
for i, row in enumerate(config_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

# 2.3 Oracle
doc.add_heading('2.3 Oracle 数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('连接 Oracle 数据库，读取或写入表数据。')

doc.add_heading('能怎么干', 3)
doc.add_paragraph('与 PostgreSQL 使用相同的通用 JDBC 服务，自动识别 Oracle 语法。')
doc.add_paragraph('URL 格式：jdbc:oracle:thin:@host:port:service')

# 2.4 JDBC 通用
doc.add_heading('2.4 JDBC 通用数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('支持任何提供 JDBC 驱动的数据库。')

doc.add_heading('能怎么干', 3)
doc.add_paragraph('通过 JDBC URL 连接，自动适配 MySQL、PostgreSQL、Oracle 等方言。')

# 2.5 HDFS
doc.add_heading('2.5 HDFS 数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('从 Hadoop HDFS 读取文件数据，支持目录扫描和文件过滤。')

doc.add_heading('能怎么干', 3)
table = doc.add_table(rows=5, cols=2)
table.style = 'Light Grid Accent 1'
hdfs_data = [
    ['配置项', '说明'],
    ['path', 'HDFS 文件或目录路径'],
    ['format', '文件格式：csv / json / ndjson'],
    ['pattern', '文件名匹配模式（如 *.csv）'],
    ['limit', '最大读取记录数'],
]
for i, row in enumerate(hdfs_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_paragraph('')
doc.add_paragraph('支持的文件格式：')
formats = [
    'CSV：逗号分隔，首行为表头',
    'JSON：对象数组格式',
    'NDJSON：每行一个 JSON 对象',
]
for f in formats:
    doc.add_paragraph(f, style='List Bullet')

# 2.6 S3
doc.add_heading('2.6 S3/MinIO/OSS 数据源', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph('从 S3 兼容存储读取或写入文件，支持 AWS S3、MinIO、阿里 OSS 等。')

doc.add_heading('能怎么干', 3)
table = doc.add_table(rows=8, cols=2)
table.style = 'Light Grid Accent 1'
s3_data = [
    ['配置项', '说明'],
    ['endpoint', 'S3 服务地址（MinIO/OSS 需要）'],
    ['access-key', '访问密钥'],
    ['secret-key', '秘密密钥'],
    ['bucket', '存储桶名称'],
    ['key', '对象 Key（文件路径）'],
    ['format', '文件格式：csv / json / ndjson'],
    ['region', '区域（默认 us-east-1）'],
]
for i, row in enumerate(s3_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_page_break()

# 第3章 新增处理控件
doc.add_heading('3. 新增处理控件', 1)

doc.add_heading('3.1 Redis 查询补全', 2)
doc.add_heading('能干什么', 3)
doc.add_paragraph(
    '根据数据中某个字段的值，自动查询 Redis 获取相关信息，并将结果扩充到原数据中。'
)
doc.add_paragraph('典型场景：')
scenes = [
    '根据 user_id 查询 Redis 中的用户手机号、邮箱',
    '根据 product_id 查询商品名称、价格',
    '根据 config_key 查询配置值',
]
for s in scenes:
    doc.add_paragraph(s, style='List Bullet')

doc.add_heading('能怎么干', 3)
doc.add_paragraph('配置说明：')
table = doc.add_table(rows=6, cols=2)
table.style = 'Light Grid Accent 1'
lookup_data = [
    ['配置项', '说明'],
    ['sourceField', '用于查询的源字段名'],
    ['redisKeyPattern', 'Redis Key 模板，{value} 为占位符'],
    ['redisType', 'Redis 数据类型：hash / string'],
    ['targetFields', '字段映射数组'],
    ['defaultValue', '查询未命中时的默认值'],
]
for i, row in enumerate(lookup_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_paragraph('')
doc.add_paragraph('配置示例：')
doc.add_paragraph(
    '{"sourceField": "user_id", "redisKeyPattern": "user:{value}", "redisType": "hash", '
    '"targetFields": [{"redisField": "phone", "targetField": "phone"}]}',
    style='Intense Quote'
)

doc.add_paragraph('')
doc.add_paragraph('处理流程：')
steps = [
    '遍历数据中每条记录',
    '提取 sourceField 字段的值',
    '替换 keyPattern 中的 {value} 生成 Redis Key',
    '使用 Pipeline 批量查询 Redis',
    '将查询结果合并到原数据',
]
for i, s in enumerate(steps, 1):
    doc.add_paragraph(f'{i}. {s}')

doc.add_paragraph('')
doc.add_paragraph('自动添加的统计字段：')
stats = [
    '_redis_hit：查询命中次数',
    '_redis_miss：查询未命中次数',
]
for s in stats:
    doc.add_paragraph(s, style='List Bullet')

doc.add_page_break()

# 第4章 安全与稳定性修复
doc.add_heading('4. 安全与稳定性修复', 1)

doc.add_heading('4.1 安全修复', 2)
table = doc.add_table(rows=5, cols=3)
table.style = 'Light Grid Accent 1'
security_data = [
    ['问题', '风险', '修复方案'],
    ['SQL 注入', '用户自定义 SQL 可执行任意命令', '禁止自定义 SQL，只允许表名'],
    ['Redis KEYS 阻塞', 'KEYS 命令会阻塞 Redis', '改用 SCAN 命令'],
    ['正则 DoS', '恶意正则导致线程卡死', '限制正则长度 200 字符'],
    ['密码明文', '密码在配置中明文存储', '前端 input type="password"'],
]
for i, row in enumerate(security_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_heading('4.2 稳定性修复', 2)
table = doc.add_table(rows=7, cols=3)
table.style = 'Light Grid Accent 1'
stability_data = [
    ['问题', '影响', '修复方案'],
    ['资源泄漏', '连接/文件句柄未关闭', 'try-with-resources 自动关闭'],
    ['Redis 破坏性读取', 'leftPop 会删除数据', '改用 range 非破坏性读取'],
    ['Excel 假导出', '实际输出 CSV，乱码', '使用 POI 生成真实 xlsx'],
    ['CSV 无转义', '含逗号字段格式错乱', '添加 escapeCsv 方法'],
    ['type-convert 静默失败', '转换失败无感知', '记录 _convert_error_ 字段'],
    ['无 limit 上限', '大数据量导致 OOM', '添加 MAX_LIMIT 常量'],
]
for i, row in enumerate(stability_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_heading('4.3 性能优化', 2)
table = doc.add_table(rows=4, cols=2)
table.style = 'Light Grid Accent 1'
perf_data = [
    ['优化项', '说明'],
    ['Redis Pipeline 批量查询', 'Redis Lookup 控件使用 Pipeline，减少网络往返'],
    ['filter 正则预编译', '避免每条记录重复编译正则'],
    ['data-repair 维表限制', '限制维表 50000 条，防止 OOM'],
]
for i, row in enumerate(perf_data):
    for j, cell in enumerate(row):
        table.rows[i].cells[j].text = cell
        if i == 0:
            table.rows[i].cells[j].paragraphs[0].runs[0].bold = True

doc.add_page_break()

# 第5章 使用指南
doc.add_heading('5. 使用指南', 1)

doc.add_heading('5.1 添加数据源节点', 2)
steps = [
    '打开画布编辑器',
    '在左侧「输入控件」或「输出控件」分组中找到对应控件',
    '拖拽到画布或点击添加',
    '双击节点打开配置面板',
    '填写连接信息和参数',
    '保存配置',
]
for i, s in enumerate(steps, 1):
    doc.add_paragraph(f'{i}. {s}')

doc.add_heading('5.2 连线与执行', 2)
doc.add_paragraph('将数据源节点与处理节点、输出节点连线，形成完整的数据处理管道。')
doc.add_paragraph('点击「保存画布」后即可执行任务。')

doc.add_heading('5.3 配置示例', 2)

# 示例1
doc.add_heading('示例1：从 PostgreSQL 读取数据，脱敏后写入 HDFS', 3)
doc.add_paragraph('1. 添加 PostgreSQL 输入节点')
doc.add_paragraph('2. 添加字段脱敏节点')
doc.add_paragraph('3. 添加 HDFS 输出节点')
doc.add_paragraph('4. 连线：PostgreSQL → 脱敏 → HDFS')

# 示例2
doc.add_heading('示例2：Redis 查询补全', 3)
doc.add_paragraph('1. 添加 CSV 输入节点')
doc.add_paragraph('2. 添加 Redis 查询节点，配置 sourceField 和 keyPattern')
doc.add_paragraph('3. 添加 MySQL 输出节点')
doc.add_paragraph('4. 连线：CSV → Redis 查询 → MySQL')

doc.add_page_break()

# 第6章 配置示例
doc.add_heading('6. 配置示例', 1)

configs = [
    ('Redis Source', '{"type": "hash", "key": "user:1001", "count": "1000"}'),
    ('PostgreSQL Source', '{"url": "jdbc:postgresql://localhost:5432/mydb", "username": "postgres", "password": "pass", "table": "users", "limit": "10000"}'),
    ('HDFS Source', '{"path": "/data/input/", "format": "csv", "pattern": "*.csv", "limit": "100000"}'),
    ('S3 Source', '{"endpoint": "http://minio:9000", "access-key": "minioadmin", "secret-key": "minioadmin", "bucket": "data", "key": "input.csv", "format": "csv"}'),
    ('Redis Lookup', '{"sourceField": "user_id", "redisKeyPattern": "user:{value}", "redisType": "hash", "targetFields": [{"redisField": "phone", "targetField": "phone"}], "defaultValue": ""}'),
    ('PostgreSQL Sink', '{"url": "jdbc:postgresql://localhost:5432/mydb", "username": "postgres", "password": "pass", "table": "output"}'),
    ('HDFS Sink', '{"path": "/data/output/result.csv", "format": "csv", "overwrite": "true"}'),
    ('S3 Sink', '{"endpoint": "http://minio:9000", "access-key": "minioadmin", "secret-key": "minioadmin", "bucket": "output", "key": "result.csv", "format": "csv"}'),
]

for name, config in configs:
    doc.add_paragraph(f'{name}：', style='Intense Quote')
    doc.add_paragraph(config)
    doc.add_paragraph('')

# 保存
doc.save('D:/a22/docs/A22数据平台-多数据源扩展功能说明.docx')
print('文档生成完成：D:/a22/docs/A22数据平台-多数据源扩展功能说明.docx')
