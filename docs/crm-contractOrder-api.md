# CRM 销售订单 OpenAPI 测试记录

测试时间：2026-10-14  
环境：https://crm.btbfaf.com

## 1. 目标接口

| 项 | 值 |
|----|-----|
| 列表 | `GET /api/blade-contractOrder/contractOrder/list?current=1&size=10` |
| 认证（首次） | Header `X-OPENAPI-KEY: openapi-dab19e82e1f9a681ee73346d3e7a575e` |

## 2. API Key 结果：失败（401）

请求：

```http
GET /api/blade-contractOrder/contractOrder/list?current=1&size=10
X-OPENAPI-KEY: openapi-dab19e82e1f9a681ee73346d3e7a575e
```

响应：

```json
{"code":401,"success":false,"data":null,"msg":"请求未授权"}
```

已尝试的等价传法（均为 401）：

- `X-OPENAPI-KEY` / `X-Openapi-Key` / `Openapi-Key`
- `X-API-KEY` / `X-Auth-Token`
- `Authorization: <key>` / `Authorization: Bearer <key>`
- Query `?apiKey=`
- 无认证基线（同样 401）

结论：**当前 API Key 无效、未启用，或不走该 Header 名称。**

## 3. 系统形态

- 模块路径 `blade-contractOrder` → **Blade / BladeX** 风格
- 存在 Knife4j/Swagger 页：`GET /doc.html`（HTML，需浏览器）
- 登录端点存在：`POST /api/blade-auth/oauth/token`

## 4. 登录认证（2026-10-14 补充）

`POST /api/blade-auth/oauth/token`  
参数（query 或 `application/x-www-form-urlencoded` body 均可）：

`tenantId=000000&username=af30045&password=e10adc3949ba59abbe56e057f20f883e&grant_type=password&scope=all&type=account`

实测：

| 请求 | 结果 |
|------|------|
| 仅 query，无 `Authorization` | 200 业务 401：`未获取到请求头[Authorization]的信息` |
| form + `Authorization: Basic client:secret` | 200 业务 401：`客户端认证失败, 请检查请求头 [Authorization] 信息` |
| 其它常见 client_id:client_secret | 同上，客户端认证均失败 |

结论：这是 **OAuth2 password 模式**，必须先过 **客户端 Basic 认证**。  
账号 `af30045` / 租户 `000000` / password 形如 MD5 可能正确，但缺：

- [ ] **client_id / client_secret**（接口方提供，用于 `Authorization: Basic base64(client_id:client_secret)`）

登录成功后应返回 access_token，再调 `contractOrder/list`。

## 6. 登录成功与列表调用（2026-10-14）

### 6.1 可用客户端（图1）

| 应用 id | 应用密钥 | 授权类型 | 范围 | 令牌秒数 |
|---------|----------|----------|------|----------|
| sword | sword_secret | password 等 | all | 604800 |
| saber | **saber_secret** | password 等 | all | 604800 |

浏览器实际请求（图2）使用：`Authorization: Basic c2FiZXI6c2FiZXJfc2VjcmV0`  
= **`saber:saber_secret`**。

### 6.2 取 token（成功）

```http
POST /api/blade-auth/oauth/token?tenantId=000000&username=af30045&password=e10adc3949ba59abbe56e057f20f883e&grant_type=password&scope=all&type=account
Authorization: Basic c2FiZXI6c2FiZXJfc2VjcmV0
```

- 密码为 **MD5**（`123456` 的 MD5）；明文 `123456` 会报用户名或密码不正确  
- 返回 `access_token` / `refresh_token`，`token_type=bearer`，`expires_in=604800`（7 天）  
- 账号：AF30045 / 范鑫 / tenant 000000

### 6.3 销售订单列表（成功）

```http
GET /api/blade-contractOrder/contractOrder/list?current=1&size=10
Authorization: Basic c2FiZXI6c2FiZXJfc2VjcmV0
Blade-Auth: bearer <access_token>
```

响应结构：

```json
{
  "code": 200, "success": true,
  "data": {
    "records": [ ... ],
    "total": 920,
    "current": 1, "size": 10, "pages": 92
  }
}
```

### 6.4 records 主要字段

| 字段 | 示例 / 说明 |
|------|-------------|
| **orderNo** | `SO20261014010` 销售订单号（主关联键） |
| id | 主键 |
| customerName / customerId | 例：兰州将军山机械厂 |
| contractCode / contractName | 合同 |
| orderCommandNo | 例：`2026AF-FJS-117` 订单指令号 |
| deliveryDate | 交货日期 |
| priority | 高 / … |
| productType / productTypeName | 产品类型 |
| **salesOrderStatus** | 销售订单状态（枚举待确认） |
| **processStatus** | `running` 等流程状态 |
| processInstanceId | 流程实例 |
| orderCommandPushed | 指令是否已推送 0/1 |
| salesmanName | 业务员 |
| createTime / applyTime | 创建 / 申请时间 |
| detailEntityList | 明细行（当前页多为 null，可再查单条详情） |
| status / isDeleted | 系统状态 |

### 6.5 后续关联建议

- CRM `orderNo` → ERP 是否开工（生产订单）  
- ERP 工单号 / CRM `orderNo` → 军品 MES 流动卡  

## 7. 待补充

- [x] OAuth2 client（saber:saber_secret 可用）
- [x] contractOrder/list 样例
- [ ] salesOrderStatus / productType 枚举中文含义
- [ ] 明细接口（detailEntityList 拉全）
- [ ] ERP「是否开工」、MES「流动卡」接口
