import { Link } from 'react-router-dom'
import { getUser } from '../api/client'
import Icon from '../components/Icon'

const BUILTIN_REPORTS = [
  {
    code: 'rpt_contract_scroll',
    name: '销售合同滚动统计报表',
    desc: '以 2026-10 为期初基线，按月自动滚动核算黑件来料/出库、白件入库/发货、开票、回款核销与期末总欠款，并自动发现新签 U9 销售订单。',
    sources: 'U9 Cloud ERP（9 张业务单据 + 期初基线）',
  },
  {
    code: 'rpt_hang_complete',
    name: '挂次日报表',
    desc: '自动关联瑞马 MES 完工挂次、锌层厚度与 U9 完工报告、生产订单，用于日常生产追溯与产销核对。',
    sources: '瑞马 MES + U9 Cloud ERP',
  },
  {
    code: 'rpt_white_weight_diff',
    name: '挂次白件重量差异表',
    desc: '自动比对 MES 挂次白件称重与 U9 完工/入库重量，快速筛查超差或缺失完工报告的异常挂次。',
    sources: '瑞马 MES + U9 Cloud ERP',
  },
  {
    code: 'rpt_cust_month_in',
    name: '客户月度黑件入库表',
    desc: '按月汇总各客户黑件杂收来料明细与吨位统计。',
    sources: 'U9 Cloud ERP（BF003 客供库）',
  },
  {
    code: 'rpt_cust_month_out',
    name: '客户月度白件出库表',
    desc: '按月汇总各客户白件成品发货明细与吨位统计。',
    sources: 'U9 Cloud ERP（BF004 成品库）',
  },
]

export default function About() {
  const isAdmin = getUser()?.role === 'admin'

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="help" size={22} /></div>
          <div>
            <h1>使用说明</h1>
            <p>智能镀锌经营分析与多源报表平台 · 开箱即用的看板、自动同步与核算体系</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm" to="/"><Icon name="home" size={13} />经营看板</Link>
          <Link className="btn sm primary" to="/reports"><Icon name="report" size={13} />进入报表中心</Link>
        </div>
      </div>

      <div className="stack">
        <div className="card">
          <h3>平台定位与核心能力</h3>
          <div className="sub">自动对接 U9 ERP 与瑞马 MES · 定时同步落库 · 系统自动核算</div>
          <p style={{ margin: 0, lineHeight: 1.75, color: 'var(--text-2)', fontSize: 13 }}>
            本平台面向日常经营决策与财务/生产对账，已由系统内置完成 U9 Cloud ERP 与瑞马 MES 的数据接入、字段对齐、定时增量同步与月度滚动核算逻辑。
            日常使用时<strong>无需手工配置接口或拼装公式</strong>，打开系统即可查看经营驾驶舱、按月查询明细报表并一键导出标准 Excel。
          </p>
          <div className="about-feature-grid">
            <div className="about-feature">
              <div className="about-feature-title">
                <Icon name="trend" size={15} />
                <b>经营驾驶舱（首页）</b>
              </div>
              <p>汇总当月白件发货、开票、回款、期末总欠款与在制库存及环比变化；点击趋势柱体切换月份，点击欠款客户或负库存预警直接下钻定位到合同明细。</p>
            </div>
            <div className="about-feature">
              <div className="about-feature-title">
                <Icon name="sync" size={15} />
                <b>本地同步库 + 实时双模式</b>
              </div>
              <p>日常默认走「本地数据」：系统按计划自动同步上游单据，看板与全部 5 张报表毫秒级响应、不给 ERP/MES 增压；核对刚录入单据时管理员可临时切「实时聚合」。</p>
            </div>
            <div className="about-feature">
              <div className="about-feature-title">
                <Icon name="download" size={15} />
                <b>全量导出与快捷导航</b>
              </div>
              <p>支持按当前筛选条件导出全量 Excel（含双层表头与合计行）；随时按 <kbd>⌘K</kbd> / <kbd>Ctrl+K</kbd> 快速跳转任意报表，左下角可收起侧栏获得全宽大屏视野。</p>
            </div>
          </div>
        </div>

        <div className="card">
          <h3>已内置核心报表</h3>
          <div className="sub">系统自动维护口径与关联，点名称直接进入查询</div>
          <div className="about-report-list">
            {BUILTIN_REPORTS.map((r) => (
              <Link to={`/reports/${r.code}`} key={r.code} className="about-report-item">
                <div className="about-report-main">
                  <div className="about-report-name">
                    <b>{r.name}</b>
                    <span className="badge muted">{r.sources}</span>
                  </div>
                  <p>{r.desc}</p>
                </div>
                <Icon name="chevronRight" size={15} className="about-report-arrow" />
              </Link>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>日常使用技巧</h3>
          <div className="sub">适用于所有账号（查询员 &amp; 管理员）</div>
          <div className="kv">
            <div className="k">看板联动下钻</div>
            <div>在「经营看板」点击任意 KPI 卡片、客户欠款排行或负库存预警行，会自动跳转至《销售合同滚动统计报表》并带入对应月份与客户/合同号筛选。</div>
            <div className="k">筛选链接分享</div>
            <div>滚动报表的月份、合同号、客户筛选会自动同步到浏览器地址栏，刷新页面条件不丢失，复制链接发给同事可直接打开同一视图。</div>
            <div className="k">宽表浏览模式</div>
            <div>查看 42 列滚动报表时，可点击左侧边栏底部「收起」将菜单缩为图标栏，最大化横向表格可视区域。</div>
            <div className="k">快捷搜索跳转</div>
            <div>在任意页面按 <span className="mono">⌘K</span>（Windows 按 <span className="mono">Ctrl+K</span>）唤起搜索面板，用方向键和回车直达目标报表。</div>
            <div className="k">账号与密码</div>
            <div>点击右上角头像进入「个人中心」，可随时修改显示名称和登录密码。</div>
          </div>
        </div>

        {isAdmin && (
          <div className="card">
            <h3>管理员运维指南</h3>
            <div className="sub">系统已自动化运行，以下入口仅在日常巡检或口径调整时使用</div>
            <div className="kv">
              <div className="k"><Link to="/sync" style={{ color: 'var(--accent)' }}>数据同步</Link></div>
              <div>13 个核心数据集已配置每日自动同步。若上游补录/修改了历史月份单据，可点单表「全量同步」重拉；同步异常时侧栏与看板顶部会亮红点提示。</div>
              <div className="k"><Link to="/reports/rpt_contract_scroll" style={{ color: 'var(--accent)' }}>期初基线维护</Link></div>
              <div>在《销售合同滚动统计报表》右上角点「期初基线」，可维护 2026-10 期初结存或补充纸质合同号与 U9 项目号/订单号映射，保存后自动生效。</div>
              <div className="k">查询模式切换</div>
              <div>顶栏「实时聚合 / 本地数据」切换为全局生效，仅管理员可操作。切换后系统自动失效缓存，日常建议保持「本地数据」。</div>
              <div className="k"><Link to="/users" style={{ color: 'var(--accent)' }}>用户管理</Link></div>
              <div>系统已关闭公开自助注册，所有账号统一在「用户管理」中创建、分配角色（管理员 / 查询员）或重置密码。</div>
              <div className="k">底层集成与血缘</div>
              <div>左侧「数据集成」下的「数据源 / 接口管理 / 数据集」已预置完成，日常无需改动（密钥在前端自动脱敏，留空或保持 <span className="mono">******</span> 不会覆盖原密钥）；「关联关系」以只读关系图展示各报表内置的表与接口血缘。</div>
            </div>
          </div>
        )}

        <div className="card">
          <h3>常见问题</h3>
          <div className="kv">
            <div className="k">刚录完单据没看到</div>
            <div>当前处于「本地数据」模式时，数据按定时计划同步更新；如需立即看到刚在 U9/MES 录入的单据，可请管理员在「数据同步」点一次增量同步，或临时切到「实时聚合」核对。</div>
            <div className="k">滚动表出现负库存</div>
            <div>代表按期初结存 + 系统累计入库扣除系统累计出库/发货后为负，常见于 10 月期初手工账偏差或单据跨月补录。可在首页「负库存预警」一键定位核对。</div>
            <div className="k">新签合同如何纳入</div>
            <div>2026-11 起在 U9 录入的镀锌销售订单（1104 料品）同步后会自动出现在《销售合同滚动统计报表》中，无需手工加行；若纸质合同号与 U9 订单号不同，可在期初基线里关联映射。</div>
            <div className="k">导出包含哪些数据</div>
            <div>点击「导出 Excel」始终导出当前筛选条件下的全部匹配行（不受页面分页影响），滚动报表会保留双层表头、合同合并与底部合计行。</div>
          </div>
        </div>
      </div>
    </>
  )
}
