from pathlib import Path
from docx import Document
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

OUTPUT = Path("docs") / "智流工坊-项目目标与解决思路.docx"
BLACK = RGBColor(0, 0, 0)

def font(run, name, size, bold=False):
    run.font.name = name
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), name)
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = BLACK

def style_font(style, name, size, bold=False):
    style.font.name = name
    style._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), name)
    style.font.size = Pt(size)
    style.font.bold = bold
    style.font.color.rgb = BLACK

def para(doc, text, style="正文", indent=True):
    p = doc.add_paragraph(style=style)
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
    p.paragraph_format.space_after = Pt(6)
    if indent:
        p.paragraph_format.first_line_indent = Cm(0.74)
    font(p.add_run(text), "宋体", 12)
    return p

def heading(doc, text):
    p = doc.add_paragraph(style="标题1")
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
    p.paragraph_format.space_before = Pt(12)
    p.paragraph_format.space_after = Pt(4)
    font(p.add_run(text), "黑体", 14, True)

def label(doc, text):
    p = doc.add_paragraph(style="小标题")
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(2)
    font(p.add_run(text), "黑体", 12, True)

def build():
    doc = Document()
    sec = doc.sections[0]
    sec.top_margin = sec.bottom_margin = Cm(2.54)
    sec.left_margin = sec.right_margin = Cm(3.18)
    styles = doc.styles
    style_font(styles["Normal"], "宋体", 12)
    body = styles.add_style("正文", WD_STYLE_TYPE.PARAGRAPH)
    body.base_style = styles["Normal"]
    style_font(body, "宋体", 12)
    small = styles.add_style("小标题", WD_STYLE_TYPE.PARAGRAPH)
    small.base_style = styles["Normal"]
    style_font(small, "黑体", 12, True)
    h1 = styles.add_style("标题1", WD_STYLE_TYPE.PARAGRAPH)
    h1.base_style = styles["Normal"]
    style_font(h1, "黑体", 14, True)

    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_after = Pt(10); font(p.add_run("智流工坊"), "黑体", 18, True)
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.paragraph_format.space_after = Pt(18); font(p.add_run("实时数据流处理平台项目说明"), "黑体", 14, True)

    heading(doc, "目标：")
    para(doc, "智流工坊的核心目标是建设一个面向数据工程团队的实时数据流处理平台，通过可视化、低代码的方式，将多种数据源接入、数据转换、目标存储和任务运行统一到一套操作流程中。平台支持用户通过拖拽节点编排数据作业，并结合流处理引擎完成实时任务执行、状态跟踪和运行日志查看，降低数据管道开发与维护门槛，提升数据处理的效率、稳定性和可复用性。")

    heading(doc, "项目价值：")
    values = [
        ("（1）降低数据开发门槛：", "通过可视化作业画布、节点拖拽和参数配置，将复杂的数据采集、转换与写入过程转化为直观的流程编排，使用户无需从零编写完整的数据处理程序，也能快速构建和调整数据管道。"),
        ("（2）打通数据处理全链路：", "平台覆盖文件、数据库、消息队列等多种数据源，支持字段映射、条件过滤、去重、类型转换、脱敏、字段拼接以及 XML/JSON 转换等处理能力，并将处理结果写入相应的数据目标，形成从数据接入到结果落地的完整闭环。"),
        ("（3）提升实时处理效率与可观测性：", "平台结合 Apache Flink 执行实时数据处理作业，支持作业提交、运行状态跟踪、任务日志查看以及异常定位，帮助团队及时掌握数据链路运行情况，减少人工排查和重复配置的成本。"),
        ("（4）推动数据处理智能化：", "平台集成 AI 辅助能力，用户可以使用自然语言描述处理需求，由系统辅助生成作业编排方案并提供操作建议，让数据处理从依赖专业脚本逐步转向更自然、更高效的人机协同。"),
        ("（5）支持持续扩展与规范化管理：", "平台采用前后端分离和插件化设计，转换算子可以按统一接口扩展；同时提供用户认证、角色权限、任务管理和统一配置能力，便于团队在不同环境中协作使用，并为后续接入更多数据源、处理算子和运行能力预留空间。"),
    ]
    for title, text in values:
        label(doc, title); para(doc, text)

    heading(doc, "解决思路：")
    solutions = [
        ("（1）可视化数据作业编排：", "以可视化流程画布作为主要交互入口，将数据源、转换算子和数据目标抽象为可连接的节点。用户通过拖拽、连线和配置参数即可完成数据流定义，平台负责保存作业结构、校验节点关系并生成可执行的任务配置。"),
        ("（2）多数据源与多目标接入：", "针对实际数据分散在文件、数据库和消息队列中的问题，平台提供 JSON、CSV、Excel、XML、MySQL 和 Kafka 等接入方式，通过统一的数据流模型屏蔽不同来源的接入差异，并支持将处理结果写入相应的数据存储或下游系统。"),
        ("（3）插件化转换与流处理引擎执行：", "后端通过统一的转换插件接口组织字段映射、过滤、去重、类型转换、脱敏、拼接和格式转换等算子，再将 source、operators 和 sink 组合成完整作业，交由 Apache Flink 负责实时执行，从而兼顾功能复用、处理性能和后续扩展。"),
        ("（4）AI 辅助生成与交互式使用：", "在可视化编排的基础上提供 AI 助手，理解用户对数据处理目标的自然语言描述，给出节点组合、字段配置和处理顺序建议，用户确认后再进行调整和执行，形成“自然语言提出需求—系统生成方案—用户可视化确认”的工作方式。"),
        ("（5）统一运行管理与安全控制：", "通过前后端分离架构、统一 API、Token 认证和基于角色的访问控制管理平台功能，配合 Docker Compose 组织数据库、缓存、消息队列和流处理服务，提供一致的部署入口，并对任务状态、运行日志和异常信息进行集中管理。"),
    ]
    for title, text in solutions:
        label(doc, title); para(doc, text)
    para(doc, "通过以上解决方案，智流工坊能够把分散、重复且依赖人工编码的数据处理工作沉淀为可视化、可复用、可监控的标准流程，为团队提供更高效、更易维护的实时数据流处理能力。")

    # Enforce requested typography and black text on all existing runs and any table cells.
    for p in doc.paragraphs:
        for r in p.runs:
            is_heading = p.style.name in ("标题1", "小标题") or p.alignment == WD_ALIGN_PARAGRAPH.CENTER
            size = r.font.size.pt if r.font.size else (14 if is_heading else 12)
            font(r, "黑体" if is_heading else "宋体", size, is_heading or bool(r.bold))
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                tc_pr = cell._tc.get_or_add_tcPr()
                borders = tc_pr.first_child_found_in("w:tcBorders")
                if borders is None:
                    borders = OxmlElement("w:tcBorders"); tc_pr.append(borders)
                for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
                    node = borders.find(qn("w:" + edge))
                    if node is None:
                        node = OxmlElement("w:" + edge); borders.append(node)
                    node.set(qn("w:val"), "single"); node.set(qn("w:sz"), "8"); node.set(qn("w:space"), "0"); node.set(qn("w:color"), "000000")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(OUTPUT)
    print(OUTPUT)

if __name__ == "__main__":
    build()