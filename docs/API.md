# 接口说明

本文档列出小兔鲜儿 Mock Service 的常用本地接口，默认服务地址为：

```text
http://localhost:8099
```

## 首页

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/home/goods` | 首页商品数据 |
| GET | `/home/category/head` | 首页头部分类 |
| GET | `/home/banner` | 首页轮播图 |
| GET | `/home/brand` | 首页品牌 |
| GET | `/home/new` | 首页新品 |
| GET | `/home/hot` | 首页人气商品 |
| GET | `/home/special` | 首页专题 |

## 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/member/register` | 用户注册 |
| POST | `/login` | 用户登录 |
| GET | `/member/profile` | 查询用户资料 |
| PUT | `/member/profile` | 更新用户资料 |
| PUT | `/member/password` | 更新密码 |
| POST | `/member/logout` | 退出登录 |

## 购物车

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/member/cart` | 查询购物车 |
| POST | `/member/cart` | 添加购物车 |
| DELETE | `/member/cart` | 删除购物车商品 |
| PUT | `/member/cart/{skuId}` | 更新购物车商品 |
| POST | `/member/cart/merge` | 合并购物车 |
| PUT | `/member/cart/selected` | 更新选中状态 |

## 地址

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/member/address` | 查询地址列表 |
| POST | `/member/address` | 新增地址 |
| PUT | `/member/address/{id}` | 更新地址 |
| DELETE | `/member/address/{id}` | 删除地址 |

## 订单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/member/order/pre` | 查询结算信息 |
| POST | `/member/order` | 创建订单 |
| GET | `/member/order` | 查询订单 |
| POST | `/member/pay` | 模拟支付 |
| PUT | `/member/order/{orderId}/cancel` | 取消订单 |
| PUT | `/member/order/{orderId}/receipt` | 确认收货 |

## 数据重置

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/reset-member-data` | 重置会员相关演示状态 |
| POST | `/admin/reload` | 重新加载演示数据 |
