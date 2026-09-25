# U9C OpenAPI 对接说明

后续在 Report Hub 里新增报表、接用友 U9C 数据时，以本文为入口。

## 官方文档

| 项 | 地址 |
|----|------|
| **U9C 标准 OpenAPI 文档** | https://openapi.yyu9c.com/doc.html#/home |
| 文档形态 | Knife4j / Swagger UI（需浏览器打开，勿用纯文本抓取） |

请以官网文档为准；本文只记录 **本项目已验证的接入方式** 与 **加报表时的配置套路**。

## 本项目接入方式

| 项 | 值 |
|----|-----|
| 数据源认证 | `authType = U9C OAuth` |
| 换 Token | `GET {Base URL}/webapi/OAuth2/AuthLogin` |
| 业务请求头 | `token: {accessToken}` |
| Token 有效期 | 约 5 分钟；引擎缓存并自动续期 / 401 重登 |

### AuthLogin 参数

| 参数 | 配置字段 | 说明 |
|------|----------|------|
| clientid | `clientId` | 应用客户端 ID |
| clientsecret | `clientSecret` | 应用密钥 |
| entCode | `entCode` | 企业编码 |
| userCode | `userCode` | 用户编码 |
| orgCode | `orgCode` | 组织编码 |
| loginType / language | 代码固定 | `loginType=1`、`language=zh-CN` |

响应：`ResCode=0` 时取 `Data` 作为 accessToken。

配置位置：**数据源 → 认证方式选 U9C OAuth**。

## 已用到的 U9C 接口（挂次日报表示例）

| 接口编码 | 方法 | 路径 | 用途 |
|----------|------|------|------|
| `u9c_complete_query` | POST | `/webapi/CompleteDoc/Query` | 完工报告 |
| `u9c_mo_query` | POST | `/webapi/MODoc/Query` | 生产订单 |
| `u9c_mo_so_contract` | POST | `/webapi/QueryCommon/QueryInfoBySql` | 工单 → 销售订单 / 合同（SQL） |

Body 示例（按 DocNo 查）：

```json
[{"DocNo":"{{key}}"}]
```

SQL 示例（`QueryInfoBySql`）：

```json
{"SqlString": "SELECT ... WHERE m.DocNo = '{{key}}'"}
```

## 新增 U9C 报表的推荐流程

1. **查文档**  
   打开 https://openapi.yyu9c.com/doc.html#/home ，找到业务 API 的路径、入参、返回结构（列表字段、分页字段）。

2. **数据源**  
   确认已有 `U9C OAuth` 数据源（`base_url` 如 `https://erp.example.com/U9C`）。新环境只换地址与 client / ent / user / org。

3. **接口**  
   - 路径：文档中的相对路径，如 `/webapi/XXX/Query`  
   - 方法：GET 填查询参数；POST 选 Body 分类（JSON / Form）  
   - 列表路径 / 总数路径：按文档返回结构填（如 `Data`、`Count`）  
   - 用「测试调用」看原始 JSON 是否解析出行

4. **数据集**  
   把返回字段映射为报表列名（`target_field`），脏数据加 trim / 数字类型。

5. **报表 + 关联**  
   选主数据集、展示列、筛选；多表按文档能批量查就 `child_batch`，只能单键则 `child_lookup`。

6. **验证**  
   查询页看调用链；导出 Excel；需要时给首页看板加 count / sum / count_distinct。

## 与 MES 的差异（避免踩坑）

| | U9C | RM-MES 等 |
|--|-----|-----------|
| 认证 | OAuth token 头 `token=` | 登录接口 Cookie / access_token |
| 列表 | 常为 `Data` 数组 | 路径因系统而异 |
| 批量 | 部分支持 SQL / IN | 需看接口是否接受列表参数 |

## 阅读文档时的注意点

- Knife4j 页必须用**浏览器**打开；脚本未加载时看不到接口列表。  
- 以当前客户现场的 U9C 版本文档为准，字段可能与样例环境略有差别。  
- 生产不要把 `clientSecret` 写进 Git；放在数据源配置（库内）或环境变量。  

## 相关代码

| 文件 | 说明 |
|------|------|
| `backend/.../engine/U9cOAuthService.java` | AuthLogin / token 缓存 / 业务头 |
| `backend/.../engine/UpstreamClient.java` | 统一 HTTP、401 重登 |
| `backend/.../engine/ReportQueryEngine.java` | 关联取数与投影 |
| `frontend/src/pages/Endpoints.tsx` | 接口 / Query·Form·JSON 配置 |
