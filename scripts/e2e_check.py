"""端到端验证：鉴权 → 限流 → 建活动 → 预热 → 并发抢购 → 消费落库 → 对账。

全部请求走 8081 网关（真实链路：网关鉴权 → 业务服务验签 → Redis/MQ/DB），
不使用直连端口，否则测不到鉴权与限流。

判定标准：
  1. 无 token 被拒 401；直连伪造 X-User-Id 被拒 401
  2. 单用户高频请求触发 429
  3. 成功笔数 == 剩余库存，Redis 库存归零且不为负
  4. 每个抢到的用户都能在订单服务查到订单号
  5. 活动账本 available_stock + 订单数 == total_stock，商品库存按订单数扣减
"""

import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

GATEWAY = "http://127.0.0.1:8081"
SECKILL_DIRECT = "http://127.0.0.1:8082"
ORDER_DIRECT = "http://127.0.0.1:8083"

CONCURRENCY = int(sys.argv[1]) if len(sys.argv) > 1 else 200
STOCK = int(sys.argv[2]) if len(sys.argv) > 2 else 50
USER_BASE = int(sys.argv[3]) if len(sys.argv) > 3 else 80000
# 商品和价格可参数化：同一商品连续跑会撞「时间重叠」校验（这本身是 P1-5 的修复生效），
# 换一个商品就能继续验证。价格必须低于商品原价，否则会被创建接口拒掉。
PRODUCT_ID = int(sys.argv[4]) if len(sys.argv) > 4 else 2
SECKILL_PRICE = float(sys.argv[5]) if len(sys.argv) > 5 else 199.00

results = {}
lock = threading.Lock()
latencies = []


def http(method, url, body=None, token=None, headers=None, timeout=20):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}
    except Exception as e:
        return 0, {"error": str(e)}


def mint_token(uid, role="USER"):
    status, body = http("POST", f"{GATEWAY}/api/auth/token", {"userId": uid, "role": role})
    if status != 200:
        raise RuntimeError(f"取 token 失败 uid={uid} role={role} status={status} body={body}")
    return body["data"]["token"]


def section(title):
    print("\n" + "=" * 70)
    print(title)
    print("=" * 70)


def test_auth():
    section("1. 鉴权")
    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/list")
    print(f"  无 token              → HTTP {status} {body.get('message')}")
    assert status == 401, "无 token 竟然通过了"

    status, body = http("GET", f"{SECKILL_DIRECT}/api/seckill/activity/1/stock",
                        headers={"X-User-Id": "1"})
    print(f"  直连 8082 伪造身份     → HTTP {status} {body.get('message')}")
    assert status == 401, "直连伪造身份竟然通过了"

    status, body = http("GET", f"{ORDER_DIRECT}/api/order/result?activityId=1",
                        headers={"X-User-Id": "1"})
    print(f"  直连 8083 伪造身份     → HTTP {status} {body.get('message')}")
    assert status == 401, "直连伪造身份竟然通过了"

    token = mint_token(1001)
    status, body = http("GET", f"{GATEWAY}/api/auth/me", token=token)
    print(f"  合法 token 查身份      → HTTP {status} userId={body.get('data', {}).get('userId')}")
    assert status == 200 and body["data"]["userId"] == 1001, "网关注入的身份不对"
    assert body["data"]["role"] == "USER", "普通用户角色不对"

    admin = mint_token(9001, "ADMIN")
    status, body = http("GET", f"{GATEWAY}/api/auth/me", token=admin)
    assert status == 200 and body["data"]["role"] == "ADMIN", "管理员角色不对"

    # 直连伪造角色头：签名不含 role，改了角色签名就对不上
    status, body = http("POST", f"{SECKILL_DIRECT}/api/seckill/activity/999999/publish",
                        headers={"X-User-Id": "1", "X-User-Role": "ADMIN"})
    print(f"  直连伪造 ADMIN 角色    → HTTP {status}")
    assert status == 401, "直连伪造角色竟然通过了"
    return token, admin


def test_authorization(user_token, admin_token):
    section("1.5 授权（只有认证没有授权是上一版的漏洞）")
    body = {"productId": PRODUCT_ID, "title": "越权测试", "seckillPrice": 9.00,
            "totalStock": 1, "startTime": _fmt(600), "endTime": _fmt(3600)}

    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity", body, token=user_token)
    print(f"  普通用户建活动         → HTTP {status} {payload.get('message')}")
    assert status == 403, f"普通用户竟然能建活动：{status}"

    # 关键：不存在的活动 id 也必须先返回 403 而不是 404/400，
    # 否则等于告诉攻击者「这个 id 存不存在」
    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity/999999/publish", token=user_token)
    print(f"  普通用户发布不存在活动 → HTTP {status} {payload.get('message')}")
    assert status == 403, f"权限判断晚于业务校验：{status}"

    status, payload = http("POST", f"{GATEWAY}/api/seckill/activity/999999/publish", token=admin_token)
    print(f"  管理员发布不存在活动   → HTTP {status} {payload.get('message')}")
    assert status == 404, f"管理员过了授权层后应得到 404：{status}"

    status, payload = http("GET", f"{GATEWAY}/api/seckill/admin/outbox/review", token=user_token)
    print(f"  普通用户查待复核列表   → HTTP {status}")
    assert status == 403, "普通用户竟然能看运维列表"

    status, payload = http("GET", f"{GATEWAY}/api/seckill/admin/outbox/review", token=admin_token)
    print(f"  管理员查待复核列表     → HTTP {status} 共 {len(payload.get('data') or [])} 条")
    assert status == 200, f"管理员查询失败：{payload}"


def test_bad_request(token):
    section("2. 客户端错误不能变成 500")
    cases = [
        ("非法 JSON", "POST", f"{GATEWAY}/api/seckill/activity", "{not json", None),
        ("路径参数类型错", "GET", f"{GATEWAY}/api/order/result?activityId=abc", None, None),
        ("缺少必填参数", "GET", f"{GATEWAY}/api/order/result", None, None),
        ("接口不存在", "GET", f"{GATEWAY}/api/order/results?activityId=1", None, None),
        ("方法不支持", "GET", f"{GATEWAY}/api/seckill/activity", None, token),
        ("秒杀价不低于原价", "POST", f"{GATEWAY}/api/seckill/activity",
         {"productId": PRODUCT_ID, "title": "错误价格", "seckillPrice": 999.00,
          "totalStock": 10, "startTime": _fmt(120), "endTime": _fmt(7200)}, None),
    ]
    for name, method, url, body, tk in cases:
        tk = tk or token
        if isinstance(body, str):
            # 故意发语法错误的 JSON
            req = urllib.request.Request(url, data=body.encode(), method=method)
            req.add_header("Content-Type", "application/json")
            req.add_header("Authorization", "Bearer " + tk)
            try:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    status, payload = resp.status, json.loads(resp.read())
            except urllib.error.HTTPError as e:
                status, payload = e.code, json.loads(e.read().decode() or "{}")
        else:
            status, payload = http(method, url, body, token=tk)
        print(f"  {name:<16} → HTTP {status} {str(payload.get('message'))[:40]}")
        assert 400 <= status < 500, f"{name} 返回了 {status}，客户端错误被当成服务端故障"


def test_rate_limit():
    section("3. 单用户限流（replenishRate=20/s, burst=20）")
    uid = 5555
    token = mint_token(uid)
    codes = {}

    def hit(_):
        status, _body = http("POST", f"{GATEWAY}/api/seckill/order/1", token=token)
        with lock:
            codes[status] = codes.get(status, 0) + 1

    with ThreadPoolExecutor(max_workers=60) as pool:
        list(pool.map(hit, range(60)))

    print(f"  60 次突发请求结果：{json.dumps(codes, sort_keys=True)}")
    ok = codes.get(200, 0)
    limited = codes.get(429, 0)
    print(f"  通过 {ok} 次，被限流 {limited} 次")
    assert limited > 0, "限流没有生效，用户的 60 次请求全部通过"
    return codes


def _fmt(offset_seconds):
    return (datetime.now() + timedelta(seconds=offset_seconds)).strftime("%Y-%m-%d %H:%M:%S")


def create_activity(token):
    section("4. 创建 → 发布 → 预热")
    # 开始时间留 15 秒：既满足「未开始才能预热」的安全约束，
    # 又不用改数据库造假数据
    start_at = datetime.now() + timedelta(seconds=15)
    payload = {
        "productId": PRODUCT_ID,
        "title": f"限时秒杀 {datetime.now().strftime('%H%M%S')}",
        "seckillPrice": SECKILL_PRICE,
        "totalStock": STOCK,
        "startTime": start_at.strftime("%Y-%m-%d %H:%M:%S"),
        "endTime": (start_at + timedelta(minutes=30)).strftime("%Y-%m-%d %H:%M:%S"),
    }
    status, body = http("POST", f"{GATEWAY}/api/seckill/activity", payload, token=token)
    print(f"  创建活动 → HTTP {status} id={body.get('data')}")
    assert status == 200, f"创建失败 {body}"
    activity_id = body["data"]

    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/publish", token=token)
    print(f"  发布活动 → HTTP {status}")
    assert status == 200, f"发布失败 {body}"

    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/warmup", token=token)
    print(f"  预热库存 → HTTP {status} {body.get('data')}")
    assert status == 200, f"预热失败 {body}"

    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/{activity_id}/stock", token=token)
    print(f"  预热后视图 → {body.get('data')}")
    assert body["data"]["redisStock"] == STOCK, "预热后的 Redis 库存不等于活动总库存"
    return activity_id, start_at


def test_warmup_guard(token, activity_id):
    section("5. 预热幂等（未开始时可重复预热，但不会重置库存）")
    status, body = http("POST", f"{GATEWAY}/api/seckill/activity/{activity_id}/warmup", token=token)
    print(f"  重复预热 → HTTP {status} {body.get('data') or body.get('message')}")
    assert status == 200, f"重复预热失败 {body}"
    assert body["data"]["written"] is False, "重复预热竟然重置了库存"
    assert body["data"]["redisStock"] == STOCK, "重复预热后库存变了"


def run_concurrent(activity_id, tokens, start_at):
    section("6. 并发抢购")
    wait = (start_at - datetime.now()).total_seconds() + 2
    if wait > 0:
        print(f"  等待活动开始 {wait:.0f}s ...")
        time.sleep(wait)

    barrier = threading.Barrier(len(tokens))

    def worker(item):
        uid, token = item
        barrier.wait()
        t0 = time.time()
        status, body = http("POST", f"{GATEWAY}/api/seckill/order/{activity_id}", token=token)
        cost = (time.time() - t0) * 1000
        if status == 200:
            key = (body.get("data") or {}).get("status", "UNKNOWN")
        else:
            key = f"HTTP_{status}"
        with lock:
            results[key] = results.get(key, 0) + 1
            latencies.append(cost)

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=len(tokens)) as pool:
        list(pool.map(worker, tokens))
    elapsed = time.time() - t0

    print(f"  并发 {len(tokens)} 人，耗时 {elapsed:.2f}s，QPS≈{len(tokens) / elapsed:.0f}")
    print(f"  结果分布：{json.dumps(results, ensure_ascii=False, sort_keys=True)}")
    latencies.sort()
    if latencies:
        print(f"  延迟：P50={latencies[len(latencies) // 2]:.0f}ms "
              f"P99={latencies[int(len(latencies) * 0.99) - 1]:.0f}ms "
              f"max={latencies[-1]:.0f}ms")
    return results


def verify(activity_id, tokens, success_count, admin_token):
    section("7. 消费结果与对账")

    def query_one(item):
        uid, token = item
        status, body = http("GET", f"{GATEWAY}/api/order/result?activityId={activity_id}", token=token)
        data = body.get("data") or {}
        key = data.get("status", f"HTTP_{status}")
        return uid, key, data.get("orderNo")

    # 异步消费是最终一致，不能固定睡三秒后只查一次。这里在截止时间内轮询，
    # 直到成功订单达到预期，避免机器负载稍高就把正确结果误判成失败。
    deadline = time.time() + 20
    states = {}
    order_nos = []
    while True:
        states = {}
        order_nos = []
        with ThreadPoolExecutor(max_workers=min(32, len(tokens))) as pool:
            rows = list(pool.map(query_one, tokens))
        for _uid, key, order_no in rows:
            states[key] = states.get(key, 0) + 1
            if order_no:
                order_nos.append(order_no)
        if len(order_nos) >= success_count or time.time() >= deadline:
            break
        time.sleep(0.5)

    print(f"  查询结果分布：{json.dumps(states, ensure_ascii=False, sort_keys=True)}")
    print(f"  拿到订单号 {len(order_nos)} 个，示例：{order_nos[:3]}")
    assert len(set(order_nos)) == len(order_nos), "出现重复订单号"

    # 对账视图含 Redis 实时库存，属于运维信息，需要管理员
    status, body = http("GET", f"{GATEWAY}/api/seckill/activity/{activity_id}/stock",
                        token=admin_token)
    assert status == 200, f"对账视图查询失败 {status} {body}"
    stock = body["data"]
    print(f"  库存对账视图：{json.dumps(stock, ensure_ascii=False)}")

    ok = True
    if stock["redisStock"] != 0:
        print(f"  ✗ Redis 库存应为 0，实际 {stock['redisStock']}")
        ok = False
    if stock["boughtCount"] != STOCK:
        print(f"  ✗ 已抢人数应为 {STOCK}，实际 {stock['boughtCount']}")
        ok = False
    if stock["dbAvailableStock"] != stock["totalStock"] - len(order_nos):
        print(f"  ✗ 账本库存 {stock['dbAvailableStock']} != 总库存 {stock['totalStock']} - 订单 {len(order_nos)}")
        ok = False
    if success_count != STOCK:
        print(f"  ✗ 成功笔数 {success_count} != 库存 {STOCK}")
        ok = False
    if len(order_nos) != STOCK:
        print(f"  ✗ 落库订单 {len(order_nos)} 笔 != 库存 {STOCK}")
        ok = False
    print(f"  {'PASS：库存、买家数、订单数三者一致，无超卖' if ok else 'FAIL：对账不一致'}")
    return ok


def main():
    user_token, admin_token = test_auth()
    test_authorization(user_token, admin_token)
    # 建活动/发布/预热/对账都需要管理员，所以这一组用例走 admin
    test_bad_request(admin_token)
    test_rate_limit()
    activity_id, start_at = create_activity(admin_token)
    test_warmup_guard(admin_token, activity_id)

    section("6.0 批量取 token")
    users = [(USER_BASE + i, None) for i in range(CONCURRENCY)]
    with ThreadPoolExecutor(max_workers=32) as pool:
        minted = list(pool.map(lambda u: (u[0], mint_token(u[0])), users))
    print(f"  已取 {len(minted)} 个 token")

    run_concurrent(activity_id, minted, start_at)
    ok = verify(activity_id, minted, results.get("QUEUED", 0), admin_token)
    print(f"\n活动 ID = {activity_id}（可用 mysql 复核 seckill_activity / seckill_order / product）")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
