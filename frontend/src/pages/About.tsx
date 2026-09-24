import { Link } from 'react-router-dom'
import Icon from '../components/Icon'

const steps = [
  {
    n: '01',
    title: '数据源',
    to: '/datasources',
    icon: 'datasource' as const,
    goal: '告诉系统：上游业务系统在哪、怎么登录。',
    fields: [
      ['编码 / 名称', '系统标识与展示名（编码保存后不可改）'],
      ['Base URL', '业务系统入口，接口路径会拼在它后面'],
      ['认证方式', '按上游能力选择，见下表'],
    ],
    tips: [
      '先用「测试登录」确认凭证可用，再往下配接口。',
      '同一套报表可同时挂多个数据源（如 ERP + MES）。',
    ],
  },
  {
    n: '02',
    title: '接口',
    to: '/endpoints',
    icon: 'api' as const,
    goal: '描述一次业务调用：路径、参数、返回里列表在哪。',
    fields: [
      ['方法 / 路径', '相对 Base URL 的业务接口，如 /webapi/XXX/Query'],
      ['查询参数', '拼在 URL 上，值可用占位符 {{filter.xxx}}、{{page}}'],
      ['列表路径', '返回 JSON 里数组字段，如 data 或 Data'],
      ['总数路径', '可选，分页算总页数用，如 count'],
    ],
    tips: [
      '占位符只是本地变量名；左边「参数名」才是发给上游的真实字段。',
      '用「测试调用」看原始返回，确认列表路径没填错。',
    ],
  },
  {
    n: '03',
    title: '数据集',
    to: '/datasets',
    icon: 'dataset' as const,
    goal: '把接口原始字段，映射成报表里稳定的列名。',
    fields: [
      ['绑定接口', '这个数据集从哪条接口取数'],
      ['接口字段', '上游 JSON 路径，如 allbatchnoweight 或 m_item.m_name'],
      ['报表列名', '映射后的 target，如 order_weight'],
      ['类型 / 转换', 'string / number / date；可选 trim 等'],
    ],
    tips: [
      '报表只认映射后的列名，不直接吃上游原始字段名。',
      '数值带空格、补零等脏数据，在映射阶段 trim / 转类型。',
    ],
  },
  {
    n: '04',
    title: '报表',
    to: '/reports',
    icon: 'report' as const,
    goal: '定一张业务报表：主表、展示列、筛选、分页。',
    fields: [
      ['主数据集', '报表的行从哪张表来'],
      ['展示列', '列标识 + 表头 + 来源「数据集.字段」，可上下移排序'],
      ['筛选与排序', '参数名、显示名、类型（日期范围 / 下拉 / 文本）'],
      ['分页', '每页条数、请求参数名、总数字段路径'],
    ],
    tips: [
      '筛选参数名要和接口查询参数里的占位符对上。',
      '计算列 / 判断列可单独配置，变量用展示列的列标识。',
    ],
  },
  {
    n: '05',
    title: '关联关系',
    to: '/relations',
    icon: 'relation' as const,
    goal: '多系统拼行：左表键 → 右表键，选好取数策略。',
    fields: [
      ['左右数据集', '主表与被关联表'],
      ['关联字段', '值相等则匹配，可多组'],
      ['取数策略', '见下表，按接口能力选'],
      ['展开方式', '1:N 时摊平多行或保持嵌套'],
    ],
    tips: [
      '接口能力不确定时可随时切换策略，字段配置可复用。',
      '一对多会摊成多行；多对一不会改变行数。',
    ],
  },
  {
    n: '06',
    title: '查询与导出',
    to: '/reports',
    icon: 'search' as const,
    goal: '业务人员选条件出报表；管理员可看调用链。',
    fields: [
      ['筛选', '日期范围、下拉、文本；空条件表示不限制'],
      ['执行查询', '实时调上游，不落业务库'],
      ['导出 Excel', '按当前筛选导出全量；短时间重复导出走结果缓存'],
    ],
    tips: [
      '行数过多时先收窄日期 / 状态条件。',
      '管理员在查询页底部可看「取数调用链」排错。',
    ],
  },
]

export default function About() {
  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="help" size={22} /></div>
          <div>
            <h1>使用说明</h1>
            <p>按配置流程，把任意多源接口拼成一张可查询、可导出的报表</p>
          </div>
        </div>
      </div>

      <div className="stack">
        <div className="card">
          <h3>这套系统解决什么</h3>
          <div className="sub">配置驱动 · 实时聚合 · 不落业务库</div>
          <p style={{ margin: 0, lineHeight: 1.7, color: 'var(--text-2)', fontSize: 13 }}>
            当同一张报表要跨 ERP / MES / 自建接口取数，且字段名、分页方式、登录方式各不相同时，
            不必为每张报表写死代码。只要按下面六步把「连接 → 接口 → 字段 → 报表 → 关联」配好，
            查询时引擎会实时调上游、映射字段、按策略关联，最后拼成一张表，并可导出 Excel。
            业务数据始终以源系统为准，本平台只保存配置。
          </p>
        </div>

        <div className="card">
          <h3>配置总览</h3>
          <div className="sub">从下到上依赖，改完上层无需改下层</div>
          <div className="code-block">{`数据源  ──  认证 / Base URL
   │
接口    ──  路径 · 参数模板 · 列表路径
   │
数据集  ──  字段映射（原始字段 → 报表列）
   │
报表    ──  主表 · 展示列 · 筛选 · 分页
   │
关联    ──  多数据集拼行（可多条、可混用策略）
   │
查询 / 导出`}</div>
          <div className="row" style={{ marginTop: 12 }}>
            <Link className="btn sm" to="/datasources"><Icon name="datasource" size={13} />1 数据源</Link>
            <Link className="btn sm" to="/endpoints"><Icon name="api" size={13} />2 接口</Link>
            <Link className="btn sm" to="/datasets"><Icon name="dataset" size={13} />3 数据集</Link>
            <Link className="btn sm" to="/reports"><Icon name="report" size={13} />4 报表</Link>
            <Link className="btn sm" to="/relations"><Icon name="relation" size={13} />5 关联</Link>
          </div>
        </div>

        {steps.map((s) => (
          <div className="card" key={s.n}>
            <div className="spread" style={{ marginBottom: 8 }}>
              <div className="row" style={{ gap: 12 }}>
                <div className="icon-tile" style={{ width: 36, height: 36 }}>
                  <Icon name={s.icon} size={18} />
                </div>
                <div>
                  <h3 style={{ margin: 0 }}>{s.n} · {s.title}</h3>
                  <div className="sub" style={{ margin: '4px 0 0' }}>{s.goal}</div>
                </div>
              </div>
              <Link className="btn sm ghost" to={s.to}>去配置<Icon name="arrowLeft" size={12} className="flip-x" /></Link>
            </div>
            <div className="kv" style={{ marginBottom: 12 }}>
              {s.fields.map(([k, v]) => (
                <div key={k} style={{ display: 'contents' }}>
                  <div className="k">{k}</div>
                  <div>{v}</div>
                </div>
              ))}
            </div>
            {s.tips.map((t) => (
              <div key={t} className="hint-line">
                <Icon name="check" size={13} />
                <span>{t}</span>
              </div>
            ))}
          </div>
        ))}

        <div className="card">
          <h3>认证方式怎么选</h3>
          <div className="sub">数据源 · 认证，按上游能力选一种</div>
          <div className="kv">
            <div className="k">无需认证</div>
            <div>内网或开放接口</div>
            <div className="k">Basic / Bearer / API Key</div>
            <div>每次请求固定带凭证</div>
            <div className="k">登录会话</div>
            <div>先调登录接口拿 Cookie / Token，再调业务；失效自动重登（如 MES 签名登录）</div>
            <div className="k">U9C OAuth</div>
            <div>OAuth2/AuthLogin 换 token，业务请求头带 token</div>
          </div>
        </div>

        <div className="card">
          <h3>关联取数策略</h3>
          <div className="sub">同一张报表可混用；接口能力变了一键切换</div>
          <div className="kv">
            <div className="k">双侧列表 dual_list</div>
            <div>左右各查列表再内存关联。适合两边都能按条件拉列表。</div>
            <div className="k">批量 IN child_batch</div>
            <div>主表键收齐后一次查子表（?ids=a,b,c）。适合「按 id 列表查详情」。</div>
            <div className="k">逐条单查 child_lookup</div>
            <div>主表键并发单查。适合只能单键查询的详情接口；行数多时较慢。</div>
          </div>
        </div>

        <div className="card">
          <h3>筛选条件如何接到接口</h3>
          <div className="sub">两边名字可以不同，靠接口「查询参数」对齐</div>
          <div className="code-block">{`报表筛选                    接口查询参数
─────────────────           ────────────────────────────
参数名 work_status     →    workStatusArr = {{filter.work_status}}
显示名「挂次状态」            ↑左=上游真实参数  ↑右=筛选参数名

参数名 endImmersionTime
  开始 / 结束           →    hangStartTime = {{filter.endImmersionTime.start}}`}</div>
          <ul className="about-list">
            <li>日期范围只通过接口参数下推，不在本地二次过滤。</li>
            <li>若筛选参数名与展示列 key 相同，还会在内存里做包含匹配。</li>
            <li>日期留空时，对应参数不会拼进请求。</li>
          </ul>
        </div>

        <div className="card">
          <h3>角色与入口</h3>
          <div className="sub">右上角头像进入个人中心；配置菜单仅管理员可见</div>
          <div className="kv">
            <div className="k">管理员</div>
            <div>数据源 / 接口 / 数据集 / 报表 / 关联 / 用户管理 + 查询导出</div>
            <div className="k">查询员</div>
            <div>首页 / 报表查询 / 导出 / 个人中心</div>
            <div className="k">个人中心</div>
            <div>改显示名、改密码（顶栏头像进入）</div>
          </div>
          <div className="code-block" style={{ marginTop: 12 }}>{`演示账号（部署后请立刻改掉）
  admin  / admin123    配置
  member / member123   查询`}</div>
        </div>

        <div className="card">
          <h3>常见问题</h3>
          <div className="kv">
            <div className="k">查不到数据</div>
            <div>先「测试调用」接口看原始 JSON；核对列表路径、筛选参数名、关联键是否一致</div>
            <div className="k">某列一直空</div>
            <div>数据集 · 字段映射的「接口字段」路径是否正确；注意 trim 与类型</div>
            <div className="k">导出很慢 / 失败</div>
            <div>收窄筛选；大量「逐条单查」关联会随行数变慢，可改批量 IN</div>
            <div className="k">改了配置没生效</div>
            <div>保存后重新执行查询；结果缓存约 10 分钟，改配置会自动失效</div>
          </div>
        </div>
      </div>
    </>
  )
}
